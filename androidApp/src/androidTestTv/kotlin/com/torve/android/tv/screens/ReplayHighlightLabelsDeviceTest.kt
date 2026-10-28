package com.torve.android.tv.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.ui.replays.ReplayHighlightChoice
import com.torve.data.replays.ReplayHighlight
import org.junit.Rule
import org.junit.Test

class ReplayHighlightLabelsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<com.torve.android.test.TorveTestHostActivity>()

    @Test fun choicesIdentifyMatchCoveragePartSourceAndRuntimeWithoutScores() {
        compose.setContent {
            com.torve.android.ui.theme.TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                Surface {
                    Column(Modifier.width(420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Highlights", style = MaterialTheme.typography.headlineMedium)
                        ReplayHighlightChoice(ReplayHighlight("safe1234567", "Lakers WIN 123-99! Celtics eliminated! 1st Qtr P2 highlights",
                            "Example Sports", 365, isOfficial = false, matchupLabel = "Lakers vs Celtics", eventDate = "2026-10-23", competitionLabel = "NBA"), false) {}
                        ReplayHighlightChoice(ReplayHighlight("safe7654321", "Lakers 123 Celtics 99! Full Game Highlights",
                            "NBA", 1200, isOfficial = true, matchupLabel = "Lakers vs Celtics", eventDate = "2026-10-23", competitionLabel = "NBA"), false) {}
                    }
                }
            }
        }
        compose.onAllNodesWithText("Lakers vs Celtics").assertCountEquals(2)
        compose.onNodeWithText("1st quarter highlights · Part 2 · 6:05").assertIsDisplayed()
        compose.onNodeWithText("Full game highlights · 20:00").assertIsDisplayed()
        compose.onAllNodesWithText("NBA · 2026-10-23").assertCountEquals(2)
        compose.onNodeWithText("Example Sports · YouTube creator").assertIsDisplayed()
        compose.onNodeWithText("NBA · Official channel").assertIsDisplayed()
        compose.onAllNodesWithText("123-99", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("eliminated", substring = true).assertCountEquals(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "highlight-choice-labels.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
