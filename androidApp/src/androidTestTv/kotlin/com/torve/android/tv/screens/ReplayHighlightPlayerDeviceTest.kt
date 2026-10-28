package com.torve.android.tv.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.ui.replays.ReplayHighlightPlayer
import com.torve.android.ui.replays.ReplayYouTubeBrowser
import com.torve.data.replays.ReplayHighlight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplayHighlightPlayerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<com.torve.android.test.TorveTestHostActivity>()

    @Test fun mobileEmbeddedPlayerBackReturnsWithoutLaunchingAnExternalApp() {
        var externalOpened = false
        compose.setContent {
            com.torve.android.ui.theme.TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                var playing by remember { mutableStateOf(true) }
                if (playing) ReplayHighlightPlayer(ReplayHighlight("gtfEW_f1-b8", "Paris vs Valencia — highlights", "EuroLeague", 590),
                    onDismiss = { playing = false }, onOpenExternal = { externalOpened = true }, isTv = false)
                else Text("Returned to Torve highlights")
            }
        }
        compose.onNodeWithTag("highlights.player.close").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 18_000) {
            compose.onAllNodesWithTag("highlights.player.playing").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Choose another highlight").fetchSemanticsNodes().isNotEmpty()
        }
        val playing = compose.onAllNodesWithTag("highlights.player.playing").fetchSemanticsNodes().isNotEmpty()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "highlight-playback-observation.json")
            .writeText("{\"playingObserved\":$playing,\"externalAutomaticallyOpened\":$externalOpened}")
        val image = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "replay-embedded-player.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Returned to Torve highlights").assertIsDisplayed()
        assertFalse("A Back press must never hand off to YouTube or SmartTube", externalOpened)
    }

    @Test fun searchFallbackHasANativeCloseAndBackReturnsToTorve() {
        compose.setContent {
            com.torve.android.ui.theme.TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                var browsing by remember { mutableStateOf(true) }
                if (browsing) ReplayYouTubeBrowser("https://www.youtube.com/channel/UCWJ2lWNubArHWmf3FIHbfcQ/search?query=Bucks%2076ers%20highlights&hl=en") { browsing = false }
                else Text("Returned from YouTube browsing to Torve")
            }
        }
        compose.onNodeWithTag("highlights.browser.close").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Returned from YouTube browsing to Torve").assertIsDisplayed()
    }
}
