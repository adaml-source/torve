package com.torve.android.tv.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.ui.replays.ReplayDetailHeader
import com.torve.data.replays.ReplayEvent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReplayDetailLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<com.torve.android.test.TorveTestHostActivity>()

    @Test fun highlightsStayBelowHeroAndTitle_whenFocusedOnWideTv() {
        compose.setContent {
            com.torve.android.ui.theme.TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
                        item { ReplayDetailHeader(ReplayEvent("layout", "site",
                            "Miami Heat vs Charlotte Hornets — NBA — Full Game Replay", "https://example.com/game",
                            artwork = "https://i.ytimg.com/vi/gtfEW_f1-b8/maxresdefault.jpg", sport = "Basketball", date = "2026-10-24"), true) {} }
                    }
                }
            }
        }
        val highlights = compose.onNodeWithTag("replay.highlights")
        highlights.performScrollTo().assertIsDisplayed()
        fun checkBounds() {
            val hero = compose.onNodeWithTag("replay.hero").fetchSemanticsNode().boundsInRoot
            val action = highlights.fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText("Miami Heat vs Charlotte Hornets — NBA — Full Game Replay").fetchSemanticsNode().boundsInRoot
            assertTrue("Highlights must be below the image", action.top > hero.bottom)
            assertTrue("The title must be between artwork and Highlights", title.top >= hero.bottom && action.top > title.bottom)
        }
        checkBounds()
        highlights.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        checkBounds()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "replay-header-upgrade.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
