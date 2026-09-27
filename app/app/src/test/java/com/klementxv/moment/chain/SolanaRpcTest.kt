package com.klementxv.moment.chain

import com.solana.networking.HttpNetworkDriver
import com.solana.networking.HttpRequest
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private class FakeDriver(private val responses: MutableList<String>) : HttpNetworkDriver {
    val bodies = mutableListOf<String>()
    override suspend fun makeHttpRequest(request: HttpRequest): String {
        bodies += request.body ?: ""
        return responses.removeAt(0)
    }
}

class SolanaRpcTest {
    private val programId = SolanaPublicKey(Base58.decode("7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1"))

    @Test(expected = IllegalStateException::class)
    fun confirmed_failed_transaction_is_not_successful() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","result":{"context":{"slot":42},"value":[{"confirmationStatus":"confirmed","err":{"InstructionError":[0,"Custom"]}}]}}"""
        ))
        SolanaRpc("https://example.invalid", driver).awaitConfirmation("signature")
        Unit
    }

    @Test
    fun confirmed_successful_transaction_finishes() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","result":{"context":{"slot":42},"value":[{"confirmationStatus":"confirmed","err":null}]}}"""
        ))
        assertTrue(SolanaRpc("https://example.invalid", driver).awaitConfirmation("signature"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrong_account_program_is_rejected() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","result":{"value":{"owner":"11111111111111111111111111111111","data":["AQ==","base64"]}}}"""
        ))
        SolanaRpc("https://example.invalid", driver).accountData(programId, programId)
        Unit
    }

    @Test
    fun reads_the_latest_blockhash() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","result":{"context":{"slot":42},"value":{"blockhash":"EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k","lastValidBlockHeight":9}}}"""
        ))
        val rpc = SolanaRpc("https://example.invalid", driver)

        assertEquals("EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k", rpc.latestBlockhash())
        assertTrue(driver.bodies.single().contains("getLatestBlockhash"))
    }

    @Test
    fun returns_null_for_an_account_that_does_not_exist_yet() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","result":{"context":{"slot":42},"value":null}}"""
        ))
        assertNull(SolanaRpc("https://example.invalid", driver).accountData(programId))
    }

    @Test
    fun decodes_base64_account_data() = runBlocking {
        val payload = BorshWriter()
            .bytes(Anchor.accountDiscriminator("CheckIn"))
            .bytes(ByteArray(32) { 1 })
            .i64(20_706L)
            .bytes(ByteArray(32) { 2 })
            .bytes(ByteArray(32) { 3 })
            .u64(1234L)
            .u32(5L)
            .build()
        val encoded = Base64.getEncoder().encodeToString(payload)
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","result":{"context":{"slot":42},"value":{"data":["$encoded","base64"],"executable":false,"lamports":100,"owner":"11111111111111111111111111111111","rentEpoch":0}}}"""
        ))

        val data = SolanaRpc("https://example.invalid", driver).accountData(programId)!!
        val checkIn = MomentAccounts.decodeCheckIn(data)
        assertEquals(20_706L, checkIn.day)
        assertEquals(1234L, checkIn.slot)
        assertEquals(5L, checkIn.streakAtCheckIn)
    }

    @Test
    fun filters_the_feed_on_the_discriminator_and_the_day() = runBlocking {
        val driver = FakeDriver(mutableListOf("""{"jsonrpc":"2.0","id":"1","result":[]}"""))

        SolanaRpc("https://example.invalid", driver).checkInsForDay(programId, 20_706L)

        val body = driver.bodies.single()
        assertTrue(body.contains("getProgramAccounts"))
        assertTrue(body.contains(Base58.encode(Anchor.accountDiscriminator("CheckIn"))))
        assertTrue(body.contains(Base58.encode(BorshWriter().i64(20_706L).build())))
        assertTrue(body.contains("\"offset\":40"))
        assertTrue(body.contains("\"dataSize\":124"))
    }

    @Test(expected = IllegalStateException::class)
    fun surfaces_an_rpc_error_instead_of_returning_an_empty_result() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","error":{"code":-32602,"message":"Invalid params"}}"""
        ))
        SolanaRpc("https://example.invalid", driver).latestBlockhash()
        Unit
    }

    @Test
    fun an_absent_token_account_reads_as_zero() = runBlocking {
        val driver = FakeDriver(mutableListOf(
            """{"jsonrpc":"2.0","id":"1","error":{"code":-32602,"message":"could not find account"}}"""
        ))
        assertEquals(0L, SolanaRpc("https://example.invalid", driver).tokenBalance(programId))
    }
}
