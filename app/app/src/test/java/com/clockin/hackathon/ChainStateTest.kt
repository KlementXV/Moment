package com.clockin.hackathon

import com.clockin.hackathon.chain.ConfigAccount
import com.clockin.hackathon.chain.DayPoolAccount
import com.clockin.hackathon.chain.ProfileAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ce que le profil affiche du pool journalier se déduit des comptes lus sur la chaîne. */
class ChainStateTest {
    private val today = 20_706L
    private val delay = 21_600L
    private val morning = today * 86_400 + 3 * 3_600   // 03:00, pool d'hier ouvert
    private val noon = today * 86_400 + 12 * 3_600     // 12:00, pool d'hier clôturé

    private fun config() = ConfigAccount(
        admin = ByteArray(32), publicationAuthority = ByteArray(32), skrMint = ByteArray(32),
        vault = ByteArray(32), minStake = 500 * SKR, faucetAmount = 100 * SKR,
        withdrawalDelaySeconds = 172_800L, poolCloseDelaySeconds = delay,
        decayBps = 1_000, maxDecayDays = 30, faucetEnabled = true,
    )

    private fun profile(pendingDays: List<Long>, pendingStakes: List<Long>) = ProfileAccount(
        owner = ByteArray(32), staked = 500 * SKR, settledDay = today - 1, lastCheckInDay = today - 1,
        exitRequestedAt = 0, exitUnlockAt = 0, totalCheckIns = 4, streak = 4,
        active = true, faucetClaimed = true, pendingDays = pendingDays, pendingStakes = pendingStakes,
    )

    private val yesterdayPool = DayPoolAccount(today - 1, penalties = 30 * SKR, totalStake = 1_500 * SKR, winnersCount = 3)

    private fun state(now: Long, pools: Map<Long, DayPoolAccount> = mapOf(today - 1 to yesterdayPool)) = ChainState(
        config = config(), profile = profile(listOf(today - 1, -1), listOf(500 * SKR, 0)),
        day = today, pools = pools, now = now,
    )

    @Test fun `an open claim is pending, not in the balance`() {
        val s = state(morning)
        assertEquals(10 * SKR, s.pendingGain)   // 30 × 500 / 1 500
        assertEquals(500 * SKR, s.balance)
        assertEquals((today) * 86_400 + delay, s.payoutAt)
    }

    @Test fun `a closed claim lands in the balance`() {
        val s = state(noon)
        assertEquals(0L, s.pendingGain)
        assertEquals(510 * SKR, s.balance)
        assertEquals(null, s.payoutAt)
    }

    @Test fun `a missing pool promises nothing`() {
        assertEquals(0L, state(morning, pools = emptyMap()).pendingGain)
    }

    @Test fun `today's share if you post includes your own stake in the total`() {
        val pools = mapOf(today to DayPoolAccount(today, penalties = 10 * SKR, totalStake = 500 * SKR, winnersCount = 1))
        val s = ChainState(config = config(), profile = profile(listOf(-1, -1), listOf(0, 0)), day = today, pools = pools, now = noon)
        assertEquals(10 * SKR, s.todayPoolTotal)
        assertEquals(5 * SKR, s.myShareToday)  // 10 × 500 / (500 + 500)
    }

    @Test fun `no stake is sent before the mint decimals are read`() {
        // Un montant saisi dans la mauvaise unité enverrait 1 000 fois trop sur
        // mainnet (6 décimales contre 9) : la mise attend la lecture du mint.
        assertEquals(false, state(noon).copy(skrDecimals = null).canStake)
        assertEquals(true, state(noon).copy(skrDecimals = 6).canStake)
    }
}

