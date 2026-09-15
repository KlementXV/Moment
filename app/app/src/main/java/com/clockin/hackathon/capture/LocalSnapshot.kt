package com.clockin.hackathon.capture

import com.clockin.hackathon.demo.DemoSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Clean JPEGs only. Camera buffers and original metadata must never enter this model. */
data class PhotoPair(val rear: ByteArray, val front: ByteArray)
data class LocalPost(val day: Long, val photos: PhotoPair)
data class LocalSnapshot(val session: DemoSession = DemoSession(), val post: LocalPost? = null)

/** Versioned local format, NOT a wallet manifest or an on-chain publication proof. */
object SnapshotCodec {
    private const val MAGIC = 0x4D4F4D31 // MOM1
    const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
    const val MAX_SNAPSHOT_BYTES = 2 * MAX_PHOTO_BYTES + 1024
    fun encode(snapshot: LocalSnapshot): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            with(snapshot.session) {
                listOf(available, staked, pool, streak, total, lastCheckIn, settledDay, exitUnlock).forEach(out::writeLong)
                out.writeBoolean(active); out.writeBoolean(faucetClaimed)
            }
            out.writeBoolean(snapshot.post != null)
            snapshot.post?.let { post ->
                out.writeLong(post.day)
                listOf(post.photos.rear, post.photos.front).forEach { photo ->
                    require(photo.isNotEmpty() && photo.size <= MAX_PHOTO_BYTES)
                    out.writeInt(photo.size); out.write(photo)
                    out.write(MessageDigest.getInstance("SHA-256").digest(photo))
                }
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): LocalSnapshot {
        require(bytes.size <= MAX_SNAPSHOT_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val values = List(8) { input.readLong() }
            require(values.take(5).all { it >= 0 } && values[7] >= 0)
            val session = DemoSession(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], input.readBoolean(), input.readBoolean())
            val post = if (input.readBoolean()) {
                val day = input.readLong()
                fun photo(): ByteArray {
                    val count = input.readInt()
                    require(count in 1..MAX_PHOTO_BYTES && count <= input.available() - 32)
                    val image = ByteArray(count).also(input::readFully)
                    val hash = ByteArray(32).also(input::readFully)
                    require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(image)))
                    return image
                }
                LocalPost(day, PhotoPair(photo(), photo()))
            } else null
            require(input.available() == 0)
            LocalSnapshot(session, post)
        }
    }
}

object LocalEncryption {
    private val domain = "moment-local-v1".toByteArray(Charsets.UTF_8)
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        require(plain.size <= SnapshotCodec.MAX_SNAPSHOT_BYTES)
        // Android Keystore generates a fresh, provider-approved random IV for every encryption.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(domain)
        return cipher.iv + cipher.doFinal(plain)
    }
    fun decrypt(sealed: ByteArray, key: SecretKey): ByteArray {
        require(sealed.size in 28..(SnapshotCodec.MAX_SNAPSHOT_BYTES + 28))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        cipher.updateAAD(domain)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}
