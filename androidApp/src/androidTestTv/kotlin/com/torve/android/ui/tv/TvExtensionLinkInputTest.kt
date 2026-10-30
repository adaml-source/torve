package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.community.ExtensionsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvExtensionLinkInputTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private var exits = 0

    private fun open() {
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { MaterialTheme { ExtensionsScreen({ exits++ }, isTv = true) } }
        rule.onNodeWithTag("extensions.url").performSemanticsAction(SemanticsActions.RequestFocus)
    }

    private fun editor() = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()

    private fun waitForEditor() {
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Fire OS moves its keyboard into another window; allow that transition.
        rule.mainClock.advanceTimeBy(300)
        android.os.SystemClock.sleep(500)
        rule.waitForIdle()
        rule.onNodeWithTag("extensions.screen").assertIsDisplayed()
        assertEquals(0, exits)
        editor().assertIsFocused()
    }

    @Test fun remoteOkOpensJsonEditorAndDoneRetainsTheLink() {
        open()
        rule.onNodeWithTag("extensions.url").performKeyInput { keyDown(Key.DirectionCenter) }
        waitForEditor()
        rule.onNodeWithTag("extensions.url").performKeyInput { keyUp(Key.DirectionCenter) }
        waitForEditor()
        val link = "https://example.com/collection.json"
        editor().performTextInput(link)
        editor().performImeAction()
        rule.onNodeWithTag("extensions.preview").assertIsEnabled()
        rule.onNodeWithTag("extensions.url").assertIsFocused()
        assertEquals(0, exits)
        rule.onNodeWithTag("extensions.url").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForEditor()
        editor().assertTextContains(link)
        editor().performImeAction()
    }

    @Test fun clickingJsonFieldAndClosingKeyboardKeepsExtensionsOpen() {
        open()
        rule.onNodeWithTag("extensions.url").performClick()
        waitForEditor()
        editor().performTextInput("https://example.com/manifest.json")
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("extensions.screen").assertIsDisplayed()
        rule.onNodeWithTag("extensions.preview").assertIsEnabled()
        assertEquals(0, exits)
    }

    @Test fun pointerClickOpensTheJsonEditorAndAcceptsTyping() {
        open()
        rule.onNodeWithTag("extensions.url").performTouchInput { click() }
        waitForEditor()
        editor().performTextInput("https://example.com/collection.json")
        editor().performImeAction()
        rule.onNodeWithTag("extensions.preview").assertIsEnabled()
        assertEquals(0, exits)
    }
}
