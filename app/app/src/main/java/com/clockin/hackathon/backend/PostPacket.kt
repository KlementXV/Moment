package com.clockin.hackathon.backend

import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.chain.BorshWriter
import com.clockin.hackathon.provenance.PostManifest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun verifySignature(key: ByteArray, message: ByteArray, signature: ByteArray) {
    require(key.size == 32 && signature.size == 64)
    require(Ed25519Signer().run {
        init(false, Ed25519PublicKeyParameters(key, 0))
        update(message, 0, message.size)
        verifySignature(signature)
    }) { "Signature invalide." }
}

data class RemoteMoment(val wallet: String, val commitment: String, val photos: PhotoPair)

data class SealedPost(val day: Long, val commitment: String, val blobRef: String, val key: ByteArray, val blob: ByteArray)

object PostPacket {
    private val domain = "moment-post-v1".toByteArray()
    const val MAX_BLOB = 8 * 1024 * 1024 + 4096

    fun seal(manifest: PostManifest, signature: ByteArray, photos: PhotoPair): SealedPost {
        val bytes = manifest.serialize()
        verifySignature(manifest.wallet, bytes, signature)
        require(sha256(photos.rear).contentEquals(manifest.rearHash))
        require(sha256(photos.front).contentEquals(manifest.frontHash))
        val writer = BorshWriter().bytes(domain).u8(1)
        listOf(bytes, signature, photos.rear, photos.front).forEach {
            require(it.isNotEmpty() && it.size <= 4 * 1024 * 1024)
            writer.u32(it.size.toLong()).bytes(it)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.updateAAD(domain)
        val blob = cipher.iv + cipher.doFinal(writer.build())
        return SealedPost(manifest.day, sha256(bytes).hex(), sha256(blob).hex(), key, blob)
    }

    fun open(post: SealedPost, wallet: ByteArray, network: String, program: ByteArray): PhotoPair {
        require(post.key.size == 32 && post.blob.size in 28..MAX_BLOB)
        require(sha256(post.blob).hex() == post.blobRef)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(post.key, "AES"), GCMParameterSpec(128, post.blob.copyOfRange(0, 12)))
        cipher.updateAAD(domain)
        val input = ByteBuffer.wrap(cipher.doFinal(post.blob, 12, post.blob.size - 12)).order(ByteOrder.LITTLE_ENDIAN)
        fun take(n: Int): ByteArray {
            require(n >= 0 && n <= input.remaining())
            return ByteArray(n).also { input.get(it) }
        }
        require(take(domain.size).contentEquals(domain) && input.get().toInt() == 1)
        fun field(max: Int): ByteArray { val n = input.int; require(n in 1..max); return take(n) }
        val manifest = field(512)
        val signature = field(64)
        val photos = PhotoPair(field(4 * 1024 * 1024), field(4 * 1024 * 1024))
        require(!input.hasRemaining() && sha256(manifest).hex() == post.commitment)
        // Reconstruct the canonical bytes, preserving only the signed nonce.
        val nonceOffset = PostManifest.DOMAIN.length + 2 + network.length + 64 + 8
        require(manifest.size == nonceOffset + 16 + 64)
        val expected = PostManifest.of(network, program, wallet, post.day, photos.rear, photos.front,
            manifest.copyOfRange(nonceOffset, nonceOffset + 16)).serialize()
        require(manifest.contentEquals(expected)) { "Manifeste incompatible avec le Moment demandé." }
        verifySignature(wallet, manifest, signature)
        return photos
    }
}
