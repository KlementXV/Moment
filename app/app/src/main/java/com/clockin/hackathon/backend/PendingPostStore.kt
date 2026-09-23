package com.clockin.hackathon.backend

import android.util.AtomicFile
import com.clockin.hackathon.capture.LocalEncryption
import java.io.*
import javax.crypto.SecretKey

/** Persist before the first upload. Failure must abort publication, never lose the retry key. */
class PendingPostStore(private val directory: File, private val key: () -> SecretKey) {
    private fun file(wallet: String, day: Long) = AtomicFile(File(directory, "pending-${sha256(wallet.toByteArray()).hex()}-$day.bin"))
    fun read(wallet: String, day: Long): SealedPost? {
        val file = file(wallet, day)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { it.readBytes() }
        return PendingPostCodec.decode(day, LocalEncryption.decrypt(bytes, key()))
    }
    fun write(wallet: String, post: SealedPost) {
        val sealed = LocalEncryption.encrypt(PendingPostCodec.encode(post), key())
        val file = file(wallet, post.day)
        val output = file.startWrite()
        try { output.write(sealed); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }
    fun clear(wallet: String, day: Long) = file(wallet, day).delete()
}

object PendingPostCodec {
    fun encode(post: SealedPost): ByteArray {
        require(post.key.size == 32 && post.blob.size in 28..PostPacket.MAX_BLOB)
        require(post.commitment.matches(Regex("[0-9a-f]{64}")) && sha256(post.blob).hex() == post.blobRef)
        return ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use {
            it.writeInt(1); it.writeLong(post.day); it.writeUTF(post.commitment); it.writeUTF(post.blobRef)
            it.write(post.key); it.writeInt(post.blob.size); it.write(post.blob)
        } }.toByteArray()
    }
    fun decode(day: Long, bytes: ByteArray): SealedPost {
        require(bytes.size <= PostPacket.MAX_BLOB + 256)
        return DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() == 1 && it.readLong() == day)
            val commitment = it.readUTF(); val ref = it.readUTF()
            require(commitment.matches(Regex("[0-9a-f]{64}")))
            val postKey = ByteArray(32).also(it::readFully)
            val size = it.readInt(); require(size in 28..PostPacket.MAX_BLOB && size == it.available())
            val blob = ByteArray(size).also(it::readFully)
            require(sha256(blob).hex() == ref)
            SealedPost(day, commitment, ref, postKey, blob)
        }
    }
}
