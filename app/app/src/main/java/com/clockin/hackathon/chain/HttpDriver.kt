package com.clockin.hackathon.chain

import com.solana.networking.HttpNetworkDriver
import com.solana.networking.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Pilote HTTP sur la bibliothèque standard : aucune dépendance réseau
 * supplémentaire à auditer pour un composant qui voit passer des transactions
 * signées. Rien de ce qui transite ici n'est journalisé. */
class HttpUrlConnectionDriver(private val timeoutMs: Int = 20_000) : HttpNetworkDriver {
    override suspend fun makeHttpRequest(request: HttpRequest): String = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            request.properties.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
