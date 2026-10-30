package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.focus.rememberTvSettingsFocusStateMachine
import com.torve.android.tv.screens.TvSettingsScreen
import org.junit.Rule
import org.junit.Test

/** Exercise the real Settings sidebar around the editor, where focus fallback can navigate away. */
@OptIn(ExperimentalTestApi::class)
class TvEmbeddedExtensionLinkInputTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    private fun open() {
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { MaterialTheme {
            TvSettingsScreen(railFocusRequester = remember { FocusRequester() },
                onFirstContentRequester = {}, onContentFocused = {},
                mainEntryFocusRequester = remember { FocusRequester() },
                settingsFocusController = rememberTvSettingsFocusStateMachine("json-settings"))
        } }
        rule.onNodeWithTag("settings.category.connections").performClick()
        rule.onNodeWithTag("settings.item.streaming").performClick()
        rule.onNodeWithTag("settings.item.extensions").performClick()
        rule.onNodeWithTag("extensions.url").performSemanticsAction(SemanticsActions.RequestFocus)
    }

    private fun editor() = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()

    private fun checkEditor() {
        android.os.SystemClock.sleep(800)
        rule.waitForIdle()
        // Fire OS resizes the app under its fullscreen keyboard. The form can
        // be clipped then, but must stay mounted with its editor owning focus.
        rule.onNodeWithTag("settings.form.extensions").assertExists()
        rule.onNodeWithTag("extensions.screen").assertExists()
        editor().assertIsFocused()
        val keyboard = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys input_method")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(keyboard).bufferedReader().use { it.readText() }
        assertTrue("The actual Fire OS keyboard must be open", dump.contains("mInputShown=true"))
    }

    @Test fun remoteOkKeepsEmbeddedExtensionsOpenWhileTypingAndFinishing() {
        open()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        checkEditor()
        instrumentation.sendKeySync(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        checkEditor()
        editor().performTextInput("https://example.com/manifest.json")
        editor().performImeAction()
        rule.onNodeWithTag("settings.form.extensions").assertIsDisplayed()
        rule.onNodeWithTag("extensions.preview").assertIsEnabled()
        rule.onNodeWithTag("extensions.url").assertIsFocused()
    }

    @Test fun pointerClickKeepsEmbeddedExtensionsOpenAndBackRetainsLink() {
        open()
        rule.onNodeWithTag("extensions.url").performTouchInput { click() }
        checkEditor()
        editor().performTextInput("https://example.com/collection.json")
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("settings.form.extensions").assertIsDisplayed()
        rule.onNodeWithTag("extensions.preview").assertIsEnabled()
    }
}
