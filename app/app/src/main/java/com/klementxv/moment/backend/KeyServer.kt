package com.klementxv.moment.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class BackendException(val status: Int, message: String) : Exception(message)
data class FeedItem(val wallet: String, val commitment: String, val blobRef: String, val postKey: String, val likes: Int = 0, val liked: Boolean = false)
data class FeedPage(val items: List<FeedItem>, val next: String?)
fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

class KeyServer(baseUrl: String, allowLocalHttp: Boolean = false) {
    val origin: String
    init {
        val uri = URI(baseUrl)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) {
            "Le service Moment est indisponible. Réessayez plus tard."
        }
        require(uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1", "10.0.2.2")))
        val port = if (uri.port == -1 || (uri.scheme == "https" && uri.port == 443) || (uri.scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
        origin = "${uri.scheme}://${uri.host.lowercase()}$port"
    }
    private suspend fun request(path: String, token: String?, body: JsonObject? = null, method: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val connection = URI(origin + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.requestMethod = method ?: if (body == null) "GET" else "POST"
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { stream -> stream.write(it.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= PostPacket.MAX_BLOB)
                    output.write(buffer, 0, n)
                }
                output.toByteArray() } ?: byteArrayOf()
            require(bytes.size <= PostPacket.MAX_BLOB)
            if (status !in 200..299) throw BackendException(status, when (status) {
                401 -> "Session expirée. Réessayez pour signer une nouvelle connexion."
                403 -> "Accès refusé. Vérifiez votre publication et votre staking du jour."
                409 -> "Une publication existe déjà pour ce jour. Reprenez sa confirmation."
                422 -> "Le serveur a refusé les photos ou la publication."
                429 -> "Trop de demandes. Réessayez dans une minute."
                else -> "Le serveur est indisponible ou a refusé la demande ($status)."
            })
            bytes
        } finally { connection.disconnect() }
    }
    private suspend fun json(path: String, token: String? = null, body: JsonObject? = null): JsonObject =
        Json.parseToJsonElement(request(path, token, body).toString(Charsets.UTF_8)).jsonObject
    suspend fun challenge(wallet: String) = json("/v1/session/challenge", body = buildJsonObject { put("wallet", wallet) })
    suspend fun verify(wallet: String, nonce: String, signature: ByteArray) = json("/v1/session/verify", body = buildJsonObject {
        put("wallet", wallet); put("nonce", nonce); put("signature", b64(signature))
    })
    suspend fun authorize(post: SealedPost, transaction: ByteArray, token: String): ByteArray {
        val result = json("/v1/posts", token, buildJsonObject {
            put("day", post.day); put("commitment", post.commitment); put("blobRef", post.blobRef)
            put("postKey", b64(post.key)); put("blob", b64(post.blob)); put("transaction", b64(transaction))
        })
        require(result.string("state") == "authorized" && result.string("commitment") == post.commitment && result.string("blobRef") == post.blobRef)
        return unb64(result.string("transaction"))
    }
    suspend fun confirm(commitment: String, token: String) {
        require(commitment.matches(Regex("[0-9a-f]{64}")))
        require(json("/v1/posts/$commitment/confirm", token, buildJsonObject {}).string("state") == "published")
    }
    suspend fun feed(day: Long, after: String?, token: String): FeedPage {
        val query = after?.let { "&after=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val result = json("/v1/feed?day=$day&limit=10$query", token)
        return FeedPage(result.getValue("items").jsonArray.map { item ->
            val o = item.jsonObject
            FeedItem(o.string("wallet"), o.string("commitment"), o.string("blobRef"), o.string("postKey"),
                o["likes"]?.jsonPrimitive?.content?.toInt() ?: 0, o["liked"]?.jsonPrimitive?.content == "true")
        }, result["nextCursor"]?.jsonPrimitive?.contentOrNull)
    }
    suspend fun like(commitment: String, liked: Boolean, token: String): Pair<Int, Boolean> {
        require(commitment.matches(Regex("[0-9a-f]{64}")))
        val result = Json.parseToJsonElement(request("/v1/posts/$commitment/like", token,
            method = if (liked) "PUT" else "DELETE").toString(Charsets.UTF_8)).jsonObject
        return result.string("likes").toInt() to (result.string("liked") == "true")
    }
    suspend fun blob(ref: String, token: String): ByteArray {
        require(ref.matches(Regex("[0-9a-f]{64}")))
        return request("/v1/blobs/$ref", token)
    }
}
fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

fun challengeMessage(challenge: JsonObject, origin: String, network: String, program: String, wallet: String, now: Long): ByteArray {
    val expires = challenge.string("expiresAt").toLong()
    require(expires > now && expires <= now + 330)
    val nonce = challenge.string("nonce")
    require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
    val expected = "Moment — connexion au serveur de clés\norigine: $origin\nréseau: $network\nprogramme: $program\nwallet: $wallet\nnonce: $nonce\nexpire: $expires\nCette signature ouvre une session de 15 minutes et n’autorise aucun transfert."
    require(challenge.string("message") == expected) { "Challenge du serveur incompatible." }
    return expected.toByteArray(Charsets.UTF_8)
}
