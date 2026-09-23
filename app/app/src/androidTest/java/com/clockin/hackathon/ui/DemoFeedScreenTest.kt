package com.clockin.hackathon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clockin.hackathon.ChainState
import com.clockin.hackathon.chain.CheckInAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Le mode démo remplit le cercle de faux Moments. Ce qu'on vérifie ici, c'est
 * qu'ils sont visibles, signalés comme faux, et qu'ils remplacent bien le mot
 * d'attente du keyserver plutôt que de s'y ajouter.
 */
class DemoFeedScreenTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()

    private val morning = 20_348L * DAY_SECONDS + 9 * 3600
    private val today = Math.floorDiv(morning, DAY_SECONDS)
    private val keyserverNote = "Les Moments des autres arrivent avec le serveur de clés"

    private val checkedIn = ChainState(
        todayCheckIn = CheckInAccount(ByteArray(32), today, ByteArray(32), ByteArray(32), 1, 4),
        day = today,
        loaded = true,
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Stockage interne : sur API 30+, adb ne lit plus /sdcard/Android/data,
        // alors que `run-as` atteint le dossier privé d'un build debug.
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun feed(state: ChainState, demo: Boolean) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, onSurface = White)) {
                // Le feed vit normalement dans une colonne défilante : cinq cartes
                // dépassent l'écran, et sans défilement rien n'est « displayed ».
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        FeedTopBar("", state.posted, null) {}
                        BoxWithConstraints(Modifier.weight(1f)) {
                            val viewportHeight = maxHeight
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = state.posted)
                                .padding(26.dp)) {
                                Feed(state, morning, unlocked = state.feedUnlocked, demo = demo,
                                    photos = null, caption = "",
                                    favorites = emptySet(), onToggleFavorite = {},
                                    onCapture = {}, onProfile = {},
                                    minimumHeight = (viewportHeight - 52.dp).coerceAtLeast(0.dp))
                            }
                        }
                        DailyNavigation(MomentPage.Home, state.posted, {}, {})
                    }
                }
            }
        }
    }

    @Test fun the_demo_fills_the_circle_with_other_people() {
        feed(checkedIn, demo = true)
        screenshot("01-feed-demo")
        val moments = demoMoments(today)
        moments.forEach { compose.onNodeWithText(it.name, substring = true).performScrollTo().assertIsDisplayed() }
        // L'étiquette ne marque que les illustrations : ton propre Moment est réel.
        compose.onAllNodesWithText("Illustration").assertCountEquals(moments.size)
    }

    @Test fun the_demo_replaces_the_keyserver_note() {
        feed(checkedIn, demo = true)
        compose.onNodeWithText(keyserverNote, substring = true).assertDoesNotExist()
    }

    @Test fun without_the_demo_the_circle_still_explains_the_keyserver() {
        feed(checkedIn, demo = false)
        screenshot("02-feed-sans-demo")
        compose.onNodeWithText(keyserverNote, substring = true).assertIsDisplayed()
        compose.onNodeWithText(demoMoments(today).first().name, substring = true).assertDoesNotExist()
    }

    @Test fun the_demo_does_not_unlock_home_without_a_check_in() {
        feed(ChainState(day = today, loaded = true), demo = true)
        compose.onNodeWithText("Ton moment ouvre le fil").assertIsDisplayed()
        compose.onNodeWithText(demoMoments(today).first().name, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Toi").assertDoesNotExist()
    }

    @Test fun the_locked_circle_is_untouched_when_the_demo_is_off() {
        feed(ChainState(day = today, loaded = true), demo = false)
        compose.onNodeWithText("Ton moment ouvre le fil").assertIsDisplayed()
        screenshot("03-home-locked")
    }

    @Test fun the_profile_switch_reports_both_directions() {
        val enabled = mutableStateOf(false)
        val seen = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, onSurface = White)) {
                Surface { DemoSection(enabled.value) { seen += it; enabled.value = it } }
            }
        }
        compose.onNodeWithText("Afficher de faux Moments").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.onNode(isToggleable()).assertIsOn().performClick()
        compose.runOnIdle { assertEquals(listOf(true, false), seen) }
    }

    /** Sous le verrou, rien du fil ne doit être lisible — ni à l'œil, ni au lecteur d'écran. */
    @Test fun the_locked_preview_hides_feed_semantics() {
        feed(ChainState(day = today, loaded = true), demo = true)
        compose.onAllNodesWithText("Illustration").assertCountEquals(0)
    }

    @Test fun every_demo_illustration_differs_from_its_neighbour() {
        val variants = demoMoments(today).map { it.variant }
        assertTrue("des cartes voisines identiques ne montreraient rien du feed",
            variants.zipWithNext().all { (a, b) -> a != b })
    }
}
