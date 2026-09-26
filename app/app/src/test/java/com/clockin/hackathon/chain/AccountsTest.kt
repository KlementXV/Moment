package com.clockin.hackathon.chain

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
        .bytes(ByteArray(32) { 1 })     // owner
        .u64(staked)
        .i64(settledDay)
        .i64(lastCheckInDay)
        .i64(0L)                        // exit_requested_at
        .i64(exitUnlockAt)
        .u64(12L)                       // total_checkins
        .u32(streak)
        .bool(active)
        .bool(true)                     // faucet_claimed
        .u8(254)                        // bump
        .i64(-1L).i64(-1L)              // pending_days : emplacements libres
        .u64(0L).u64(0L)                // pending_stakes
        .build()

    @Test
    fun decodes_a_profile() {
        val profile = ClockInAccounts.decodeProfile(profileBytes())
        assertEquals(50_000_000_000L, profile.staked)
        assertEquals(20_705L, profile.settledDay)
        assertEquals(4L, profile.streak)
        assertEquals(12L, profile.totalCheckIns)
        assertTrue(profile.active)
        assertEquals(0L, profile.exitUnlockAt)
    }

    @Test
    fun a_profile_is_exactly_127_bytes() {
        // 8 discriminant + 32 owner + 8 staked + 4 × 8 dates + 8 total + 4 streak + 3 octets
        // + 2 × 8 jours de créance + 2 × 8 mises de créance
        assertEquals(127, profileBytes().size)
    }

    @Test
    fun a_closed_gain_is_credited_before_the_decay() {
        // Le programme encaisse d'abord : 40 + 10 SKR décimés une fois à 25 %.
        val profile = ClockInAccounts.decodeProfile(profileBytes(staked = 40_000_000_000L, settledDay = 20_705L))
        val displayed = profile.settledBalance(decayBps = 2500, maxDecayDays = 30, today = 20_707L, gain = 10_000_000_000L)
        assertEquals(37_500_000_000L, displayed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_an_account_with_the_wrong_discriminator() {
        val corrupted = profileBytes().copyOf()
        corrupted[0] = 0
        ClockInAccounts.decodeProfile(corrupted)
    }

    @Test
    fun reports_the_balance_after_the_decay_that_is_already_due() {
        // Deux jours entiers manqués : 50 SKR décimés deux fois à 25 %.
        val profile = ClockInAccounts.decodeProfile(profileBytes(settledDay = 20_705L))
        val displayed = profile.settledBalance(decayBps = 2500, maxDecayDays = 30, today = 20_708L)
        assertEquals(28_125_000_000L, displayed)
    }

    @Test
    fun a_pending_exit_caps_the_decay_shown_to_the_user() {
        val unlockAt = 20_707L * 86_400L + 43_200L
        val profile = ClockInAccounts.decodeProfile(
            profileBytes(settledDay = 20_705L, exitUnlockAt = unlockAt)
        )
        // Seul le jour 20 706 est entièrement terminé avant le déblocage.
        val displayed = profile.settledBalance(decayBps = 2500, maxDecayDays = 30, today = 20_710L)
        assertEquals(37_500_000_000L, displayed)
    }

    @Test
    fun a_closed_position_shows_nothing() {
        val profile = ClockInAccounts.decodeProfile(profileBytes(active = false))
        assertEquals(0L, profile.settledBalance(2500, 30, 20_710L))
    }

    @Test
    fun beyond_the_decay_limit_the_displayed_balance_is_zero() {
        val profile = ClockInAccounts.decodeProfile(profileBytes(settledDay = 20_600L))
        assertEquals(0L, profile.settledBalance(2500, 30, 20_708L))
    }

    // Octets réels des deux mints, relevés le 2026-09-27 : le décodeur doit
    // lire leurs décimales, sans supposer celles du mint de test.
    private val devnetTestMint = java.util.Base64.getDecoder().decode(
        "AQAAAATXPZC60rNhTOVePsss0spHYygFnmvJr+UgDNpBjqEMABCl1OgAAAAJAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    )
    private val mainnetSkrMint = java.util.Base64.getDecoder().decode(
        "AQAAANU721mLTlyETyBmNLVCQu0ZtyoLZVaIPavZk94uC5wwwOsnI9G4JQAGAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    )

    @Test
    fun reads_the_decimals_of_the_real_mints() {
        assertEquals(9, ClockInAccounts.decodeMintDecimals(devnetTestMint))
        assertEquals(6, ClockInAccounts.decodeMintDecimals(mainnetSkrMint))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_a_truncated_mint() {
        ClockInAccounts.decodeMintDecimals(mainnetSkrMint.copyOf(44))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuses_an_uninitialized_mint() {
        val uninitialized = mainnetSkrMint.copyOf().also { it[45] = 0 }
        ClockInAccounts.decodeMintDecimals(uninitialized)
    }
}

