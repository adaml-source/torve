package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvSettingsAppearanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()
    private fun focus(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)

    @Test fun focusingASettingUsesABorderWithoutABrightFillOrResizing() {
        compose.setContent { MaterialTheme { TvSettingsStyle {
            Column(Modifier.fillMaxSize().background(Color(0xFF1256FF)).padding(32.dp)) {
                TvSettingsActionRow("", modifier = Modifier.width(500.dp).testTag("row")) {}
                TvSettingsActionRow("Other", modifier = Modifier.testTag("other")) {}
            }
        } } }
        focus("other")
        val before = compose.onNodeWithTag("row").captureToImage().toPixelMap()
        val bounds = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        focus("row")
        val after = compose.onNodeWithTag("row").captureToImage().toPixelMap()
        val next = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width, next.width, 1f); assertEquals(bounds.height, next.height, 1f)
        val a = before[before.width / 2, before.height / 2].luminance()
        val b = after[after.width / 2, after.height / 2].luminance()
        assertTrue("Focus fill must stay dark: $b", b < .20f)
        assertTrue("Focus must not flash a bright box: $a -> $b", b - a < .06f)
    }

    @Test fun popupPanelMasksTheBackgroundAndBackDismissesOnlyThePopup() {
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme { TvSettingsStyle {
            Box(Modifier.fillMaxSize().background(Color.Blue)) {
                if (visible) TvSettingsChoiceDialog(TvSettingsChoice("Streaming service", "rd",
                    listOf("rd" to "Real-Debrid", "tb" to "TorBox")) {}) { visible = false }
            }
        } } }
        val pixels = compose.onNodeWithTag("settings.choice").captureToImage().toPixelMap()
        val sample = pixels[pixels.width / 2, pixels.height - 12]
        assertTrue("Blue page must not bleed through the panel: $sample", sample.blue < .20f)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("settings.choice").assertDoesNotExist()
    }

    @Test fun aSettingsWorkflowReturnsToItsPageWithOneRemoteBackPress() {
        var exits = 0
        compose.setContent { MaterialTheme { TvSettingsStyle {
            val entry = remember { FocusRequester() }
            TvWorkflowPage("Nested setting", "", { exits++ }, entry, {}) {
                item { TvWorkflowAction("Setting value", {}) }
            }
        } } }
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Setting value").assertIsFocused()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, exits) }
    }
}
