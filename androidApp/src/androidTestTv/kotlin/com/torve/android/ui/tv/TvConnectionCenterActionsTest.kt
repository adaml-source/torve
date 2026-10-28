package com.torve.android.ui.tv

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvConnectionCenterAction
import com.torve.android.tv.components.TvConnectionCenterDialog
import com.torve.presentation.settings.IntegrationWorkflow
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.settings.buildIntegrationReadinessSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvConnectionCenterActionsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun traktOnlySyncRetainsRemoteFocusWhileBusyAndDoesNotStartTwice() {
        var settings by mutableStateOf(SettingsUiState(
            traktConnected = true, traktError = "Sync failed", simklConnected = true,
            traktPendingWrites = 3,
        ))
        val actions = mutableListOf<TvConnectionCenterAction>()
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                TvConnectionCenterDialog(
                    summary = buildIntegrationReadinessSummary(settings, emptyList()),
                    settings = settings,
                    onDismiss = { dismissed++ },
                    onOpenSettings = { error("A service sync must stay in the connection center") },
                    onAction = { action ->
                        actions += action
                        settings = settings.copy(traktSyncing = true)
                    },
                )
            }
        }
        enterTracking()
        waitForFocus("Retry Trakt sync")
        composeRule.onNodeWithText("Trakt needs attention. SIMKL connected.").assertExists()
        composeRule.onNodeWithText("3 changes waiting to upload").assertExists()
        saveScreenshot("connection-center-tracking-attention.png")
        press("Retry Trakt sync", Key.DirectionCenter)
        waitForFocus("Syncing Trakt…")
        press("Syncing Trakt…", Key.DirectionCenter)
        composeRule.runOnIdle { assertEquals(listOf(TvConnectionCenterAction.SYNC_TRAKT), actions) }
        composeRule.runOnIdle {
            settings = settings.copy(traktSyncing = false, traktError = null, traktPendingWrites = 0,
                traktLastSyncTime = System.currentTimeMillis())
        }
        waitForFocus("Sync Trakt")
        press("Sync Trakt", Key.DirectionLeft)
        workflowRow().assertIsFocused()
        workflowRow().performKeyInput { pressKey(Key.Back) }
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun repairAndSettingsActionsStayReachableBelowTheFoldWithTheRemote() {
        val actions = mutableListOf<TvConnectionCenterAction>()
        val settingsActions = mutableListOf<IntegrationWorkflow>()
        var settings by mutableStateOf(SettingsUiState(traktConnected = true, traktAccessToken = "test-account",
            simklConnected = true, simklError = "Connection failed"))
        composeRule.setContent {
            MaterialTheme {
                TvConnectionCenterDialog(
                    summary = buildIntegrationReadinessSummary(settings, emptyList()),
                    settings = settings,
                    onDismiss = {},
                    onOpenSettings = { settingsActions += it },
                    onAction = { actions += it },
                )
            }
        }
        enterTracking()
        waitForFocus("Sync Trakt")
        press("Sync Trakt", Key.DirectionDown)
        waitForFocus("Reconnect Trakt")
        composeRule.runOnIdle { settings = settings.copy(traktConnected = false) }
        waitForFocus("Reconnect Trakt")
        press("Reconnect Trakt", Key.DirectionCenter)
        press("Reconnect Trakt", Key.DirectionDown)
        waitForFocus("Reconnect SIMKL")
        press("Reconnect SIMKL", Key.DirectionCenter)
        press("Reconnect SIMKL", Key.DirectionDown)
        waitForFocus("Open tracking settings")
        composeRule.onNodeWithText("Open tracking settings").assertIsDisplayed()
        composeRule.onNodeWithText("No successful Trakt sync yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Trakt and SIMKL need attention.").assertIsDisplayed()
        saveScreenshot("connection-center-tracking-settings.png")
        press("Open tracking settings", Key.DirectionCenter)
        composeRule.runOnIdle {
            assertEquals(listOf(TvConnectionCenterAction.RECONNECT_TRAKT, TvConnectionCenterAction.CONNECT_SIMKL), actions)
            assertEquals(listOf(IntegrationWorkflow.TRACKING), settingsActions)
        }
        press("Open tracking settings", Key.DirectionUp)
        waitForFocus("Reconnect SIMKL")
        press("Reconnect SIMKL", Key.DirectionLeft)
        workflowRow().assertIsFocused()
    }

    private fun enterTracking() {
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNode(hasText("Watch now") and hasClickAction()).assertIsFocused(); true }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        var previous = "Watch now"
        for (next in listOf("My library", "Save to library", "Tracking and sync")) {
            composeRule.onNode(hasText(previous) and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.waitUntil(5_000) {
                runCatching { composeRule.onNode(hasText(next) and hasClickAction()).assertIsFocused(); true }.getOrDefault(false)
            }
            previous = next
        }
        workflowRow().assertIsFocused()
        workflowRow().performKeyInput { pressKey(Key.DirectionRight) }
    }

    private fun workflowRow() = composeRule.onNode(hasText("Tracking and sync") and hasClickAction())

    private fun press(label: String, key: Key) = composeRule.onNodeWithText(label).performKeyInput { pressKey(key) }

    private fun waitForFocus(label: String) {
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithText(label).assertIsFocused(); true }.getOrDefault(false)
        }
    }

    private fun saveScreenshot(name: String) {
        composeRule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
