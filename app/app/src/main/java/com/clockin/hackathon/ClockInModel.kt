package com.clockin.hackathon

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clockin.hackathon.capture.DraftCodec
import com.clockin.hackathon.capture.LocalDraft
import com.clockin.hackathon.capture.LocalEncryption
import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.chain.Base58
import com.clockin.hackathon.chain.CheckInAccount
import com.clockin.hackathon.chain.ClockInAccounts
import com.clockin.hackathon.chain.ClockInAddresses
import com.clockin.hackathon.chain.ClockInInstructions
import com.clockin.hackathon.chain.ConfigAccount
import com.clockin.hackathon.chain.ProfileAccount
import com.clockin.hackathon.chain.SolanaRpc
import com.clockin.hackathon.chain.TransactionBuilder
import com.clockin.hackathon.provenance.PostManifest
import com.clockin.hackathon.wallet.DevPublicationAuthority
import com.clockin.hackathon.wallet.WalletException
import com.clockin.hackathon.wallet.WalletFailure
import com.clockin.hackathon.wallet.WalletSession
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Une unité de SKR affichable : le mint de test a 9 décimales. */
const val SKR: Long = 1_000_000_000L

/**
 * État lu sur la chaîne. Source de vérité unique depuis la version 0.3 :
 * aucun solde n'est simulé côté app.
 */
data class ChainState(
    val config: ConfigAccount? = null,
    val profile: ProfileAccount? = null,
    val todayCheckIn: CheckInAccount? = null,
    val tokenBalance: Long = 0,
    val day: Long = 0,
    val loaded: Boolean = false,
) {
    /** Solde réel : celui du compte, après le decay déjà dû. */
    val balance: Long
        get() = if (profile == null || config == null) {
            0
        } else {
            profile.settledBalance(config.decayBps, config.maxDecayDays, day)
        }

    val minStake: Long get() = config?.minStake ?: (10 * SKR)
    val streak: Long get() = profile?.streak ?: 0
    val totalCheckIns: Long get() = profile?.totalCheckIns ?: 0
    val active: Boolean get() = profile?.active == true
    val hasProfile: Boolean get() = profile != null
    val faucetClaimed: Boolean get() = profile?.faucetClaimed == true
    val exitUnlockAt: Long get() = profile?.exitUnlockAt ?: 0
    val posted: Boolean get() = todayCheckIn != null

    /** Le feed n'est lisible qu'après son propre check-in (§3). */
    val feedUnlocked: Boolean get() = posted

    fun canPublish(now: Long): Boolean =
        active && !posted && balance >= minStake && (exitUnlockAt == 0L || now < exitUnlockAt)
}

/**
 * Orchestration de l'app : lecture des comptes, construction et envoi des
 * transactions, brouillon de capture chiffré localement.
 *
 * Rien n'est journalisé : ni photos, ni transactions, ni signatures.
 */
class ClockInModel(application: Application) : AndroidViewModel(application) {
    private val programId = SolanaPublicKey(Base58.decode(BuildConfig.PROGRAM_ID))
    private val mint: SolanaPublicKey? =
        BuildConfig.SKR_MINT.takeIf { it.isNotBlank() }?.let { SolanaPublicKey(Base58.decode(it)) }
    private val rpc = SolanaRpc(BuildConfig.RPC_URL)
    private val file = AtomicFile(File(application.noBackupFilesDir, "moment-draft.bin"))

    var state by mutableStateOf(ChainState())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf<PhotoPair?>(null)
        private set
    var wallet: WalletSession? = null

    val walletAddress: String? get() = wallet?.address?.base58()

    init {
        loadDraft()
    }

    fun dismissError() {
        error = null
    }

    fun replaceDraft(photos: PhotoPair?) {
        if (busy) return
        draft = photos
        viewModelScope.launch { saveDraft(photos) }
    }

