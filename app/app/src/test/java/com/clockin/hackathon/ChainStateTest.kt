package com.clockin.hackathon

import com.clockin.hackathon.chain.ConfigAccount
import com.clockin.hackathon.chain.ProfileAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ce que le profil affiche de la pool se déduit de la Config : rien n'est tenu à part. */
class ChainStateTest {
    private val today = 20_706L

    private fun config(rewardRateBps: Int = 100, rewardCap: Long = 10 * SKR) = ConfigAccount(
        admin = ByteArray(32), publicationAuthority = ByteArray(32), skrMint = ByteArray(32),
        vault = ByteArray(32), poolBalance = 290 * SKR, minStake = 500 * SKR,
        rewardCap = rewardCap, faucetAmount = 100 * SKR, withdrawalDelaySeconds = 172_800L,
        rewardRateBps = rewardRateBps, decayBps = 2_500, maxDecayDays = 30, faucetEnabled = true,
    )

    private fun profile(staked: Long) = ProfileAccount(
        owner = ByteArray(32), staked = staked, settledDay = today - 1, lastCheckInDay = today - 1,
        exitRequestedAt = 0, exitUnlockAt = 0, totalCheckIns = 4, streak = 4,
        active = true, faucetClaimed = true,
    )

    private fun state(staked: Long, rewardRateBps: Int = 100, rewardCap: Long = 10 * SKR) =
        ChainState(config = config(rewardRateBps, rewardCap), profile = profile(staked), day = today)

    @Test fun `the daily reward is a share of the settled stake`() {
        // 1 % de 500 SKR, sous le plafond de 10 SKR.
        assertEquals(5 * SKR, state(500 * SKR).dailyReward)
    }

    @Test fun `the reward cap wins over the rate`() {
        // 1 % de 5 000 SKR vaudrait 50 SKR : le plafond ramène à 10.
        assertEquals(10 * SKR, state(5_000 * SKR).dailyReward)
    }

    @Test fun `without a stake there is nothing to receive`() {
        assertEquals(0L, state(0).dailyReward)
    }

    @Test fun `an unread config promises nothing`() {
        assertEquals(0L, ChainState(profile = profile(500 * SKR), day = today).dailyReward)
    }
}
