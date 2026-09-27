package com.klementxv.moment.provenance

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
        assertEquals(178, bytes.size)
        assertEquals("moment-manifest-v1", String(bytes.copyOfRange(0, 18)))
        assertEquals(1, bytes[18].toInt())
        assertEquals(6, bytes[19].toInt())
        assertEquals("devnet", String(bytes.copyOfRange(20, 26)))
        assertArrayEquals(programId, bytes.copyOfRange(26, 58))
        assertArrayEquals(wallet, bytes.copyOfRange(58, 90))
        assertArrayEquals(sha256(rear), bytes.copyOfRange(114, 146))
        assertArrayEquals(sha256(front), bytes.copyOfRange(146, 178))
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
        assertEquals(GOLDEN_COMMITMENT, manifest().commitment().joinToString("") { "%02x".format(it) })
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private companion object {
        const val GOLDEN_COMMITMENT = "5de9efcc791d4672569365d2c31f352d54b822208369f52807bc24abb3b85eb5"
    }
}
