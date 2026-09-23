package com.clockin.hackathon.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.clockin.hackathon.capture.DraftCodec
import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.moderation.ModerationAnalysis
import com.clockin.hackathon.moderation.ModerationPolicy
import com.clockin.hackathon.moderation.ModerationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Ce que l'aperçu promet de la légende : une borne, et un trajet jusqu'à la publication. */
class CaptureReviewTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()
    private val accepted = ModerationState.Ready(
        ModerationAnalysis(.05f, .05f, 120, ModerationPolicy("ui-test", .5f, .9f, true))
    )

    private fun photos(): PhotoPair {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return PhotoPair(assets.open("moderation/rear.jpg").use { it.readBytes() },
            assets.open("moderation/front.jpg").use { it.readBytes() })
    }

    private fun review(onPublish: (Boolean, String) -> Unit) {
        val pair = photos()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink,
                background = Ink, surface = Panel, onSurface = White)) {
                Surface(color = Ink, contentColor = White) {
                    CaptureReview(pair, canPublish = true, busy = false,
                        onRetake = {}, onPublish = onPublish, onProfile = {},
                        moderation = accepted, onRetryModeration = {})
                }
            }
        }
    }

    @Test fun the_caption_reaches_publication() {
        var published: String? = null
        review { _, caption -> published = caption }
        compose.onNodeWithContentDescription("Légende").performTextInput("Le café d’en bas")
        compose.onNodeWithText("Publier").performClick()
        compose.runOnIdle { assertEquals("Le café d’en bas", published) }
    }

    @Test fun a_caption_past_the_limit_is_truncated_not_refused() {
        var published: String? = null
        review { _, caption -> published = caption }
        val tooLong = "a".repeat(DraftCodec.MAX_CAPTION_CHARS + 20)
        compose.onNodeWithContentDescription("Légende").performTextInput(tooLong)
        compose.onNodeWithText("Publier").performClick()
        compose.runOnIdle {
            assertEquals("a".repeat(DraftCodec.MAX_CAPTION_CHARS), published)
        }
    }

    @Test fun publishing_without_a_caption_sends_an_empty_one() {
        var published: String? = null
        review { _, caption -> published = caption }
        compose.onNodeWithText("0/${DraftCodec.MAX_CAPTION_CHARS}").assertExists()
        compose.onNodeWithText("Publier").performClick()
        compose.runOnIdle { assertEquals("", published) }
    }
}
