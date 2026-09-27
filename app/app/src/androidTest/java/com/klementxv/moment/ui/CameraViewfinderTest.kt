package com.klementxv.moment.ui

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class CameraViewfinderTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val language = FrenchLanguageRule()

    @Before fun grantCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
    }

    @Test fun the_viewfinder_opens_and_arms_the_shutter() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink,
                background = Ink, surface = Panel, onSurface = White)) {
                Surface(Modifier.fillMaxSize(), color = Ink, contentColor = White) {
                    CameraCapture(enabled = true, now = 20_348L * DAY_SECONDS + 9 * 3600,
                        onClose = {}, onDraft = {})
                }
            }
        }
        compose.onNodeWithContentDescription("Capturer la scène").assertExists()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Ouverture de la caméra…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Moment nécessite une caméra arrière et une caméra avant", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Capturer la scène").assertIsEnabled()
        compose.onNodeWithContentDescription("Reprendre la scène").assertDoesNotExist()

        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "capture-01-viseur.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
