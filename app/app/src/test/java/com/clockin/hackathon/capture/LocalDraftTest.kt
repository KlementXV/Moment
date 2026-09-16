package com.clockin.hackathon.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator

class LocalDraftTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun sample() = LocalDraft(
        day = 20_706L,
        photos = PhotoPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
        nonce = ByteArray(DraftCodec.NONCE_SIZE) { 7 },
    )

    @Test
    fun `photos day and nonce roundtrip as one draft`() {
        val before = sample()
        val after = DraftCodec.decode(DraftCodec.encode(before))
        assertEquals(before.day, after.day)
        assertArrayEquals(before.nonce, after.nonce)
        assertArrayEquals(before.photos.rear, after.photos.rear)
        assertArrayEquals(before.photos.front, after.photos.front)
    }

    @Test
    fun `a modified image hash is rejected`() {
        val bytes = DraftCodec.encode(sample())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(bytes) }
    }

    @Test
    fun `unknown format truncation and trailing bytes are rejected`() {
        val bytes = DraftCodec.encode(sample())
        assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(bytes + 0) }
        assertThrows(Exception::class.java) { DraftCodec.decode(bytes.copyOf(10)) }
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(bytes) }
    }

    @Test
    fun `oversized and empty photos are rejected before serialization`() {
        assertThrows(IllegalArgumentException::class.java) {
            DraftCodec.encode(
                sample().copy(
                    photos = PhotoPair(ByteArray(DraftCodec.MAX_PHOTO_BYTES + 1), byteArrayOf(1))
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DraftCodec.encode(sample().copy(photos = PhotoPair(byteArrayOf(1), ByteArray(0))))
        }
    }

    @Test
    fun `encryption is randomized for the same plaintext`() {
        val secret = key()
        val plain = DraftCodec.encode(sample())
        val first = LocalEncryption.encrypt(plain, secret)
        val second = LocalEncryption.encrypt(plain, secret)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, LocalEncryption.decrypt(first, secret))
        assertArrayEquals(plain, LocalEncryption.decrypt(second, secret))
    }

    @Test
    fun `the wrong key cannot decrypt`() {
        val sealed = LocalEncryption.encrypt(DraftCodec.encode(sample()), key())
        assertThrows(Exception::class.java) { LocalEncryption.decrypt(sealed, key()) }
    }

    @Test
    fun `a tampered ciphertext or nonce is rejected`() {
        val secret = key()
        val sealed = LocalEncryption.encrypt(DraftCodec.encode(sample()), secret)

        val tamperedBody = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { LocalEncryption.decrypt(tamperedBody, secret) }

        val tamperedNonce = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { LocalEncryption.decrypt(tamperedNonce, secret) }
    }

    @Test
    fun `a draft without its nonce is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DraftCodec.encode(sample().copy(nonce = ByteArray(4)))
        }
    }
}
