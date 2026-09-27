package com.klementxv.moment.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.klementxv.moment.ChainState
import com.klementxv.moment.SKR
import com.klementxv.moment.chain.CheckInAccount
import com.klementxv.moment.chain.ConfigAccount
import com.klementxv.moment.chain.DayPoolAccount
import com.klementxv.moment.chain.ProfileAccount
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileScreenTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()

    private val today = 20_348L
    private val morning = today * DAY_SECONDS + 9 * 3600
    private val address = "7xKpWm9Rr4nT2vHc8LbYd5uJ3aFsq3Zd"

    private fun config() = ConfigAccount(
        admin = ByteArray(32), publicationAuthority = ByteArray(32), skrMint = ByteArray(32),
        vault = ByteArray(32), minStake = 500 * SKR, faucetAmount = 100 * SKR,
        withdrawalDelaySeconds = 172_800L, poolCloseDelaySeconds = 21_600L,
        decayBps = 2_500, maxDecayDays = 30, faucetEnabled = true,
    )

    private fun profile(staked: Long, exitUnlockAt: Long = 0, pendingDays: List<Long> = listOf(-1, -1),
                        pendingStakes: List<Long> = listOf(0, 0)) = ProfileAccount(
        owner = ByteArray(32), staked = staked, settledDay = today - 1, lastCheckInDay = today - 1,
        exitRequestedAt = if (exitUnlockAt > 0) morning else 0, exitUnlockAt = exitUnlockAt,
        totalCheckIns = 4, streak = 4, active = true, faucetClaimed = true,
        pendingDays = pendingDays, pendingStakes = pendingStakes,
    )

    private val todayPool = DayPoolAccount(today, penalties = 30 * SKR, totalStake = 1_000 * SKR, winnersCount = 2)

    private fun state(staked: Long, exitUnlockAt: Long = 0, posted: Boolean = false) = ChainState(
        config = config(), profile = profile(staked, exitUnlockAt),
        todayCheckIn = if (posted) CheckInAccount(ByteArray(32), today, ByteArray(32), ByteArray(32), 1, 4) else null,
        tokenBalance = 1_284 * SKR, day = today, loaded = true,
        pools = mapOf(today to todayPool), now = morning,
    )

    private fun screen(
        state: ChainState,
        wallet: String? = address,
        demoFeed: Boolean = false,
        favorites: Set<String> = emptySet(),
        onStake: (Long) -> Unit = {},
        onToggleFavorite: (String) -> Unit = {},
    ): @Composable () -> Unit = {
        Profile(state, morning, wallet, busy = false, error = null,
            nickname = "Camille Roy", skrName = null, skrLoading = false, skrFailed = false,
            favorites = favorites, onToggleFavorite = onToggleFavorite,
            onRetrySkr = {}, onNicknameChange = {}, onConnect = {}, onDisconnect = {},
            onFaucet = {}, onStake = onStake, onExit = {}, onCancel = {}, onWithdraw = {},
            demoFeed = demoFeed)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink,
                background = Ink, surface = Panel, onSurface = White)) {
                Surface(Modifier.fillMaxSize(), color = Ink, contentColor = White) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    ) { content() }
                }
            }
        }
    }

    @Test fun a_stake_above_the_minimum_is_in_order_and_offers_the_exit() {
        show(screen(state(500 * SKR)))
        compose.onNodeWithText("En règle").assertExists()
        compose.onNodeWithText("Demander le retrait").assertExists()
    }

    @Test fun a_stake_below_the_minimum_says_so() {
        show(screen(state(100 * SKR)))
        compose.onNodeWithText("Sous le minimum").assertExists()
        compose.onNodeWithText("Il te faut 500\u00A0SKR en staking pour publier. Complète ton staking.",
            substring = true).assertExists()
    }

    @Test fun an_exit_in_flight_replaces_the_card_action() {
        show(screen(state(500 * SKR, exitUnlockAt = morning + 172_800L)))
        compose.onAllNodesWithText("Sortie en cours").assertCountEquals(2)
        compose.onAllNodesWithText("Annuler la sortie").assertCountEquals(2)
        compose.onNodeWithText("Demander le retrait").assertDoesNotExist()
    }

    @Test fun the_pool_shows_the_chain_total_and_the_share_it_promises() {
        show(screen(state(500 * SKR)))
        compose.onNodeWithText("30").assertExists()
        compose.onNodeWithText("Ta part si tu publies").assertExists()
        compose.onNodeWithText("10 SKR").assertExists()
    }

    @Test fun an_open_claim_shows_the_pending_gain_and_no_immediate_reward() {
        val yesterday = DayPoolAccount(today - 1, penalties = 30 * SKR, totalStake = 1_500 * SKR, winnersCount = 3)
        val pending = state(500 * SKR).copy(
            profile = profile(500 * SKR, pendingDays = listOf(today - 1, -1), pendingStakes = listOf(500 * SKR, 0)),
            pools = mapOf(today - 1 to yesterday, today to todayPool),
            now = today * DAY_SECONDS + 3 * 3600,
        )
        show(screen(pending))
        compose.onNodeWithText("+10\u00A0SKR en attente").assertExists()
        compose.onNodeWithText("Versé à", substring = true).assertExists()
        compose.onAllNodesWithText("jusqu’à", substring = true).assertCountEquals(0)
    }

    @Test fun without_the_demo_the_pool_shows_no_invented_count() {
        show(screen(state(500 * SKR), demoFeed = false))
        compose.onNodeWithText("5 gagnants · 5 sans moment").assertDoesNotExist()
        compose.onAllNodesWithText("Démo").assertCountEquals(0)
    }

    @Test fun the_invented_pool_count_appears_only_with_the_demo_and_carries_its_badge() {
        show(screen(state(500 * SKR), demoFeed = true))
        compose.onNodeWithText("5 gagnants · 5 sans moment").assertExists()
        compose.onAllNodesWithText("Démo").assertCountEquals(1)
    }

    @Test fun the_sections_without_a_source_explain_themselves() {
        show(screen(state(500 * SKR)))
        compose.onNodeWithText("Favoris").performScrollTo().assertExists()
        compose.onNodeWithText("Personne pour l’instant", substring = true).assertExists()
        compose.onNodeWithText("Activité").performScrollTo().assertExists()
        compose.onNodeWithText("Rien n’est encore affiché ici", substring = true).assertExists()
    }

    @Test fun any_amount_can_be_typed_rather_than_only_the_presets() {
        var staked: Long? = null
        show(screen(state(500 * SKR), onStake = { staked = it }))
        compose.onNodeWithText("Ajouter des SKR").performScrollTo().performClick()
        val field = compose.onNodeWithContentDescription("Montant en SKR")
        field.performScrollTo().performTextClearance()
        field.performTextInput("137,5")
        compose.onNodeWithText("Staker 137,50\u00A0SKR").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(137 * SKR + SKR / 2, staked) }
    }

    @Test fun an_amount_above_the_wallet_balance_is_capped_as_it_is_typed() {
        var staked: Long? = null
        show(screen(state(500 * SKR), onStake = { staked = it }))
        compose.onNodeWithText("Ajouter des SKR").performScrollTo().performClick()
        val field = compose.onNodeWithContentDescription("Montant en SKR")
        field.performScrollTo().performTextClearance()
        field.performTextInput("99999")
        compose.onNodeWithText("Staker 1284\u00A0SKR").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1_284 * SKR, staked) }
    }

    @Test fun the_wallet_balance_is_no_longer_repeated_at_the_top() {
        show(screen(state(500 * SKR)))
        compose.onNodeWithText("Solde").assertDoesNotExist()
        compose.onNodeWithText("Ajouter des SKR").performScrollTo().performClick()
        compose.onNodeWithText("1284\u00A0SKR disponibles sur ton wallet.",
            substring = true).performScrollTo().assertExists()
    }

    @Test fun the_rules_and_the_language_open_in_popups() {
        show(screen(state(500 * SKR)))
        compose.onNodeWithText("Comment ça marche").assertDoesNotExist()
        compose.onNodeWithText("Règles du jeu").performScrollTo().performClick()
        compose.onNodeWithText("Comment ça marche").assertExists()
        compose.onNodeWithText("Fermer").performClick()
        compose.onNodeWithText("Changer la langue").performScrollTo().performClick()
        compose.onNodeWithText("Langue").assertExists()
    }

    @Test fun a_favourite_can_be_removed_from_the_profile() {
        var removed: String? = null
        show(screen(state(500 * SKR), favorites = setOf("Léa Martin")) { removed = it })
        compose.onNodeWithContentDescription("Retirer Léa Martin des favoris")
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Léa Martin", removed) }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun screenshot_in_order() {
        show(screen(state(500 * SKR), demoFeed = true, favorites = setOf("Léa Martin", "Inès Caron")))
        shoot("profil-01-en-regle")
    }

    @Test fun screenshot_below_the_minimum() {
        show(screen(state(100 * SKR)))
        shoot("profil-02-sous-le-minimum")
    }

    @Test fun screenshot_exit_in_flight() {
        show(screen(state(500 * SKR, exitUnlockAt = morning + 172_800L)))
        shoot("profil-03-sortie")
    }
}
