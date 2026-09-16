package com.clockin.hackathon.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Validation croisée contre le déploiement devnet du 2026-09-16.
 *
 * Ces octets viennent du compte `Config` réel, écrit par le programme Rust.
 * Si le décodeur Kotlin et le programme divergent sur l'ordre ou la taille d'un
 * champ, ce test tombe — là où un test sur des octets fabriqués par le même
 * code ne verrait rien.
 *
 * Adresses dans docs/devnet-run.md. À rafraîchir si la configuration change.
 */
class DevnetConfigTest {
    private val data = Base64.getDecoder().decode(
        "mwyq4B76zIIDTVfMVPGFAbwO9YuMv2CnfOMcY1wvuKzZZb0+N6WiD+m8YSnjtAD69tq0NGmYZPpQEsS56yVHQbrT/JlRD8WJEXQfOQgydfjWuogt5eBmNybplJIgdcXVJ7tgQubtSwlbOTIM+fKYQMABzOx38cUa8LA0L8l36zAh2Ol29nLybgCIUmp0AAAAAOQLVAIAAAAAypo7AAAAAADodkgXAAAAAKMCAAAAAABkAMQJHgH/+g=="
    )

    @Test
    fun decodes_the_real_devnet_config_account() {
        val config = ClockInAccounts.decodeConfig(data)

        assertEquals("2B8dXN4cgPuhh5jFM4qrJfzWt718RVpZaP7oGzmuqNPS", Base58.encode(config.skrMint))
        assertEquals("796gheKx3knzDu3qEUPLjvjCnH3KF5VyBWZJi3jYV3oj", Base58.encode(config.vault))
        assertEquals(
            "GjQbefZGYQDtGpBxfLoXv8tyzTHnskCERWVgyegTbT6p",
            Base58.encode(config.publicationAuthority),
        )

        // Paramètres de travail de la feuille de route, tels qu'écrits on-chain.
        assertEquals(500_000_000_000L, config.poolBalance)
        assertEquals(10_000_000_000L, config.minStake)
        assertEquals(1_000_000_000L, config.rewardCap)
        assertEquals(100_000_000_000L, config.faucetAmount)
        assertEquals(172_800L, config.withdrawalDelaySeconds)
        assertEquals(100, config.rewardRateBps)
        assertEquals(2500, config.decayBps)
        assertEquals(30, config.maxDecayDays)
        assertTrue(config.faucetEnabled)
    }

    @Test
    fun the_account_layout_matches_the_program() {
        // 8 discriminant + 4 × 32 clés + 5 × 8 valeurs + 2 + 2 + 1 + 1 + 1 + 1
        assertEquals(184, data.size)
    }
}