    fun connect() {
        val session = wallet ?: return
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            session.connect()
                .onSuccess { refreshNow(it) }
                .onFailure { error = messageFor(it) }
            busy = false
        }
    }

    fun refresh() {
        val owner = wallet?.address ?: return
        viewModelScope.launch {
            try {
                refreshNow(owner)
            } catch (_: Exception) {
                error = "Lecture de la chaîne impossible. Vérifie ta connexion, puis réessaie."
            }
        }
    }

    fun claimFaucet() = submit { owner, skrMint ->
        buildList {
            if (!state.hasProfile) add(ClockInInstructions.createProfile(programId, owner))
            add(ClockInInstructions.createAssociatedTokenAccount(owner, owner, skrMint))
            add(
                ClockInInstructions.faucet(
                    programId, owner, skrMint, ClockInAddresses.associatedToken(owner, skrMint)
                )
            )
        }
    }

    fun stake(amount: Long) = submit { owner, skrMint ->
        listOf(
            ClockInInstructions.stake(
                programId, owner, skrMint,
                ClockInAddresses.associatedToken(owner, skrMint), amount
            )
        )
    }

    fun requestExit() = submit { owner, _ -> listOf(ClockInInstructions.requestExit(programId, owner)) }

    fun cancelExit() = submit { owner, _ -> listOf(ClockInInstructions.cancelExit(programId, owner)) }

    fun finalizeExit() = submit { owner, skrMint ->
        listOf(
            ClockInInstructions.finalizeExit(
                programId, owner, owner, skrMint,
                ClockInAddresses.associatedToken(owner, skrMint)
            )
        )
    }

    /**
     * Publication : manifeste signé par le wallet, puis transaction `check_in`
     * co-signée par l'autorité de publication.
     *
     * En semaine 1, cette co-signature vient d'une clé de développement présente
     * dans le seul build debug. Le plan 03 la remplace par le keyserver, qui
     * vérifiera les images avant de co-signer — et ajoutera l'envoi du blob
     * chiffré, dont `blob_ref` portera alors le hash réel.
     */
    fun publish(onSuccess: () -> Unit) {
        val photos = draft ?: return
        val session = wallet ?: return
        val owner = session.address ?: run { error = WalletFailure.NotConnected.message; return }
        val skrMint = mint ?: run { error = MINT_MISSING; return }
        val authority = DevPublicationAuthority.publicKey ?: run {
            error = "Autorité de publication absente de ce build."
            return
        }
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val day = utcDay()
                val manifest = PostManifest.of(
                    network = BuildConfig.NETWORK,
                    programId = programId.bytes,
                    wallet = owner.bytes,
                    day = day,
                    rear = photos.rear,
                    front = photos.front,
                )
                session.signManifest(manifest).getOrThrow()
                val commitment = manifest.commitment()
                // Tant que le blob n'est pas envoyé, blob_ref porte le commitment.
                // Le plan 03 y met le hash du blob chiffré réellement déposé.
                val instruction = ClockInInstructions.checkIn(
                    programId, owner, authority, day, commitment, commitment
                )
                sendSigned(listOf(instruction), owner, authority)
                // Le brouillon est conservé : c'est ce qui permet d'afficher son
                // propre Moment du jour tant que le feed chiffré n'existe pas.
                refreshNow(owner)
                onSuccess()
            } catch (failure: Exception) {
                error = messageFor(failure)
            } finally {
                busy = false
            }
        }
    }

    private fun submit(build: (SolanaPublicKey, SolanaPublicKey) -> List<TransactionInstruction>) {
        val owner = wallet?.address ?: run { error = WalletFailure.NotConnected.message; return }
        val skrMint = mint ?: run { error = MINT_MISSING; return }
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                sendSigned(build(owner, skrMint), owner, null)
                refreshNow(owner)
            } catch (failure: Exception) {
                error = messageFor(failure)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun sendSigned(
        instructions: List<TransactionInstruction>,
        owner: SolanaPublicKey,
        coSigner: SolanaPublicKey?,
    ) {
        val session = wallet ?: throw WalletException(WalletFailure.NotConnected)
        val blockhash = rpc.latestBlockhash()
        var transaction = TransactionBuilder.build(instructions, owner, blockhash)
        var authoritySignature: ByteArray? = null
        var authorityIndex = -1
        if (coSigner != null) {
            authorityIndex = TransactionBuilder.signatureIndex(transaction, coSigner)
            authoritySignature = DevPublicationAuthority.sign(transaction.message.serialize())
            transaction = TransactionBuilder.withSignature(transaction, coSigner, authoritySignature)
        }
        val signed = session.signTransaction(transaction.serialize()).getOrThrow()
        // La spec MWA demande au wallet de préserver les signatures existantes.
        // On vérifie plutôt que de faire confiance, et on restaure si besoin.
        val repaired = if (authoritySignature != null &&
            TransactionBuilder.isEmptySignature(TransactionBuilder.signatureAt(signed, authorityIndex))
        ) {
            TransactionBuilder.withSignatureAt(signed, authorityIndex, authoritySignature)
        } else {
            signed
        }
        val signature = rpc.sendTransaction(repaired)
        check(rpc.awaitConfirmation(signature)) {
            "Transaction envoyée mais non confirmée dans le délai imparti."
        }
    }

    private suspend fun refreshNow(owner: SolanaPublicKey) {
        val day = utcDay()
        val config = rpc.accountData(ClockInAddresses.config(programId))
            ?.let(ClockInAccounts::decodeConfig)
        val profile = rpc.accountData(ClockInAddresses.profile(programId, owner))
            ?.let(ClockInAccounts::decodeProfile)
        val checkIn = rpc.accountData(ClockInAddresses.checkIn(programId, owner, day))
            ?.let(ClockInAccounts::decodeCheckIn)
        val balance = mint?.let { rpc.tokenBalance(ClockInAddresses.associatedToken(owner, it)) } ?: 0
        state = ChainState(config, profile, checkIn, balance, day, loaded = true)
    }

    private fun messageFor(failure: Throwable): String = when (failure) {
        is WalletException -> failure.failure.message
        else -> "Transaction impossible. Vérifie ta connexion, puis réessaie."
    }

    // --- brouillon local chiffré ---

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun loadDraft() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                try {
                    if (!file.baseFile.exists()) return@withContext null
                    val sealed = file.openRead().use { it.readBytes() }
                    DraftCodec.decode(LocalEncryption.decrypt(sealed, keystoreKey()))
                } catch (_: Exception) {
                    null
                }
            }
            if (restored != null && restored.day == utcDay()) draft = restored.photos
        }
    }

    private suspend fun saveDraft(photos: PhotoPair?) = withContext(Dispatchers.IO) {
        try {
            if (photos == null) {
                file.delete()
                return@withContext
            }
            val draftToStore = LocalDraft(utcDay(), photos, PostManifest.newNonce())
            val sealed = LocalEncryption.encrypt(DraftCodec.encode(draftToStore), keystoreKey())
            val output = file.startWrite()
            try {
                output.write(sealed)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        } catch (_: Exception) {
            // Le brouillon est un confort de reprise : son échec ne doit pas
            // interrompre une capture en cours.
        }
    }

    private companion object {
        const val KEY_ALIAS = "moment-local-v2"
        const val MINT_MISSING =
            "Mint SKR non configuré. Renseigne clockin.skrMint dans local.properties."

        fun utcDay(): Long = Math.floorDiv(Instant.now().epochSecond, 86_400L)
    }
}
