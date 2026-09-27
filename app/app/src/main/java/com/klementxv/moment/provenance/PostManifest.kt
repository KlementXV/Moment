package com.klementxv.moment.provenance

import com.klementxv.moment.chain.BorshWriter
import java.security.MessageDigest
import java.security.SecureRandom

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
        const val DOMAIN = "moment-manifest-v1"
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
