package com.klementxv.moment.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PhotoPair(val rear: ByteArray, val front: ByteArray)

data class LocalDraft(
    val day: Long,
    val photos: PhotoPair,
    val nonce: ByteArray,
    val caption: String = "",
)

object DraftCodec {
    private const val MAGIC = 0x4D4F4D33
    const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
    const val MAX_DRAFT_BYTES = 2 * MAX_PHOTO_BYTES + 1024
    const val NONCE_SIZE = 16
    const val MAX_CAPTION_CHARS = 80

    fun encode(draft: LocalDraft): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(draft.day)
            require(draft.nonce.size == NONCE_SIZE)
            out.write(draft.nonce)
            require(draft.caption.codePointCount(0, draft.caption.length) <= MAX_CAPTION_CHARS)
            out.writeUTF(draft.caption)
            listOf(draft.photos.rear, draft.photos.front).forEach { photo ->
                require(photo.isNotEmpty() && photo.size <= MAX_PHOTO_BYTES)
                out.writeInt(photo.size)
                out.write(photo)
                out.write(MessageDigest.getInstance("SHA-256").digest(photo))
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): LocalDraft {
        require(bytes.size <= MAX_DRAFT_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val day = input.readLong()
            val nonce = ByteArray(NONCE_SIZE).also(input::readFully)
            val caption = input.readUTF()
            require(caption.codePointCount(0, caption.length) <= MAX_CAPTION_CHARS)
            fun photo(): ByteArray {
                val count = input.readInt()
                require(count in 1..MAX_PHOTO_BYTES && count <= input.available() - 32)
                val image = ByteArray(count).also(input::readFully)
                val hash = ByteArray(32).also(input::readFully)
                require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(image)))
                return image
            }
            val draft = LocalDraft(day, PhotoPair(photo(), photo()), nonce, caption)
            require(input.available() == 0)
            draft
        }
    }
}

object LocalEncryption {
    private val domain = "moment-local-v2".toByteArray(Charsets.UTF_8)

    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        require(plain.size <= DraftCodec.MAX_DRAFT_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(domain)
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(sealed: ByteArray, key: SecretKey): ByteArray {
        require(sealed.size in 28..(DraftCodec.MAX_DRAFT_BYTES + 28))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        cipher.updateAAD(domain)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}
