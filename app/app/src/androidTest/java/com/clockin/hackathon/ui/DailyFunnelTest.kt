package com.clockin.hackathon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.clockin.hackathon.ChainState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DailyFunnelTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()

    @Test fun avatar_opens_profile_and_back_restores_home() {
        val page = mutableStateOf(MomentPage.Home)
        compose.setContent {
            MaterialTheme {
                Column {
                    when (page.value) {
                        MomentPage.Home -> FeedTopBar("Clément", false, null) { page.value = MomentPage.Profile }
                        MomentPage.Profile -> androidx.compose.material3.TextButton(
                            onClick = { page.value = MomentPage.Home },
                        ) { androidx.compose.material3.Text("Retour au fil") }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Voir mon profil").performClick()
        compose.onNodeWithText("Retour au fil").performClick()
        compose.onNodeWithContentDescription("Voir mon profil").assertIsDisplayed()
        compose.onNodeWithText("Jour 1").assertDoesNotExist()
    }

    @Test fun locked_home_offers_capture_without_exposing_demo_content() {
        var captures = 0
        compose.setContent {
            MaterialTheme {
                Feed(ChainState(loaded = true), 20_348L * DAY_SECONDS, false, true, null,
                    caption = "", favorites = emptySet(), onToggleFavorite = {},
                    minimumHeight = 640.dp,
                    onCapture = { captures++ }, onProfile = {})
            }
        }
        compose.onNodeWithText("Capturer mon moment").performClick()
        compose.runOnIdle { assertEquals(1, captures) }
        compose.onAllNodesWithText("Illustration").assertCountEquals(0)
    }
}
