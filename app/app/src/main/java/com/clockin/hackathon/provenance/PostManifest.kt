package com.clockin.hackathon.provenance

import com.clockin.hackathon.chain.BorshWriter
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manifeste canonique `clockin-post-v1` (§7).
 *
 * Le wallet signe ces octets exacts via MWA ; `commitment()` est ce qui est
 * inscrit on-chain. Ce que cela prouve : cette paire d'images précise existait
 * et a été engagée par ce wallet, horodatée par le réseau. Ce que cela ne prouve
 * pas : qu'un capteur a observé la réalité.
 *
 * Les hashes portent sur les images **nettoyées**, jamais sur les octets bruts
 * du capteur. L'ordre est toujours arrière puis selfie.
 *
 * Chaque champ est de taille fixe ou préfixé en longueur : aucune concaténation
 * ambiguë n'est possible.
 */
data class PostManifest(
    val network: String,
    val programId: ByteArray,
    val wallet: ByteArray,
    val day: Long,
    val nonce: ByteArray,
    val rearHash: ByteArray,
    val frontHash: ByteArray,
) {
    init {
        require(programId.size == 32 && wallet.size == 32)
        require(nonce.size == NONCE_SIZE)
        require(rearHash.size == 32 && frontHash.size == 32)
        require(network.isNotEmpty() && network.length <= 32)
        require(network.all { it.code in 0x21..0x7E }) { "réseau non ASCII imprimable" }
    }

    fun serialize(): ByteArray = BorshWriter()
        .bytes(DOMAIN.toByteArray(Charsets.US_ASCII))
        .u8(VERSION)
        .u8(network.length)
        .bytes(network.toByteArray(Charsets.US_ASCII))
        .bytes(programId)
        .bytes(wallet)
        .i64(day)
        .bytes(nonce)
        .bytes(rearHash)
        .bytes(frontHash)
        .build()

    fun commitment(): ByteArray = MessageDigest.getInstance("SHA-256").digest(serialize())

    companion object {
        const val DOMAIN = "clockin-post-v1"
        const val VERSION = 1
        const val NONCE_SIZE = 16

        fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }

        fun of(
            network: String,
            programId: ByteArray,
            wallet: ByteArray,
            day: Long,
            rear: ByteArray,
            front: ByteArray,
            nonce: ByteArray = newNonce(),
        ): PostManifest = PostManifest(
            network = network,
            programId = programId,
            wallet = wallet,
            day = day,
            nonce = nonce,
            rearHash = sha256(rear),
            frontHash = sha256(front),
        )

        private fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)
    }
}
