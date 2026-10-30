package com.torve.android.ui.tv

import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.TvMainActivity
import com.torve.android.tv.nav.TvRoutes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Keep the production activity, root navigation, Settings and Amazon keyboard in the test. */
@OptIn(ExperimentalTestApi::class)
class TvExtensionSettingsRootTest {
    @get:Rule val rule = createAndroidComposeRule<TvMainActivity>()

    @Test fun realTvActivityKeepsJsonEntryInExtensionsForRemoteAndPointer() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val settingsTag = "tv_nav_item_${TvRoutes.SETTINGS}"
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(settingsTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(settingsTag).performClick()
        rule.onNodeWithTag("settings.category.connections").performClick()
        rule.onNodeWithTag("settings.item.streaming").performClick()
        rule.onNodeWithTag("settings.item.extensions").performClick()
        val field = rule.onNodeWithTag("extensions.url")
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        checkEditor()
        val link = "https://example.com/manifest.json"
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input text $link")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { it.readBytes() }
        editor().assertTextContains(link)
        editor().performImeAction()
        rule.onNodeWithTag("settings.form.extensions").assertIsDisplayed()
        rule.onNodeWithTag("extensions.preview").assertIsEnabled()
        field.assertIsFocused().performTouchInput { click() }
        checkEditor()
        editor().assertTextContains(link)
        Espresso.pressBack()
        rule.onNodeWithTag("settings.form.extensions").assertIsDisplayed()
        field.assertIsFocused()
    }

    private fun editor() = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()
    private fun checkEditor() {
        android.os.SystemClock.sleep(800)
        rule.onNodeWithTag("settings.form.extensions").assertExists()
        editor().assertIsFocused()
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).bufferedReader().use { it.readText() }
        assertTrue("Amazon keyboard must be shown", dump.contains("mInputShown=true"))
    }
}
