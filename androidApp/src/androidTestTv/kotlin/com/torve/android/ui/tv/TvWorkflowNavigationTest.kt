package com.torve.android.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.remote.*
import com.torve.android.sync.model.TorveRemoteCommand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvWorkflowNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun node(text: String) = rule.onNode(hasText(text) and hasClickAction())
    private fun focused(text: String) { rule.waitUntil(5_000) { runCatching { node(text).assertIsFocused(); true }.getOrDefault(false) } }
    private fun press(text: String, key: Key) = node(text).performKeyInput { pressKey(key) }

    @Test fun dialogDismissAndAsyncLabelsKeepTheSameRemoteActionFocused() {
        var dialog by mutableStateOf(false)
        var busy by mutableStateOf(false)
        rule.setContent { MaterialTheme { TvWorkflowSurface("workflow_test") {
            val back = remember { FocusRequester() }; val origin = remember { FocusRequester() }
            TvWorkflowPage("Your library", "Test", {}, back, {}, toolbar = {
                TvWorkflowAction(if (busy) "Syncing…" else "Sync", { dialog = true }, requester = origin)
            }) { item { TvWorkflowAction("Series", {}) } }
            if (dialog) TvWorkflowDialog("Edit collection", { dialog = false }, origin) { TvWorkflowAction("Save", { dialog = false }) }
        } } }
        focused("Back"); press("Back", Key.DirectionDown); focused("Sync")
        press("Sync", Key.DirectionCenter); focused("Cancel")
        press("Cancel", Key.Back); focused("Sync")
        rule.runOnIdle { busy = true }; focused("Syncing…")
        rule.runOnIdle { busy = false }; focused("Sync")
    }

    @Test fun backMovesFromTheScrolledListToTheToolbarBeforeLeavingThePage() {
        var exited = 0
        rule.setContent { MaterialTheme { TvWorkflowSurface("long_test") {
            val entry = remember { FocusRequester() }
            TvWorkflowPage("Your shows", "Test", { exited++ }, entry, {}) {
                items((1..30).toList(), key = { it }) { TvWorkflowAction("Show $it", {}, Modifier.fillMaxWidth()) }
            }
        } } }
        focused("Back"); press("Back", Key.DirectionDown); focused("Show 1")
        repeat(8) { index -> press("Show ${index + 1}", Key.DirectionDown); focused("Show ${index + 2}") }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }; focused("Back")
        rule.runOnIdle { assertEquals(0, exited) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(1, exited) }
    }

    @Test fun removingTheLastFocusedEpisodeReturnsFocusToTheToolbar() {
        var rows by mutableStateOf(listOf("Last episode"))
        rule.setContent { MaterialTheme { TvWorkflowSurface("mutation_test") {
            val entry = remember { FocusRequester() }
            TvWorkflowPage("Your shows", "Test", {}, entry, {}) {
                items(rows, key = { it }) { TvWorkflowAction(it, { rows = emptyList() }, Modifier.fillMaxWidth()) }
            }
        } } }
        focused("Back"); press("Back", Key.DirectionDown); focused("Last episode")
        press("Last episode", Key.DirectionCenter); focused("Back")
    }

    @Test fun companionCommandsReachAnOpenDialogAndTransportCallbacks() {
        var open by mutableStateOf(true)
        var seeks = 0L; var pauses = 0; var stops = 0; var selections = 0
        rule.setContent { MaterialTheme {
            TorveRemoteInputSurface()
            TorveRemoteTransportSurface(TorveRemoteTransport({ pauses++ }, { stops++ }, { seeks += it }))
            if (open) TvWorkflowDialog("Remote test", { open = false }, null) { TvWorkflowAction("Action", { selections++ }) }
        } }
        focused("Cancel")
        rule.runOnIdle { assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.DOWN)) }
        focused("Action")
        rule.runOnIdle { assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.SELECT)) }
        rule.waitUntil(5_000) { selections == 1 }
        rule.runOnIdle { assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.SEEK_FORWARD, 10_000))
            assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.PLAY_PAUSE))
            assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.STOP)) }
        rule.runOnIdle { assertEquals(10_000L, seeks); assertEquals(1, pauses); assertEquals(1, stops)
            assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.BACK)) }
        rule.waitUntil(5_000) { !open }
    }

    @Test fun companionBackClosesAPopupWithItsOwnRemoteBackHandler() {
        var open by mutableStateOf(true)
        rule.setContent { MaterialTheme {
            TorveRemoteInputSurface()
            val requester = remember { FocusRequester() }
            if (open) TvDocumentModal("Connection details", requester, { open = false }) {
                TvWorkflowAction("Popup action", {}, requester = requester)
            }
        } }
        focused("Popup action")
        rule.runOnIdle { assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.BACK)) }
        rule.waitUntil(5_000) { !open }
    }
}
