package com.torve.android.tv.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** Real service renderer, using the QA app's existing account without changing credentials. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class TvServiceSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()
    private fun show() {
        compose.setContent {
            TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                val rail = remember { FocusRequester() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TvAlignedSettingsPanel(rail, null, true, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                        legacyForm = { _, _, _, _ -> Text("Other settings") })
                }
            }
        }
    }
    private fun categoriesStayVisible() {
        tvSettingsGroups.forEach { compose.onNodeWithTag("settings.category.${it.id}").assertIsDisplayed() }
    }
    private fun screenshot(name: String) {
        // Compose idleness does not include the Android dialog window fade.
        android.os.SystemClock.sleep(500)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val target = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun streamingServicesOpensDebrid_notExtensions() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.waitForIdle()
        screenshot("liquid-settings-menu.png")
        compose.onNodeWithTag("settings.item.streaming").performClick()
        compose.onNodeWithTag("settings.item.debrid").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        screenshot("liquid-settings-sources.png")
        compose.onNodeWithTag("settings.item.debrid").performClick()
        compose.onNodeWithTag("settings.form.debrid").assertIsDisplayed()
        compose.onNodeWithText("Save & connect").assertExists()
        compose.onNodeWithText("Add extension").assertDoesNotExist()
        categoriesStayVisible()
        screenshot("settings-services.png")
    }
    @Test fun traktStaysInCategoryShell_andDoesNotIncludeOtherServices() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.item.tracking").performClick()
        compose.onNodeWithTag("settings.item.trakt").performClick()
        compose.onNodeWithTag("settings.form.trakt").assertIsDisplayed()
        compose.onNodeWithText("SIMKL").assertDoesNotExist()
        compose.onNodeWithText("Receive credentials from another device").assertDoesNotExist()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("settings.form.trakt"))).assertExists()
        compose.onNodeWithTag("settings.category.account").assertIsNotFocused()
        categoriesStayVisible()
        screenshot("settings-trakt.png")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings.item.trakt").assertIsFocused()
    }
    @Test fun pickerBackRestoresTheServiceRowAndUsesAnOpaquePanel() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.item.streaming").performClick()
        compose.onNodeWithTag("settings.item.debrid").performClick()
        compose.onNodeWithText("Service").performClick()
        compose.onNodeWithTag("settings.choice").assertIsDisplayed()
        screenshot("settings-service-choice.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("settings.choice").assertDoesNotExist()
        compose.onNodeWithText("Service").assertIsFocused()
        compose.onNodeWithTag("settings.category.account").assertIsNotFocused()
    }
    @Test fun extensionsKeepTheSettingsMenuAndRemoteBackReturnsToStreaming() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.item.streaming").performClick()
        compose.onNodeWithTag("settings.item.extensions").performClick()
        compose.onNodeWithTag("extensions.screen").assertIsDisplayed()
        categoriesStayVisible()
        compose.onNodeWithText("Back").assertDoesNotExist()
        screenshot("settings-extensions-embedded.png")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings.item.extensions").assertIsFocused()
    }
    @Test fun replaySetupHasWebsiteList_andKeyboardRequiresOk() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.item.streaming").performClick()
        compose.onNodeWithTag("settings.item.web_replays").performClick()
        compose.onNodeWithTag("settings.form.web_replays").assertIsDisplayed()
        categoriesStayVisible()
        screenshot("settings-replays.png")
        compose.onNodeWithText("Add website").performClick()
        compose.onNodeWithTag("replays.website.address").assertIsFocused()
        screenshot("settings-replays-add.png")
        compose.waitForIdle()
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).bufferedReader().use { it.readText() }
        assertEquals("Focusing a field must not open the IME", false, dump.contains("mInputShown=true"))
        // No submit: the test never adds, removes or edits the user's websites.
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Your websites").assertIsDisplayed()
    }
    @Test fun playbackPageFillsAvailableWidth() {
        show()
        compose.onNodeWithTag("settings.category.playback").performClick()
        compose.onNodeWithTag("settings.item.video").performClick()
        compose.onNodeWithText("Match frame rate").assertExists()
        compose.onNodeWithText("Automatic source selection").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Automatic source selection").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Playback preset").assertIsFocused()
        compose.onNodeWithText("Playback preset").performKeyInput { pressKey(Key.DirectionUp) }
        repeat(4) {
            compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("settings.content")))
                .performKeyInput { pressKey(Key.DirectionUp) }
        }
        compose.onNodeWithTag("settings.category.playback").assertIsNotFocused()
        categoriesStayVisible()
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val formRight = compose.onNodeWithTag("settings.form.video").fetchSemanticsNode().boundsInRoot.right
        org.junit.Assert.assertTrue("Settings should reach the screen's right gutter", rootWidth - formRight < rootWidth * .05f)
        screenshot("settings-playback.png")
    }

    @Test fun fullSettingsKeepsOneSidebarForAccountAndChannels() {
        compose.setContent {
            TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val rail = remember { FocusRequester() }
                    val controller = com.torve.android.tv.focus.rememberTvSettingsFocusStateMachine("full-settings-test")
                    TvSettingsScreen(rail, {}, {}, settingsFocusController = controller)
                }
            }
        }
        compose.onNodeWithTag("settings.category.account").performClick()
        compose.onNodeWithTag("settings.item.account_devices").performClick()
        compose.onNodeWithTag("settings.form.account_devices").assertIsDisplayed()
        repeat(4) {
            compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("settings.content")))
                .performKeyInput { pressKey(Key.DirectionUp) }
        }
        compose.onNodeWithTag("settings.category.account").assertIsNotFocused()
        compose.onNodeWithText("Back to settings").assertDoesNotExist()
        categoriesStayVisible()
        screenshot("settings-account.png")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings.category.library").performClick()
        compose.onNodeWithTag("settings.item.channels").performClick()
        compose.onNodeWithTag("settings.form.channels").assertIsDisplayed()
        categoriesStayVisible()
    }

    @Test fun externalEntriesAreConsumedAndDoNotReopenAfterBrowsing() {
        val entry = mutableStateOf<String?>("account")
        compose.setContent {
            TorveTheme(themeMode = com.torve.presentation.settings.ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val rail = remember { FocusRequester() }
                    val controller = com.torve.android.tv.focus.rememberTvSettingsFocusStateMachine("entry-settings-test")
                    TvSettingsScreen(rail, {}, {}, settingsFocusController = controller,
                        entryAction = entry.value, onEntryConsumed = { entry.value = null })
                }
            }
        }
        compose.onNodeWithTag("settings.form.account_devices").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, entry.value) }
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.menu.connections").assertIsDisplayed()
        compose.runOnIdle { entry.value = "about" }
        compose.onNodeWithTag("settings.form.about").assertIsDisplayed()
        categoriesStayVisible()
        compose.runOnIdle { assertEquals(null, entry.value) }
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.menu.connections").assertIsDisplayed()
    }

}
