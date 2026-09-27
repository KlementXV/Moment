package com.klementxv.moment.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private fun profileBytes(
        staked: Long = 50_000_000_000L,
        settledDay: Long = 20_705L,
        lastCheckInDay: Long = 20_705L,
        exitUnlockAt: Long = 0L,
        streak: Long = 4L,
        active: Boolean = true,
    ): ByteArray = BorshWriter()
        .bytes(Anchor.accountDiscriminator("Profile"))
        .bytes(ByteArray(32) { 1 })
        .u64(staked)
        .i64(settledDay)
        .i64(lastCheckInDay)
        .i64(0L)
        .i64(exitUnlockAt)
        .u64(12L)
        .u32(streak)
        .bool(active)
        .bool(true)
        .u8(254)
        .i64(-1L).i64(-1L)
        .u64(0L).u64(0L)
        .build()

    @Test
    fun decodes_a_profile() {
        val profile = MomentAccounts.decodeProfile(profileBytes())
        assertEquals(50_000_000_000L, profile.staked)
        assertEquals(20_705L, profile.settledDay)
        assertEquals(4L, profile.streak)
        assertEquals(12L, profile.totalCheckIns)
        assertTrue(profile.active)
        assertEquals(0L, profile.exitUnlockAt)
    }

    @Test
    fun a_profile_is_exactly_127_bytes() {
        assertEquals(127, profileBytes().size)
    }

    @Test
    fun a_closed_gain_is_credited_before_the_decay() {
        val profile = MomentAccounts.decodeProfile(profileBytes(staked = 40_000_000_000L, settledDay = 20_705L))
        val displayed = profile.settledBalance(decayBps = 2500, maxDecayDays = 30, today = 20_707L, gain = 10_000_000_000L)
        assertEquals(37_500_000_000L, displayed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_an_account_with_the_wrong_discriminator() {
        val corrupted = profileBytes().copyOf()
        corrupted[0] = 0
        MomentAccounts.decodeProfile(corrupted)
    }

    @Test
    fun reports_the_balance_after_the_decay_that_is_already_due() {
        val profile = MomentAccounts.decodeProfile(profileBytes(settledDay = 20_705L))
        val displayed = profile.settledBalance(decayBps = 2500, maxDecayDays = 30, today = 20_708L)
        assertEquals(28_125_000_000L, displayed)
    }

    @Test
    fun a_pending_exit_caps_the_decay_shown_to_the_user() {
        val unlockAt = 20_707L * 86_400L + 43_200L
        val profile = MomentAccounts.decodeProfile(
            profileBytes(settledDay = 20_705L, exitUnlockAt = unlockAt)
        )
        val displayed = profile.settledBalance(decayBps = 2500, maxDecayDays = 30, today = 20_710L)
        assertEquals(37_500_000_000L, displayed)
    }

    @Test
    fun a_closed_position_shows_nothing() {
        val profile = MomentAccounts.decodeProfile(profileBytes(active = false))
        assertEquals(0L, profile.settledBalance(2500, 30, 20_710L))
    }

    @Test
    fun beyond_the_decay_limit_the_displayed_balance_is_zero() {
        val profile = MomentAccounts.decodeProfile(profileBytes(settledDay = 20_600L))
        assertEquals(0L, profile.settledBalance(2500, 30, 20_708L))
    }

    private val devnetTestMint = java.util.Base64.getDecoder().decode(
        "AQAAAATXPZC60rNhTOVePsss0spHYygFnmvJr+UgDNpBjqEMABCl1OgAAAAJAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    )
    private val mainnetSkrMint = java.util.Base64.getDecoder().decode(
        "AQAAANU721mLTlyETyBmNLVCQu0ZtyoLZVaIPavZk94uC5wwwOsnI9G4JQAGAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    )

    @Test
    fun reads_the_decimals_of_the_real_mints() {
        assertEquals(9, MomentAccounts.decodeMintDecimals(devnetTestMint))
        assertEquals(6, MomentAccounts.decodeMintDecimals(mainnetSkrMint))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_a_truncated_mint() {
        MomentAccounts.decodeMintDecimals(mainnetSkrMint.copyOf(44))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_an_uninitialized_mint() {
        val uninitialized = mainnetSkrMint.copyOf().also { it[45] = 0 }
        MomentAccounts.decodeMintDecimals(uninitialized)
    }
}

