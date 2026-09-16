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
    fun a_profile_is_exactly_ninety_five_bytes() {
        // 8 discriminant + 32 owner + 8 staked + 4 × 8 dates + 8 total + 4 streak + 3 octets
        assertEquals(95, profileBytes().size)
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
}
