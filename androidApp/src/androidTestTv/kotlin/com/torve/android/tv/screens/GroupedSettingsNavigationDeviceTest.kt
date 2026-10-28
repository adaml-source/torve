package com.torve.android.tv.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the actual grouped settings UI with remote key events. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class GroupedSettingsNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()
    private var publishedEntry: FocusRequester? = null

    private fun show(visible: MutableState<Boolean>? = null, requestEntry: Boolean = false, open: (String) -> Boolean = { false }) {
        compose.setContent {
            MaterialTheme {
                val rail = remember { FocusRequester() }
                val entry = remember { FocusRequester() }
                SideEffect { publishedEntry = entry }
                LaunchedEffect(requestEntry) {
                    if (requestEntry) { withFrameNanos { }; entry.requestFocus() }
                }
                Row {
                    Button(onClick = {}, modifier = Modifier.focusRequester(rail)) { Text("App rail") }
                    Box(Modifier.weight(1f)) {
                        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                        if (visible?.value == false) Button(onClick = { visible.value = true }) { Text("Return to menu") }
                        else holder.SaveableStateProvider("settings") {
                        TvGroupedSettingsBrowser(rail, entry, true, false, {}, {}, {}, open) { action, first, _, focused, openSetting, _ ->
                            Button(onClick = { if (action == "tests") openSetting("Trakt") }, modifier = Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) focused(first) }.testTag("test.form")) { Text("Form: $action") }
                        }
                        }
                    }
                }
            }
        }
    }

    private fun focus(tag: String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) }
    private fun key(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }

    @Test fun sixCategories_areAllReachableWithoutScrolling() {
        show()
        tvSettingsGroups.forEach { compose.onNodeWithTag("settings.category.${it.id}").assertIsDisplayed() }
    }

    @Test fun enteringSettingsFocusesAccountAtTheTopOfTheMenu() {
        show(requestEntry = true)
        compose.onNodeWithTag("settings.category.account").assertIsFocused()
        compose.onNodeWithTag("settings.menu.account").assertIsDisplayed()
        compose.onNodeWithTag("settings.menu.connections").assertDoesNotExist()
    }

    @Test fun browsingCategoriesChangesContentWithoutStealingMenuFocus() {
        show()
        focus("settings.category.account")
        key("settings.category.account", Key.DirectionDown)
        compose.onNodeWithTag("settings.category.connections").assertIsFocused()
        compose.onNodeWithTag("settings.menu.connections").assertIsDisplayed()
        key("settings.category.connections", Key.DirectionDown)
        compose.onNodeWithTag("settings.category.playback").assertIsFocused()
        compose.onNodeWithTag("settings.menu.playback").assertIsDisplayed()
        compose.onNodeWithTag("settings.menu.connections").assertDoesNotExist()
        key("settings.category.playback", Key.DirectionRight)
        compose.onNodeWithTag("settings.item.video").assertIsFocused()
        key("settings.item.video", Key.DirectionLeft)
        compose.onNodeWithTag("settings.category.playback").assertIsFocused()
        key("settings.category.playback", Key.DirectionUp)
        compose.onNodeWithTag("settings.category.connections").assertIsFocused()
        compose.onNodeWithTag("settings.menu.connections").assertIsDisplayed()
    }

    @Test fun upAtTheTopOfTheContentGridStaysInTheContentPane() {
        show(requestEntry = true)
        key("settings.category.account", Key.DirectionRight)
        compose.onNodeWithTag("settings.item.account_devices").assertIsFocused()
        repeat(4) { key("settings.item.account_devices", Key.DirectionUp) }
        compose.onNodeWithTag("settings.item.account_devices").assertIsFocused()
        compose.onNodeWithTag("settings.category.account").assertIsNotFocused()
        key("settings.item.account_devices", Key.DirectionLeft)
        compose.onNodeWithTag("settings.category.account").assertIsFocused()
    }

    @Test fun upFromAServiceStaysInContentWithoutABackButton() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.item.tracking").performClick()
        compose.onNodeWithTag("settings.item.trakt").performClick()
        compose.onNodeWithTag("test.form").assertIsFocused()
        key("test.form", Key.DirectionUp)
        repeat(4) { key("test.form", Key.DirectionUp) }
        compose.onNodeWithTag("test.form").assertIsFocused()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithTag("settings.category.connections").assertIsNotFocused()
    }

    @Test fun backFromService_restoresClickedService_thenParentCategory() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        compose.onNodeWithTag("settings.item.tracking").performClick()
        compose.onNodeWithTag("settings.item.trakt").performClick()
        compose.onNodeWithText("Form: Trakt").assertIsDisplayed()
        back()
        compose.onNodeWithTag("settings.item.trakt").assertIsFocused()
        back()
        compose.onNodeWithTag("settings.item.tracking").assertIsFocused()
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun rightColumnLeft_returnsToAdjacentCard_notAppRail() {
        show()
        compose.onNodeWithTag("settings.category.connections").performClick()
        focus("settings.item.tracking")
        key("settings.item.tracking", Key.DirectionLeft)
        compose.onNodeWithTag("settings.item.streaming").assertIsFocused()
    }

    @Test fun externalDestination_doesNotReplaceItsParentMenu() {
        var opened = ""
        show { opened = it; true }
        compose.onNodeWithTag("settings.category.library").performClick()
        compose.onNodeWithTag("settings.item.library_collections").performClick()
        compose.runOnIdle { assertEquals("collections", opened) }
        compose.onNodeWithTag("settings.menu.library").assertExists()
    }

    @Test fun returningFromExternalPage_restoresTheClickedRow() {
        val visible = mutableStateOf(true)
        show(visible) { visible.value = false; true }
        compose.onNodeWithTag("settings.category.library").performClick()
        compose.onNodeWithTag("settings.item.library_collections").performClick()
        compose.onNodeWithText("Return to menu").performClick()
        compose.runOnIdle { publishedEntry!!.requestFocus() }
        compose.onNodeWithTag("settings.item.library_collections").assertIsFocused()
    }

    @Test fun serviceShortcutBackReturnsToConnectionTestsBeforeItsCategory() {
        show()
        compose.onNodeWithTag("settings.category.advanced").performClick()
        compose.onNodeWithTag("settings.item.tests").performClick()
        compose.onNodeWithText("Form: tests").performClick()
        compose.onNodeWithText("Form: Trakt").assertIsDisplayed()
        back()
        compose.onNodeWithText("Form: tests").assertIsFocused()
        back()
        compose.onNodeWithTag("settings.item.tests").assertIsFocused()
        compose.onNodeWithTag("settings.category.account").assertIsNotFocused()
    }

    @Test fun firstLevelCardsHaveTheSameHeightAndColumnGapInEveryCategory() {
        show()
        var height: Float? = null
        var gap: Float? = null
        tvSettingsGroups.forEach { group ->
            compose.onNodeWithTag("settings.category.${group.id}").performClick()
            val first = compose.onNodeWithTag("settings.item.${group.children[0].id}").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("settings.item.${group.children[1].id}").fetchSemanticsNode().boundsInRoot
            height?.let { assertEquals(it, first.height, 1f) } ?: run { height = first.height }
            gap?.let { assertEquals(it, second.left - first.right, 1f) } ?: run { gap = second.left - first.right }
            assertEquals(first.height, second.height, 1f)
        }
    }

    @Test fun pickerBack_leavesPreferenceUnchanged() {
        var preference = "b"
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible) TvSettingsChoiceDialog(TvSettingsChoice("Choose", preference, listOf("a" to "Alpha", "b" to "Beta")) { preference = it }) { visible = false } } }
        compose.onNodeWithText("✓  Beta").assertIsFocused()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("b", preference) }
        compose.onNodeWithText("Choose").assertDoesNotExist()
    }

    @Test fun keyboardBack_restoresTheEditedField() {
        compose.setContent {
            MaterialTheme { Column {
                TextButton(onClick = {}) { Text("Before field") }
                com.torve.android.tv.components.TvClickToEditOutlinedTextField(
                    "", {}, { Text("Server URL") }, true, modifier = Modifier.testTag("test.edit"))
                TextButton(onClick = {}) { Text("After field") }
            } }
        }
        focus("test.edit")
        key("test.edit", Key.DirectionDown)
        compose.onNodeWithText("After field").assertIsFocused()
        focus("test.edit")
        key("test.edit", Key.DirectionCenter)
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("test.edit").assertIsFocused(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Before field").assertIsNotFocused()
    }
}
