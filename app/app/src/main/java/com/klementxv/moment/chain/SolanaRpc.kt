package com.klementxv.moment.chain

import com.solana.networking.HttpNetworkDriver
import com.solana.networking.Rpc20Driver
import com.solana.publickey.SolanaPublicKey
import com.solana.rpccore.JsonRpc20Request
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.Base64
import java.util.UUID

@Serializable
private data class RpcContext(val slot: Long = 0)

@Serializable
private data class BlockhashValue(val blockhash: String)

@Serializable
private data class Wrapped<T>(val context: RpcContext = RpcContext(), val value: T? = null)

@Serializable
private data class AccountValue(val data: List<String> = emptyList(), val owner: String = "")

@Serializable
private data class ProgramAccountEntry(val pubkey: String, val account: AccountValue)

@Serializable
private data class SignatureStatusValue(val confirmationStatus: String? = null, val err: JsonElement? = null)

@Serializable
private data class TokenAmount(val amount: String = "0", val decimals: Int = 0)

class SolanaRpc(
    url: String,
    driver: HttpNetworkDriver = HttpUrlConnectionDriver(),
) {
    private val rpc = Rpc20Driver(url, driver)

    suspend fun latestBlockhash(): String =
        call<Wrapped<BlockhashValue>>("getLatestBlockhash", buildJsonArray {
            add(buildJsonObject { put("commitment", "confirmed") })
        }).value!!.blockhash

    suspend fun accountData(address: SolanaPublicKey, expectedOwner: SolanaPublicKey? = null): ByteArray? =
        call<Wrapped<AccountValue>>("getAccountInfo", buildJsonArray {
            add(address.base58())
            add(buildJsonObject {
                put("encoding", "base64")
                put("commitment", "confirmed")
            })
        }).value?.let {
            require(expectedOwner == null || it.owner == expectedOwner.base58()) { "Propriétaire du compte incorrect." }
            Base64.getDecoder().decode(it.data.first())
        }

    suspend fun checkInsForDay(programId: SolanaPublicKey, day: Long): List<CheckInAccount> =
        call<List<ProgramAccountEntry>>("getProgramAccounts", buildJsonArray {
            add(programId.base58())
            add(buildJsonObject {
                put("encoding", "base64")
                put("commitment", "confirmed")
                put("filters", buildJsonArray {
                    add(buildJsonObject { put("dataSize", MomentAccounts.CHECK_IN_SIZE.toLong()) })
                    add(buildJsonObject {
                        put("memcmp", buildJsonObject {
                            put("offset", 0)
                            put("bytes", Base58.encode(Anchor.accountDiscriminator("CheckIn")))
                        })
                    })
                    add(buildJsonObject {
                        put("memcmp", buildJsonObject {
                            put("offset", MomentAccounts.CHECK_IN_DAY_OFFSET)
                            put("bytes", Base58.encode(BorshWriter().i64(day).build()))
                        })
                    })
                })
            })
        }).map { MomentAccounts.decodeCheckIn(Base64.getDecoder().decode(it.account.data.first())) }

    suspend fun tokenBalance(address: SolanaPublicKey): Long = try {
        call<Wrapped<TokenAmount>>("getTokenAccountBalance", buildJsonArray {
            add(address.base58())
            add(buildJsonObject { put("commitment", "confirmed") })
        }).value?.amount?.toLong() ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    suspend fun sendTransaction(signed: ByteArray): String =
        call<String>("sendTransaction", buildJsonArray {
            add(Base64.getEncoder().encodeToString(signed))
            add(buildJsonObject {
                put("encoding", "base64")
                put("preflightCommitment", "confirmed")
            })
        })

    suspend fun awaitConfirmation(signature: String, timeoutMs: Long = 45_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val statuses = call<Wrapped<List<SignatureStatusValue?>>>(
                "getSignatureStatuses",
                buildJsonArray {
                    add(buildJsonArray { add(signature) })
                    add(buildJsonObject { put("searchTransactionHistory", true) })
                },
            ).value.orEmpty()
            val result = statuses.firstOrNull()
            check(result?.err == null || result.err == kotlinx.serialization.json.JsonNull) { "La transaction a échoué sur Solana." }
            val status = result?.confirmationStatus
            if (status == "confirmed" || status == "finalized") return true
            delay(1_500)
        }
        return false
    }

    private suspend inline fun <reified T> call(method: String, params: JsonElement): T {
        val request = JsonRpc20Request(method, params, UUID.randomUUID().toString())
        val response = rpc.makeRequest(request, serializer<T>())
        response.error?.let { error("RPC $method : ${it.message}") }
        return response.result ?: error("RPC $method : réponse vide")
    }
}
