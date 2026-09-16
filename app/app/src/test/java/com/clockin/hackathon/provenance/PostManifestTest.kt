package com.clockin.hackathon.provenance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

class PostManifestTest {
    private val programId = ByteArray(32) { 1 }
    private val wallet = ByteArray(32) { 2 }
    private val nonce = ByteArray(16) { 3 }
    private val rear = "arrière".toByteArray(Charsets.UTF_8)
    private val front = "selfie".toByteArray(Charsets.UTF_8)

    private fun manifest(
        network: String = "devnet",
        day: Long = 20_706L,
        rearPhoto: ByteArray = rear,
        frontPhoto: ByteArray = front,
    ) = PostManifest.of(network, programId, wallet, day, rearPhoto, frontPhoto, nonce)

    @Test
    fun the_serialized_layout_is_fixed_and_unambiguous() {
        val bytes = manifest().serialize()
        // 15 domaine + 1 version + 1 longueur + 6 réseau + 32 + 32 + 8 + 16 + 32 + 32
        assertEquals(175, bytes.size)
        assertEquals("clockin-post-v1", String(bytes.copyOfRange(0, 15)))
        assertEquals(1, bytes[15].toInt())
        assertEquals(6, bytes[16].toInt())
        assertEquals("devnet", String(bytes.copyOfRange(17, 23)))
        assertArrayEquals(programId, bytes.copyOfRange(23, 55))
        assertArrayEquals(wallet, bytes.copyOfRange(55, 87))
        assertArrayEquals(sha256(rear), bytes.copyOfRange(111, 143))
        assertArrayEquals(sha256(front), bytes.copyOfRange(143, 175))
    }

    @Test
    fun the_commitment_is_the_hash_of_those_exact_bytes() {
        val subject = manifest()
        assertArrayEquals(sha256(subject.serialize()), subject.commitment())
        assertEquals(32, subject.commitment().size)
    }

    @Test
    fun swapping_the_two_photos_changes_the_commitment() {
        assertNotEquals(
            manifest().commitment().toList(),
            manifest(rearPhoto = front, frontPhoto = rear).commitment().toList(),
        )
    }

    @Test
    fun changing_a_single_byte_of_a_photo_changes_the_commitment() {
        val altered = rear.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(
            manifest().commitment().toList(),
            manifest(rearPhoto = altered).commitment().toList(),
        )
    }

    @Test
    fun changing_the_day_or_the_network_changes_the_commitment() {
        assertNotEquals(manifest().commitment().toList(), manifest(day = 20_707L).commitment().toList())
        assertNotEquals(manifest().commitment().toList(), manifest(network = "mainnet").commitment().toList())
    }

    @Test
    fun a_golden_vector_pins_the_format_across_the_app_and_the_server() {
        // Calculé indépendamment, hors de ce code (voir docs/manifest-v1.md) :
        // programId = 32 × 0x01, wallet = 32 × 0x02, nonce = 16 × 0x03,
        // day = 20706, réseau "devnet", photos "arrière" et "selfie" en UTF-8.
        assertEquals(GOLDEN_COMMITMENT, manifest().commitment().joinToString("") { "%02x".format(it) })
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private companion object {
        const val GOLDEN_COMMITMENT = "cd6e5720f11bf646617ef9ebf69bf2cfdf656cc36f1421e9cf6954fd8e69f093"
    }
}
