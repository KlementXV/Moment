package com.clockin.hackathon.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clockin.hackathon.ChainState
import com.clockin.hackathon.SKR
import com.clockin.hackathon.chain.ConfigAccount
import com.clockin.hackathon.chain.ProfileAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()

    /** Midi UTC d'un jour fixe : le parcours ne doit pas dépendre de l'heure du test. */
    private val NOON = 20_348L * 86_400L + 12 * 3600L

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Amène le parcours jusqu'à l'écran des conditions, la dernière étape sans engagement. */
    private fun walkToConditions() {
        compose.onNodeWithText("Commencer").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choisis ta langue").assertIsDisplayed()
        compose.onNodeWithText("Continuer").performClick()
        compose.onNodeWithText("Un moment par jour").assertIsDisplayed()
        compose.onNodeWithText("Continuer").performClick()
        compose.onNodeWithText("Un seul cercle").assertIsDisplayed()
        compose.onNodeWithText("Continuer").performClick()
        compose.onNodeWithText("Joue avec un staking de SKR").assertIsDisplayed()
        compose.onNodeWithText("Continuer").performClick()
        compose.onNodeWithText("Publie, reçois ta part").assertIsDisplayed()
        compose.onNodeWithText("Continuer").performClick()
        compose.onNodeWithText("Avant de jouer").assertIsDisplayed()
    }

    @Test fun the_pitch_comes_before_anything_is_engaged() {
        val wallet = mutableStateOf<String?>(null)
        var connections = 0
        var finished = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, onSurface = White)) {
                Surface {
                    Onboarding(wallet.value, ChainState(loaded = true), NOON, false, null,
                        resumeAtStake = false, onConnect = { connections++ }, onStake = {},
                        onFaucet = {}, onDisconnect = {}, onFinish = { finished = true })
                }
            }
        }
        screenshot("00-splash")
        walkToConditions()
        screenshot("01-conditions")
        // Rien n'a été demandé au wallet tant que les conditions ne sont pas acceptées.
        compose.runOnIdle { assertEquals(0, connections); assertFalse(finished) }
    }

    @Test fun the_wallet_is_out_of_reach_until_the_terms_are_accepted() {
        var connections = 0
        compose.setContent {
            MaterialTheme {
                Onboarding(null, ChainState(loaded = true), NOON, false, null,
                    resumeAtStake = false, onConnect = { connections++ }, onStake = {},
                    onFaucet = {}, onDisconnect = {}, onFinish = {})
            }
        }
        walkToConditions()
        compose.onNodeWithText("Continuer").assertIsNotEnabled()
        compose.onNodeWithText("J’ai lu et j’accepte les conditions d’utilisation et la politique de confidentialité.")
            .performClick()
        compose.onNodeWithText("Continuer").assertIsEnabled().performClick()
        compose.onNodeWithText("Connecte ton wallet").assertIsDisplayed()
        compose.onNodeWithText("Connecter mon wallet").performClick()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    // ── L'écran 7, la mise ─────────────────────────────────────────────────

    /** Un état de chaîne avec une mise déjà prise de [staked], minimum à 500 SKR. */
    private fun stakedState(staked: Long) = ChainState(
        config = ConfigAccount(ByteArray(32), ByteArray(32), ByteArray(32), ByteArray(32),
            minStake = 500 * SKR, faucetAmount = 0, withdrawalDelaySeconds = 0,
            poolCloseDelaySeconds = 21_600, decayBps = 2_500,
            maxDecayDays = 4, faucetEnabled = true),
        profile = ProfileAccount(ByteArray(32), staked = staked, settledDay = NOON / 86_400,
            lastCheckInDay = NOON / 86_400, exitRequestedAt = 0, exitUnlockAt = 0,
            totalCheckIns = 1, streak = 1, active = true, faucetClaimed = true),
        day = NOON / 86_400,
        loaded = true,
    )

    private fun stakeScreen(state: ChainState) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, onSurface = White)) {
                Surface {
                    Onboarding("wallet", state, NOON, false, null,
                        resumeAtStake = true, onConnect = {}, onStake = {},
                        onFaucet = {}, onDisconnect = {}, onFinish = {})
                }
            }
        }
    }

    /** Mise déjà suffisante : un seul geste suffit pour quitter l'écran. */
    @Test fun a_stake_above_the_minimum_lets_the_seventh_screen_pass_in_one_tap() {
        stakeScreen(stakedState(500 * SKR))
        compose.onNodeWithText("Stake tes SKR").assertIsDisplayed()
        // Rien à choisir : le sélecteur reste replié derrière « Ajouter ».
        compose.onNodeWithText("Staker ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ajouter à mon staking").assertIsDisplayed()
        screenshot("07-mise-couverte")
        compose.onNodeWithText("Continuer").assertIsEnabled().performClick()
        compose.onNodeWithText("Deux autorisations").assertIsDisplayed()
    }

    /** Mise tombée sous le seuil : passer n'est plus possible, il faut compléter. */
    @Test fun a_stake_below_the_minimum_still_has_to_be_topped_up() {
        stakeScreen(stakedState(100 * SKR))
        compose.onNodeWithText("Continuer").assertDoesNotExist()
        compose.onNodeWithText("Staker ", substring = true).assertIsDisplayed()
    }
}
