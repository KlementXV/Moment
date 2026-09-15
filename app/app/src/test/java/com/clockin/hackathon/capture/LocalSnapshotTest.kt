package com.clockin.hackathon.capture

import com.clockin.hackathon.demo.DemoSession
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class LocalSnapshotTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun sample() = LocalSnapshot(DemoSession().faucet().stake(5_000, 100 * 86_400L).checkIn(100 * 86_400L),
        LocalPost(100, PhotoPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))))

    @Test fun `photos and position roundtrip as one snapshot`() {
        val before = sample()
        val after = SnapshotCodec.decode(SnapshotCodec.encode(before))
        assertEquals(before.session, after.session)
        assertEquals(before.post!!.day, after.post!!.day)
        assertArrayEquals(before.post.photos.rear, after.post.photos.rear)
        assertArrayEquals(before.post.photos.front, after.post.photos.front)
    }
    @Test fun `session without photos also persists`() {
        assertEquals(LocalSnapshot(), SnapshotCodec.decode(SnapshotCodec.encode(LocalSnapshot())))
    }
    @Test fun `modified image hash is rejected`() {
        val bytes = SnapshotCodec.encode(sample())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(bytes) }
    }
    @Test fun `unknown format truncation and trailing bytes are rejected`() {
        val bytes = SnapshotCodec.encode(sample())
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(bytes + 0) }
        assertThrows(Exception::class.java) { SnapshotCodec.decode(bytes.copyOf(10)) }
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(bytes) }
    }
    @Test fun `oversized and empty photos are rejected before serialization`() {
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotCodec.encode(LocalSnapshot(post = LocalPost(0, PhotoPair(ByteArray(SnapshotCodec.MAX_PHOTO_BYTES + 1), byteArrayOf(1)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotCodec.encode(LocalSnapshot(post = LocalPost(0, PhotoPair(byteArrayOf(), byteArrayOf(1)))))
        }
    }
    @Test fun `encrypted payload roundtrips with a fresh nonce each time`() {
        val key = key()
        val plain = SnapshotCodec.encode(sample())
        val one = LocalEncryption.encrypt(plain, key)
        val two = LocalEncryption.encrypt(plain, key)
        assertFalse(one.contentEquals(two))
        assertArrayEquals(plain, LocalEncryption.decrypt(one, key))
        assertArrayEquals(plain, LocalEncryption.decrypt(two, key))
    }
    @Test fun `modified ciphertext nonce and wrong key are rejected`() {
        val key = key()
        val sealed = LocalEncryption.encrypt(SnapshotCodec.encode(sample()), key)
        assertThrows(Exception::class.java) { LocalEncryption.decrypt(sealed, key()) }
        for (index in listOf(0, 12, sealed.lastIndex)) {
            val broken = sealed.clone()
            broken[index] = (broken[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { LocalEncryption.decrypt(broken, key) }
        }
    }
    @Test fun `swapping camera order changes the serialized commitment`() {
        val before = sample()
        val photos = before.post!!.photos
        val swapped = before.copy(post = before.post.copy(photos = PhotoPair(photos.front, photos.rear)))
        assertFalse(SnapshotCodec.encode(before).contentEquals(SnapshotCodec.encode(swapped)))
    }
}
