package com.torve.android.tv.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvSettingsContentFocusDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun playbackRowsStayInContentWhileMovingPastBothEnds() {
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            TvAlignedSettingsPanel(remember { FocusRequester() }, null, true, false, {}, {}, {},
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, legacyForm = { _, _, _, _ -> Text("Account") })
        } }
        rule.onNodeWithTag("settings.category.playback").performClick()
        rule.onNodeWithTag("settings.item.video").performClick()
        val titles = listOf("Playback preset", "Automatic source selection", "Maximum quality", "Minimum quality",
            "Match frame rate", "HDR", "Video codec", "Start playback automatically")
        focused(titles.first())
        repeat(4) { rule.onNodeWithText(titles.first()).performKeyInput { pressKey(Key.DirectionUp) } }
        focused(titles.first())
        titles.zipWithNext().forEach { (from, to) ->
            rule.onNodeWithText(from).performKeyInput { pressKey(Key.DirectionDown) }; focused(to)
        }
        repeat(4) { rule.onNodeWithText(titles.last()).performKeyInput { pressKey(Key.DirectionDown) } }
        focused(titles.last())
        titles.reversed().zipWithNext().forEach { (from, to) ->
            rule.onNodeWithText(from).performKeyInput { pressKey(Key.DirectionUp) }; focused(to)
        }
        rule.onNodeWithTag("settings.category.playback").assertIsNotFocused()
        rule.onNodeWithText(titles.first()).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("settings.category.playback").assertIsFocused()
    }

    @Test fun aBusySettingsActionKeepsFocusAndCannotRunAgain() {
        var enabled by mutableStateOf(true)
        var activations = 0
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { MaterialTheme {
            TvGroupedSettingsBrowser(remember { FocusRequester() }, null, true, false, {}, {}, {}, { false },
                initialAction = "Playback") { _, first, _, focused, _, _ ->
                Column {
                    TvSettingsActionRow("Check connection", enabled = enabled, modifier = Modifier.focusRequester(first)
                        .onFocusChanged { if (it.isFocused) focused(first) }.testTag("busy.action")) { activations++ }
                    TvSettingsActionRow("Next setting", modifier = Modifier.testTag("busy.next")) {}
                }
            }
        } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("busy.action").assertIsFocused() }.isSuccess }
        rule.runOnIdle { enabled = false }
        rule.onNodeWithTag("busy.action").assertIsFocused().assertIsNotEnabled()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(0, activations) }
        rule.onNodeWithTag("busy.action").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("busy.next").assertIsFocused()
        rule.onNodeWithTag("settings.category.playback").assertIsNotFocused()
        rule.onNodeWithTag("busy.next").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("busy.next").assertIsFocused()
        rule.runOnIdle { enabled = true }
        rule.onNodeWithTag("busy.next").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("busy.action").assertIsFocused()
    }

    private fun focused(title: String) {
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(title).assertIsFocused().assertIsDisplayed() }.isSuccess }
    }
}
