package com.torve.android.ui.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.player.TvAudioDelayOverlay
import com.torve.android.ui.player.TvSubtitleDelayOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TvDelayAdjustmentUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun value() = rule.onNodeWithTag("tv_delay_value")
    private fun key(key: Key) = value().performKeyInput { pressKey(key) }
    private fun awaitValueFocus() { rule.waitUntil(5_000) { runCatching { value().assertIsFocused(); true }.getOrDefault(false) } }

    @Test fun audioPreviewMovesInFiftyMillisecondStepsAndOkSaves() {
        val previews = mutableListOf<Int>(); var saved: Int? = null
        rule.setContent { MaterialTheme {
            TvAudioDelayOverlay(600, { previews += it }, { saved = it }, {}, {})
        } }
        awaitValueFocus()
        key(Key.DirectionRight)
        value().assertIsFocused()
        rule.onNodeWithText("‹  +650 ms  ›").assertExists()
        key(Key.DirectionCenter)
        rule.runOnIdle { assertEquals(listOf(650), previews); assertEquals(650, saved) }
    }

    @Test fun subtitleNegativeValuesResetAndCloseRemainReachableOnTheRemote() {
        var preview = 0; var resets = 0; var closed = 0
        rule.setContent { MaterialTheme {
            TvSubtitleDelayOverlay(-50, {}, { resets++ }, { closed++ }, { preview = it })
        } }
        awaitValueFocus()
        rule.onNodeWithText("‹  −50 ms  ›").assertExists()
        key(Key.DirectionLeft)
        rule.runOnIdle { assertEquals(-100, preview) }
        key(Key.DirectionDown)
        val save = rule.onNodeWithText(rule.activity.getString(R.string.common_save))
        val reset = rule.onNodeWithText(rule.activity.getString(R.string.common_reset))
        val close = rule.onNodeWithText(rule.activity.getString(R.string.common_close))
        save.assertIsFocused()
        save.performKeyInput { pressKey(Key.DirectionRight) }
        reset.assertIsFocused()
        reset.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("‹  0 ms  ›").assertExists()
        reset.assertIsFocused()
        reset.performKeyInput { pressKey(Key.DirectionRight) }
        close.assertIsFocused()
        close.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, resets); assertEquals(1, closed) }
    }

    @Test fun audioDelayStopsAtItsSupportedLimitWithoutRepeatedPreviewWrites() {
        val previews = mutableListOf<Int>()
        rule.setContent { MaterialTheme { TvAudioDelayOverlay(1950, { previews += it }, {}, {}, {}) } }
        awaitValueFocus()
        key(Key.DirectionRight); key(Key.DirectionRight)
        rule.onNodeWithText("‹  +2000 ms  ›").assertExists()
        rule.runOnIdle { assertEquals(listOf(2000), previews) }
    }

    @Test fun backDismissesAnUnsavedSubtitlePreviewWithoutSaving() {
        var saves = 0; var dismissals = 0
        rule.setContent { MaterialTheme { TvSubtitleDelayOverlay(0, { saves++ }, {}, { dismissals++ }) } }
        awaitValueFocus()
        key(Key.DirectionRight)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(0, saves); assertEquals(1, dismissals) }
    }

    @Test fun bothDelayStripsLeaveThePictureAndSubtitlesVisible() {
        var subtitles by mutableStateOf(false)
        rule.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF527D81), Color(0xFFE2B193))))) {
                Text("The subtitles remain unobstructed.", Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp), color = Color.White)
                if (subtitles) TvSubtitleDelayOverlay(-50, {}, {}, {}) else TvAudioDelayOverlay(600, {}, {}, {}, {})
            }
        } }
        fun checkAndCapture(name: String) {
            awaitValueFocus()
            val strip = rule.onNodeWithTag("tv_delay_strip").fetchSemanticsNode().boundsInRoot
            val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Delay adjustment must stay a thin line", strip.height < screen.height * .11f)
            assertTrue("Delay strip must fit on screen", strip.left >= screen.left && strip.right <= screen.right)
            rule.onNodeWithText("The subtitles remain unobstructed.").assertIsDisplayed()
            rule.waitForIdle()
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(rule.activity.getExternalFilesDir(null), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        checkAndCapture("audio-delay-strip.png")
        rule.runOnIdle { subtitles = true }
        checkAndCapture("subtitle-delay-strip.png")
    }
}
