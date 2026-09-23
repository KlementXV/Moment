package com.clockin.hackathon.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.moderation.ModerationAnalysis
import com.clockin.hackathon.moderation.ModerationPolicy
import com.clockin.hackathon.moderation.ModerationState
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Les écrans de capture, en image. Ce test ne juge rien — il fige ce que la
 * maquette a demandé pour qu'on puisse le comparer à l'œil.
 */
class CaptureScreensScreenshotTest {
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

    private fun shoot(name: String, screen: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink,
                background = Ink, surface = Panel, onSurface = White)) {
                Surface(Modifier.fillMaxSize(), color = Ink, contentColor = White) { screen() }
            }
        }
        compose.waitForIdle()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun the_review_screen() {
        shoot("capture-02-apercu") {
            CaptureReview(photos(), canPublish = true, busy = false, onRetake = {},
                onPublish = { _, _ -> }, onProfile = {},
                moderation = accepted, onRetryModeration = {})
        }
    }

    @Test fun the_permission_screen() {
        // L'état bloqué : c'est celui qui porte le plus de texte, donc celui
        // dont la mise en page peut déborder.
        shoot("capture-00-autorisation") {
            CameraPermission(blocked = true, onClose = {}, onAllow = {}, onSettings = {})
        }
    }

    @Test fun the_published_screen() {
        shoot("capture-03-publie") {
            CapturePublished(photos(), 20_348L * DAY_SECONDS + 9 * 3600, onDismiss = {})
        }
    }
}
