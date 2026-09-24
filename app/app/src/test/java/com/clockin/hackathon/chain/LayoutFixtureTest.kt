package com.clockin.hackathon.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Octets écrits par le programme Rust (tests/test_layout.rs) : ce test tombe
 * si le décodeur Kotlin diverge de la disposition Borsh. */
class LayoutFixtureTest {
    private val lines = File("../../keyserver/tests/fixtures/account-layouts-v2.hex").readLines()
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun bytes(name: String) = lines.getValue(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decodes_config() {
        val config = ClockInAccounts.decodeConfig(bytes("config"))
        assertEquals(500_000_000_000L, config.minStake)
        assertEquals(1_000_000_000_000L, config.faucetAmount)
        assertEquals(172_800L, config.withdrawalDelaySeconds)
        assertEquals(21_600L, config.poolCloseDelaySeconds)
        assertEquals(1000, config.decayBps)
        assertEquals(30, config.maxDecayDays)
        assertTrue(config.faucetEnabled)
    }

    @Test fun decodes_profile_claims() {
        val profile = ClockInAccounts.decodeProfile(bytes("profile"))
        assertEquals(123_000_000_000L, profile.staked)
        assertEquals(listOf(20_717L, 20_718L), profile.pendingDays)
        assertEquals(listOf(100_000_000_000L, 110_000_000_000L), profile.pendingStakes)
    }

    @Test fun decodes_day_pool() {
        val pool = ClockInAccounts.decodeDayPool(bytes("day_pool"))
        assertEquals(DayPoolAccount(20_718, 30_000_000_000, 600_000_000_000, 6), pool)
    }
}
