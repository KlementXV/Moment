package com.klementxv.moment.chain

import com.solana.networking.HttpNetworkDriver
import com.solana.networking.Rpc20Driver
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.rpccore.JsonRpc20Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Serializable
private data class SkrAccount(val owner: String, val data: List<String>)
@Serializable
private data class SkrEntry(val pubkey: String, val account: SkrAccount)
@Serializable
private data class SkrBatch(val value: List<SkrAccount?>)

class SkrResolver(url: String, driver: HttpNetworkDriver = HttpUrlConnectionDriver(8_000)) {
    private val rpc = Rpc20Driver(url, driver)

    suspend fun resolve(owner: String, now: Long = Instant.now().epochSecond): String? {
        require(Base58.decode(owner).size == 32)
        val parent = nameAddress(".skr", key(ROOT).bytes)
        val house = ProgramDerivedAddress.find(
            listOf("tld_house".toByteArray(), ".skr".toByteArray()), key(HOUSE_PROGRAM),
        ).getOrThrow()
        val accounts = call<List<SkrEntry>>("getProgramAccounts", buildJsonArray {
            add(PROGRAM)
            add(buildJsonObject {
                put("encoding", "base64")
                put("commitment", "confirmed")
                put("filters", buildJsonArray {
                    for ((offset, value) in listOf(8 to parent.base58(), 40 to owner)) {
                        add(buildJsonObject { put("memcmp", buildJsonObject {
                            put("offset", offset); put("bytes", value)
                        }) })
                    }
                })
            })
        }).filter { entry ->
            val data = decode(entry.account)
            entry.account.owner == PROGRAM && data.size >= 200 &&
                data.copyOfRange(8, 40).contentEquals(parent.bytes) &&
                data.copyOfRange(40, 72).contentEquals(Base58.decode(owner)) && live(data, now)
        }.sortedBy { it.pubkey }
        val names = mutableListOf<String>()
        for (batch in accounts.chunked(100)) {
            val reverse = batch.map { entry ->
                ProgramDerivedAddress.find(listOf(hash(entry.pubkey), house.bytes, ByteArray(32)), key(PROGRAM)).getOrThrow()
            }
            val records = call<SkrBatch>("getMultipleAccounts", buildJsonArray {
                add(buildJsonArray { reverse.forEach { add(it.base58()) } })
                add(buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") })
            }).value
            check(records.size == batch.size) { "Incomplete name lookup" }
            records.forEachIndexed { index, record ->
                if (record != null && record.owner == PROGRAM) {
                    val data = decode(record)
                    if (data.size > 200) {
                        val label = data.copyOfRange(200, data.size).toString(Charsets.UTF_8).substringBefore('\u0000')
                        if (label.matches(Regex("[a-z0-9-]{1,63}")) &&
                            nameAddress(label, parent.bytes).base58() == batch[index].pubkey) names += "$label.skr"
                    }
                }
            }
        }
        return names.minOrNull()
    }

    private suspend inline fun <reified T> call(method: String, params: JsonElement): T {
        val result = rpc.makeRequest(JsonRpc20Request(method, params, UUID.randomUUID().toString()), serializer<T>())
        check(result.error == null) { "Name lookup unavailable" }
        return result.result ?: error("Empty name lookup response")
    }

    companion object {
        internal const val PROGRAM = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK"
        internal const val ROOT = "3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU"
        private const val HOUSE_PROGRAM = "TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S"
        private fun key(value: String) = SolanaPublicKey(Base58.decode(value))
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(("ALT Name Service" + value).toByteArray())
        internal suspend fun nameAddress(label: String, parent: ByteArray = ByteArray(32)): SolanaPublicKey =
            ProgramDerivedAddress.find(listOf(hash(label), ByteArray(32), parent), key(PROGRAM)).getOrThrow()
        private fun decode(account: SkrAccount): ByteArray {
            check(account.data.getOrNull(1) == "base64")
            return Base64.getDecoder().decode(account.data.first())
        }
        internal fun live(data: ByteArray, now: Long): Boolean {
            if (data.size < 200) return false
            val expiry = ByteBuffer.wrap(data, 104, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            return expiry == 0uL || expiry >= now.toULong()
        }
    }
}
