package com.klementxv.moment.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.klementxv.moment.ChainState
import com.klementxv.moment.i18n.AppLanguage
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LanguageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previous: String? = null

    @Before fun setUp() {
        previous = AppLanguage.selection
        AppLanguage.select("fr")
    }
    @After fun tearDown() { AppLanguage.select(previous) }

    @Test fun onboarding_switches_immediately_and_keeps_its_current_step() {
        compose.setContent {
            MaterialTheme {
                Onboarding(null, ChainState(loaded = true), 1_800_000_000L, false, null,
                    resumeAtStake = false, onConnect = {}, onStake = {}, onFaucet = {},
                    onDisconnect = {}, onFinish = {})
            }
        }
        compose.onNodeWithText("Commencer").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Choose your language").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("One moment a day").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Français").performClick()
        compose.onNodeWithText("Choisis ta langue").assertIsDisplayed()
    }

    @Test fun profile_switches_language_after_onboarding() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Profile(ChainState(loaded = true), 1_800_000_000L, null, false, null,
                        nickname = "", skrName = null, skrLoading = false, skrFailed = false,
                        favorites = emptySet(), onToggleFavorite = {}, onRetrySkr = {},
                        onNicknameChange = {}, onConnect = {}, onDisconnect = {},
                        onFaucet = {}, onStake = {}, onExit = {}, onCancel = {}, onWithdraw = {},
                        demoFeed = false)
                }
            }
        }
        compose.onNodeWithText("Changer la langue").performScrollTo().performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("Welcome").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("en", AppLanguage.selection) }
    }

    @Test fun explicit_choice_survives_initialization_and_can_follow_device_again() {
        compose.runOnIdle {
            AppLanguage.select("en")
            AppLanguage.initialize(context)
            assertEquals("en", AppLanguage.code)
            assertEquals("en", AppLanguage.selection)
            AppLanguage.select(null)
            AppLanguage.initialize(context)
            assertEquals(null, AppLanguage.selection)
            assertEquals(AppLanguage.deviceCode, AppLanguage.code)
        }
    }
}
