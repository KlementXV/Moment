package com.clockin.hackathon.backend

import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.chain.*
import com.clockin.hackathon.provenance.PostManifest
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class BackendTest {
    private val secret = Ed25519PrivateKeyParameters(ByteArray(32) { 7 }, 0)
    private val wallet = secret.generatePublicKey().encoded
    private val program = ByteArray(32) { 3 }
    private val photos = PhotoPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
    private fun sign(bytes: ByteArray) = Ed25519Signer().run {
        init(true, secret); update(bytes, 0, bytes.size); generateSignature()
    }
    private fun packet(): SealedPost {
        val manifest = PostManifest.of("devnet", program, wallet, 20718, photos.rear, photos.front)
        return PostPacket.seal(manifest, sign(manifest.serialize()), photos)
    }
    @Test fun sharedRustAndroidPacket() {
        val fixture = Json.parseToJsonElement(java.io.File("../../keyserver/tests/fixtures/android-post-v1.json").readText()).jsonObject
        val post = SealedPost(fixture.string("day").toLong(), fixture.string("commitment"), fixture.string("blobRef"),
            unb64(fixture.string("postKey")), unb64(fixture.string("blob")))
        val decoded = PostPacket.open(post, unb64(fixture.string("wallet")), fixture.string("network"), unb64(fixture.string("program")))
        assertArrayEquals(unb64(fixture.string("rear")), decoded.rear)
        assertArrayEquals(unb64(fixture.string("front")), decoded.front)
        val authority = Ed25519PrivateKeyParameters(ByteArray(32) { 8 }, 0).generatePublicKey().encoded
        fun hexBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tx = TransactionBuilder.build(listOf(ClockInInstructions.checkIn(SolanaPublicKey(program), SolanaPublicKey(wallet),
            SolanaPublicKey(authority), post.day, hexBytes(post.commitment), hexBytes(post.blobRef))), SolanaPublicKey(wallet), Base58.encode(ByteArray(32) { 9 }))
        assertArrayEquals(unb64(fixture.string("transaction")), tx.serialize())
        assertArrayEquals(byteArrayOf(2, 1, 2), tx.message.serialize().copyOfRange(0, 3))
    }
    @Test fun encryptedPendingPostSurvivesRestartWithoutChangingReferences() {
        val original = packet()
        val deviceKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val diskBytes = com.clockin.hackathon.capture.LocalEncryption.encrypt(PendingPostCodec.encode(original), deviceKey)
        val restored = PendingPostCodec.decode(original.day, com.clockin.hackathon.capture.LocalEncryption.decrypt(diskBytes, deviceKey))
        assertEquals(original.commitment, restored.commitment)
        assertEquals(original.blobRef, restored.blobRef)
        assertArrayEquals(original.key, restored.key)
        assertArrayEquals(original.blob, restored.blob)
        assertArrayEquals(photos.front, PostPacket.open(restored, wallet, "devnet", program).front)
        val clear = PendingPostCodec.encode(original)
        assertThrows(Exception::class.java) { PendingPostCodec.decode(original.day + 1, clear) }
        assertThrows(Exception::class.java) { PendingPostCodec.decode(original.day, clear + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { PendingPostCodec.decode(original.day, clear.copyOf(clear.size - 1)) }
        diskBytes[diskBytes.lastIndex] = (diskBytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { com.clockin.hackathon.capture.LocalEncryption.decrypt(diskBytes, deviceKey) }
    }

    @Test fun packetRoundtripAndTampering() {
        val post = packet()
        assertArrayEquals(photos.rear, PostPacket.open(post, wallet, "devnet", program).rear)
        fun rejects(p: SealedPost = post, w: ByteArray = wallet, network: String = "devnet", id: ByteArray = program) {
            assertThrows(Exception::class.java) { PostPacket.open(p, w, network, id) }
        }
        rejects(post.copy(day = post.day + 1))
        rejects(post.copy(commitment = "0".repeat(64)))
        rejects(post.copy(key = ByteArray(32)))
        rejects(w = ByteArray(32)); rejects(network = "mainnet"); rejects(id = ByteArray(32))
        val tampered = post.blob.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejects(post.copy(blob = tampered, blobRef = sha256(tampered).hex()))
    }
    @Test fun packetRejectsWrongSignatureAndImages() {
        val manifest = PostManifest.of("devnet", program, wallet, 20718, photos.rear, photos.front)
        assertThrows(Exception::class.java) { PostPacket.seal(manifest, ByteArray(64), photos) }
        assertThrows(Exception::class.java) { PostPacket.seal(manifest, sign(manifest.serialize()), PhotoPair(photos.front, photos.rear)) }
    }
    @Test fun randomEncryptionCreatesDistinctReferences() {
        val first = packet(); val second = packet()
        assertNotEquals(first.blobRef, second.blobRef)
        assertFalse(first.key.contentEquals(second.key))
    }
    @Test fun onlySecureOrExplicitLocalOrigins() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com?x=1", "").forEach {
            assertThrows(Exception::class.java) { KeyServer(it, true) }
        }
        assertThrows(Exception::class.java) { KeyServer("http://localhost:8000") }
        assertEquals("https://example.com", KeyServer("https://example.com/").origin)
    }
    @Test fun transactionMessageAndSignatureSlots() {
        val owner = SolanaPublicKey(wallet)
        val authority = SolanaPublicKey(ByteArray(32) { 8 })
        val tx = TransactionBuilder.build(listOf(ClockInInstructions.checkIn(SolanaPublicKey(program), owner, authority, 20718, ByteArray(32), ByteArray(32))), owner, Base58.encode(ByteArray(32) { 9 }))
        assertArrayEquals(tx.message.serialize(), TransactionBuilder.messageBytes(tx.serialize()))
        val signed = TransactionBuilder.withSignatureAt(tx.serialize(), 0, sign(tx.message.serialize()))
        verifySignature(wallet, TransactionBuilder.messageBytes(signed), TransactionBuilder.signatureAt(signed, 0))
        assertThrows(Exception::class.java) { TransactionBuilder.messageBytes(byteArrayOf(2)) }
        assertThrows(Exception::class.java) { TransactionBuilder.signatureAt(signed, -1) }
    }
    @Test fun authenticationChallengeIsBoundToTheConfiguredDeployment() {
        val nonce = "a".repeat(43)
        val message = "Moment — connexion au serveur de clés\norigine: https://example.com\nréseau: devnet\nprogramme: program\nwallet: wallet\nnonce: $nonce\nexpire: 1300\nCette signature ouvre une session de 15 minutes et n’autorise aucun transfert."
        val challenge = buildJsonObject { put("nonce", nonce); put("message", message); put("expiresAt", 1300) }
        assertArrayEquals(message.toByteArray(), challengeMessage(challenge, "https://example.com", "devnet", "program", "wallet", 1000))
        assertThrows(Exception::class.java) { challengeMessage(challenge, "https://evil.com", "devnet", "program", "wallet", 1000) }
        assertThrows(Exception::class.java) { challengeMessage(challenge, "https://example.com", "mainnet", "program", "wallet", 1000) }
        assertThrows(Exception::class.java) { challengeMessage(challenge, "https://example.com", "devnet", "other", "wallet", 1000) }
        assertThrows(Exception::class.java) { challengeMessage(challenge, "https://example.com", "devnet", "program", "other", 1000) }
        assertThrows(Exception::class.java) { challengeMessage(challenge, "https://example.com", "devnet", "program", "wallet", 1300) }
        assertEquals("https://example.com", KeyServer("https://EXAMPLE.com:443/").origin)
    }

    @Test fun errorsAndRedirectsDoNotForwardCredentials() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = false
        server.createContext("/v1/feed") { exchange ->
            exchange.responseHeaders.add("Location", "/stolen")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/stolen") { exchange -> redirected = true; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.createContext("/v1/posts") { exchange -> exchange.sendResponseHeaders(401, -1); exchange.close() }
        server.start()
        try {
            val api = KeyServer("http://127.0.0.1:${server.address.port}", true)
            try { api.feed(20718, null, "secret"); fail("Redirect accepted") }
            catch (e: BackendException) { assertEquals(302, e.status) }
            assertFalse(redirected)
            try { api.confirm("a".repeat(64), "expired"); fail("Expired token accepted") }
            catch (e: BackendException) { assertEquals(401, e.status) }
        } finally { server.stop(0) }
    }

    @Test fun authenticatedHttpPublicationAndFeedContract() = runBlocking {
        val post = packet()
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            try {
                val path = exchange.requestURI.path
                calls += path
                if (!path.contains("session")) check(exchange.requestHeaders.getFirst("Authorization") == "Bearer session-token")
                val body = exchange.requestBody.readBytes()
                val response = when (path) {
                    "/v1/session/challenge" -> """{"nonce":"nonce","message":"challenge","expiresAt":9999999999}""".toByteArray()
                    "/v1/session/verify" -> {
                        val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                        verifySignature(wallet, "challenge".toByteArray(), unb64(request.string("signature")))
                        """{"token":"session-token","expiresAt":9999999999}""".toByteArray()
                    }
                    "/v1/posts" -> {
                        val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                        assertArrayEquals(post.blob, unb64(request.string("blob")))
                        assertArrayEquals(photos.front, PostPacket.open(post, wallet, "devnet", program).front)
                        """{"state":"authorized","commitment":"${post.commitment}","blobRef":"${post.blobRef}","transaction":"AQID"}""".toByteArray()
                    }
                    "/v1/posts/${post.commitment}/confirm" -> """{"state":"published"}""".toByteArray()
                    "/v1/feed" -> """{"items":[{"wallet":"${Base58.encode(wallet)}","commitment":"${post.commitment}","blobRef":"${post.blobRef}","postKey":"${b64(post.key)}"}],"nextCursor":"cursor+next"}""".toByteArray()
                    "/v1/blobs/${post.blobRef}" -> post.blob
                    else -> error("Unexpected route")
                }
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val api = KeyServer("http://127.0.0.1:${server.address.port}", true)
            val challenge = api.challenge(Base58.encode(wallet))
            val token = api.verify(Base58.encode(wallet), challenge.string("nonce"), sign(challenge.string("message").toByteArray())).string("token")
            assertArrayEquals(byteArrayOf(1, 2, 3), api.authorize(post, byteArrayOf(1), token))
            api.confirm(post.commitment, token)
            val page = api.feed(post.day, null, token)
            assertEquals("cursor+next", page.next)
            val blob = api.blob(page.items.single().blobRef, token)
            assertArrayEquals(photos.rear, PostPacket.open(post.copy(blob = blob), wallet, "devnet", program).rear)
            assertEquals(6, calls.size)
        } finally { server.stop(0) }
    }
}
