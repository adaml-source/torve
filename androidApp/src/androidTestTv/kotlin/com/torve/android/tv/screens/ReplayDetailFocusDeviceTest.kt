package com.torve.android.tv.screens

import android.view.KeyEvent
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.replays.ReplayDetailContent
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.replays.ReplayEvent
import com.torve.data.replays.ReplaySourceOption
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real remote events, including the lazy header leaving and returning to composition. */
class ReplayDetailFocusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()

    private fun show(options: List<ReplaySourceOption>) {
        compose.setContent { TorveTheme(ThemeMode.DARK) {
            ReplayDetailContent(
                ReplayEvent("focus-test", "test", "United States Grand Prix — Full Race Replay", "https://example.invalid",
                    competition = "Formula 1", date = "2026-10-25"),
                true, options, false, false, {}, {}, {}, {}, {},
            )
        } }
        focused("replay.back")
    }

    private fun sources(count: Int, grouped: Boolean = false) = (1..count).map {
        ReplaySourceOption("$it", "Server $it", "https://example.invalid/$it", "https://example.invalid",
            gameTitle = if (grouped) "Game ${(it - 1) / 2 + 1}" else null)
    }

    private fun focused(tag: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed() }.isSuccess
        }
        // Allow bring-into-view and a newly composed top bar to finish before asserting again.
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed()
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun assertAmber(tag: String) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        var amberPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .65f && color.green > .35f &&
                color.red > color.blue * 1.3f && color.green > color.blue * 1.15f) amberPixels++
        }
        assertTrue("$tag needs an amber focus outline, found $amberPixels amber pixels", amberPixels > pixels.width / 2)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "$tag-amber-focus.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun highlightsAreReachableFromBackAndSourcesWithAmberFocus() {
        show(sources(3))
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.highlights"); assertAmber("replay.highlights")
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.source.1"); assertAmber("replay.source.1")
        key(KeyEvent.KEYCODE_DPAD_UP); focused("replay.highlights")
        key(KeyEvent.KEYCODE_DPAD_UP); focused("replay.back")
    }

    @Test fun returningFromLongSourceListDoesNotLetBackStealHighlightsFocus() {
        show(sources(10))
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.highlights")
        for (index in 1..10) {
            key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.source.$index")
        }
        compose.onNodeWithTag("replay.back").assertDoesNotExist()
        for (index in 9 downTo 1) {
            key(KeyEvent.KEYCODE_DPAD_UP); focused("replay.source.$index")
        }
        key(KeyEvent.KEYCODE_DPAD_UP); focused("replay.highlights")
        key(KeyEvent.KEYCODE_DPAD_UP); focused("replay.back")
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.highlights")
    }

    @Test fun groupedSourcesReturnToMainHighlightsAfterScrollingPastHeader() {
        show(sources(8, grouped = true))
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.highlights")
        for (group in 1..4) {
            for (source in (group * 2 - 1)..(group * 2)) {
                key(KeyEvent.KEYCODE_DPAD_DOWN); focused("replay.source.$source")
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            focused("replay.game.Game $group")
        }
        compose.onNodeWithTag("replay.back").assertDoesNotExist()
        for (group in 4 downTo 1) {
            for (source in (group * 2) downTo (group * 2 - 1)) {
                key(KeyEvent.KEYCODE_DPAD_UP); focused("replay.source.$source")
            }
            key(KeyEvent.KEYCODE_DPAD_UP)
            if (group == 1) focused("replay.highlights")
            else focused("replay.game.Game ${group - 1}")
        }
    }
}
