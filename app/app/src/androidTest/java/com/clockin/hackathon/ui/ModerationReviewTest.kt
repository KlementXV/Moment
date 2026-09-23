package com.clockin.hackathon.ui

import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.moderation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ModerationReviewTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()
    private val policy = ModerationPolicy("ui-test", .5f, .9f, true)
    private fun ready(score: Float) = ModerationState.Ready(ModerationAnalysis(score, .1f, 120, policy))

    @Test fun pending_failure_block_and_review_have_distinct_actions() {
        val state = mutableStateOf<ModerationState>(ModerationState.Pending)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val photos = PhotoPair(assets.open("moderation/rear.jpg").use { it.readBytes() },
            assets.open("moderation/front.jpg").use { it.readBytes() })
        var publications = 0
        var acknowledged = false
        var retries = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, background = Ink, surface = Panel, onSurface = White)) {
                Surface(color = Ink, contentColor = White) {
                    CaptureReview(photos, canPublish = true, busy = false,
                        onRetake = {}, onPublish = { ack, _ -> publications++; acknowledged = ack },
                        onProfile = {},
                        moderation = state.value, onRetryModeration = { retries++ })
                }
            }
        }
        compose.onNodeWithText("Analyse des photos…").assertIsNotEnabled()
        compose.runOnIdle { state.value = ModerationState.Unavailable }
        compose.onNodeWithText("Réessayer l’analyse").performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = ready(.95f) }
        compose.onNodeWithText("Publication indisponible").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, publications); state.value = ready(.7f) }
        compose.onNodeWithText("Publier").performClick()
        compose.onNodeWithText("Reprendre les photos ?").assertIsDisplayed()
        compose.onNodeWithText("J’ai vérifié, publier").performClick()
        compose.runOnIdle {
            assertEquals(1, publications)
            assertTrue(acknowledged)
            state.value = ready(.05f).let { it.copy(analysis = it.analysis.copy(policy = policy.copy(calibrated = false))) }
        }
        compose.onNodeWithText("Détection expérimentale · seuils non calibrés").assertExists()
        compose.onNodeWithText("Publier").assertIsEnabled()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "moderation-review.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
