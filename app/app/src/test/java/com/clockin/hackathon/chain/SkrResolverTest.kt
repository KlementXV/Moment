package com.clockin.hackathon.chain

import com.solana.networking.HttpNetworkDriver
import com.solana.networking.HttpRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class SkrResolverTest {
    private val owner = "11111111111111111111111111111111"
    private class Driver(val replies: MutableList<String>) : HttpNetworkDriver {
        val requests = mutableListOf<String>()
        override suspend fun makeHttpRequest(request: HttpRequest): String {
            requests += request.body.orEmpty()
            return """{"jsonrpc":"2.0","id":"1","result":${replies.removeAt(0)}}"""
        }
    }
    private fun account(data: ByteArray) = """{"owner":"${SkrResolver.PROGRAM}","data":["${Base64.getEncoder().encodeToString(data)}","base64"]}"""

    @Test fun root_derivation_matches_mainnet() = runBlocking {
        assertEquals(SkrResolver.ROOT, SkrResolver.nameAddress("ANS").base58())
    }

    @Test fun expiry_rejects_expired_and_truncated_records() {
        val data = ByteArray(200)
        assertTrue(SkrResolver.live(data, 100))
        ByteBuffer.wrap(data, 104, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(99)
        assertFalse(SkrResolver.live(data, 100))
        assertTrue(SkrResolver.live(data, 99))
        assertFalse(SkrResolver.live(ByteArray(110), 100))
    }

    @Test fun no_domain_is_a_success_without_followup_request() = runBlocking {
        val driver = Driver(mutableListOf("[]"))
        assertNull(SkrResolver("https://example.invalid", driver).resolve(owner))
        assertEquals(1, driver.requests.size)
        assertTrue(driver.requests.single().contains("\"offset\":40"))
        assertTrue(driver.requests.single().contains("\"offset\":8"))
    }

    @Test fun resolves_owned_name_and_checks_reverse_label() = runBlocking {
        val parent = SkrResolver.nameAddress(".skr", Base58.decode(SkrResolver.ROOT))
        val name = SkrResolver.nameAddress("alice", parent.bytes)
        val data = ByteArray(200)
        parent.bytes.copyInto(data, 8)
        Base58.decode(owner).copyInto(data, 40)
        val entries = """[{"pubkey":"${name.base58()}","account":${account(data)}}]"""
        val driver = Driver(mutableListOf(entries, """{"value":[${account(ByteArray(200) + "alice\u0000".toByteArray())}]}"""))
        assertEquals("alice.skr", SkrResolver("https://example.invalid", driver).resolve(owner))
        assertTrue(driver.requests.last().contains("getMultipleAccounts"))
        val bad = Driver(mutableListOf(entries, """{"value":[${account(ByteArray(200) + "mallory".toByteArray())}]}"""))
        assertNull(SkrResolver("https://example.invalid", bad).resolve(owner))
    }

    @Test(expected = IllegalStateException::class)
    fun rpc_failure_is_not_treated_as_no_domain() = runBlocking {
        val driver = object : HttpNetworkDriver {
            override suspend fun makeHttpRequest(request: HttpRequest) =
                """{"jsonrpc":"2.0","id":"1","error":{"code":-32005,"message":"Rate limited"}}"""
        }
        SkrResolver("https://example.invalid", driver).resolve(owner)
        Unit
    }
}
