package com.clockin.hackathon.capture

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clockin.hackathon.demo.DemoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** One encrypted, atomic snapshot: a photo and its demo check-in always commit together. */
class MomentModel(application: Application) : AndroidViewModel(application) {
    var snapshot by mutableStateOf(LocalSnapshot())
        private set
    var draft by mutableStateOf<PhotoPair?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val file = AtomicFile(File(application.noBackupFilesDir, "moment-local.bin"))
    init { reload() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("moment-local-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("moment-local-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun reload() {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) {
                    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) LocalSnapshot()
                    else file.openRead().use { stream ->
                        val data = stream.readBytesBounded(SnapshotCodec.MAX_SNAPSHOT_BYTES + 28)
                        SnapshotCodec.decode(LocalEncryption.decrypt(data, key()))
                    }
                }
                ready = true
            } catch (_: Exception) {
                error = "Impossible d’ouvrir les données locales. Réessaie sans effacer tes photos."
            } finally { busy = false }
        }
    }
    fun replaceDraft(photos: PhotoPair?) { if (!busy) draft = photos }
    fun dismissError() { error = null }
    fun update(transform: (DemoSession) -> DemoSession) = commit({ current ->
        current.copy(session = transform(current.session))
    })
    fun publish(onSuccess: () -> Unit) {
        val photos = draft ?: return
        commit({ current ->
            val now = Instant.now().epochSecond
            LocalSnapshot(current.session.checkIn(now), LocalPost(now / DemoSession.DAY, photos))
        }, { draft = null; onSuccess() })
    }
    private fun commit(transform: (LocalSnapshot) -> LocalSnapshot, onSuccess: () -> Unit = {}) {
        if (busy || !ready) return
        val next = try { transform(snapshot) } catch (_: IllegalStateException) {
            error = "Ta position a changé. Vérifie ton solde et la journée en cours avant de réessayer."; return
        } catch (_: IllegalArgumentException) {
            error = "Cette mise n’est pas disponible. Vérifie ton solde."; return
        }
        busy = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val encrypted = LocalEncryption.encrypt(SnapshotCodec.encode(next), key())
                    val output = file.startWrite()
                    try { output.write(encrypted); file.finishWrite(output) }
                    catch (e: Exception) { file.failWrite(output); throw e }
                }
                snapshot = next
                onSuccess()
            } catch (_: Exception) {
                error = "Sauvegarde impossible. Ton aperçu est conservé ; libère de l’espace puis réessaie."
            } finally { busy = false }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= max)
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
