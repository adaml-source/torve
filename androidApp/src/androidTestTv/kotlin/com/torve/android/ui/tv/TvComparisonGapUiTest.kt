package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.screens.TvWatchingHubContent
import com.torve.domain.model.*
import com.torve.domain.providerhealth.*
import com.torve.presentation.settings.*
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvComparisonGapUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun action(label: String) = (if (label == "Back") hasContentDescription(label) else hasText(label)) and hasClickAction()
    private fun key(label: String, key: Key) = rule.onNode(action(label)).performKeyInput { pressKey(key) }
    private fun focused(label: String) { rule.waitUntil(5_000) { rule.onAllNodes(action(label) and isFocused()).fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(rule.activity.getExternalFilesDir(null), "comparison-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun episodePreferencesStartOnTheUsefulActionAndRestoreTheirInvokingButton() {
        val today = LocalDate(2026, 10, 8)
        val next = WatchingEpisode(MediaItem("12", tmdbId = 12, type = MediaType.SERIES, title = "The Gentlemen"), 2,
            Episode(5, "The secret reveal"), today, 4)
        var state by mutableStateOf(WatchingHubState(upNext = listOf(next), today = today))
        var opened: WatchingEpisode? = null
        rule.setContent { MaterialTheme { TvWorkflowSurface("comparison") { TvWatchingHubContent(state, {}, { opened = it }, {}, {}, {},
            onHideSpoilers = { state = state.copy(hideSpoilers = it) }) } } }
        focused("Back")
        key("Back", Key.DirectionDown); focused("Up Next")
        key("Up Next", Key.DirectionRight); focused("Calendar")
        key("Calendar", Key.DirectionRight); focused("Refresh")
        key("Refresh", Key.DirectionRight); focused("View options")
        key("View options", Key.DirectionCenter); focused("Hide unwatched spoilers")
        capture("tv-episode-options")
        key("Hide unwatched spoilers", Key.DirectionCenter); focused("View options")
        rule.onNodeWithText("Episode 5").assertIsDisplayed()
        rule.onNodeWithText("The secret reveal").assertDoesNotExist()
        key("View options", Key.DirectionCenter); focused("Show unwatched episode details")
        key("Show unwatched episode details", Key.Back); focused("View options")
        key("View options", Key.DirectionDown)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("watching_episode_12_5") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("watching_episode_12_5").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(next, opened) }
    }
    @Test fun streamingSetupOffersSourcesAddonsAndReceiveWithPredictableRemoteNavigation() {
        val actions = mutableListOf<TvConnectionCenterAction>()
        rule.setContent { MaterialTheme { TvConnectionCenterDialog(buildIntegrationReadinessSummary(SettingsUiState(), emptyList()),
            SettingsUiState(), {}, {}, actions::add) } }
        focused("Watch now")
        key("Watch now", Key.DirectionRight); focused("Streaming sources")
        capture("tv-connection-center")
        key("Streaming sources", Key.DirectionCenter)
        key("Streaming sources", Key.DirectionDown); focused("Installed add-ons")
        key("Installed add-ons", Key.DirectionCenter)
        key("Installed add-ons", Key.DirectionDown); focused("Receive from another device")
        key("Receive from another device", Key.DirectionCenter)
        key("Receive from another device", Key.DirectionLeft); focused("Watch now")
        rule.runOnIdle { assertEquals(listOf(TvConnectionCenterAction.OPEN_STREAMING_SETUP, TvConnectionCenterAction.OPEN_ADDONS,
            TvConnectionCenterAction.RECEIVE_CREDENTIALS), actions) }
    }
    @Test fun aFailedHealthCheckIsVisibleAndItsRepairRemainsReachable() {
        val settings = SettingsUiState(seerrServerUrl = "https://requests.example", seerrApiKey = "stored", seerrConnected = true)
        val summary = buildIntegrationReadinessSummary(settings, emptyList(), listOf(ProviderHealthEntry(
            ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager", "Seerr", ProviderHealthStatus.RED, 20)))
        var opened: IntegrationWorkflow? = null
        rule.setContent { MaterialTheme { TvConnectionCenterDialog(summary, settings, {}, { opened = it }) } }
        focused("Watch now")
        key("Watch now", Key.DirectionDown); focused("My library")
        key("My library", Key.DirectionDown); focused("Save to library")
        rule.onNodeWithText("Needs attention").assertIsDisplayed()
        key("Save to library", Key.DirectionRight); focused("Open library request settings")
        rule.onNodeWithText("Seerr is unreachable or needs its credentials checked. Open library request settings and select Save and test.").assertIsDisplayed()
        rule.onNodeWithText("Connection test: needs attention.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Connection test: passed.", substring = true).assertDoesNotExist()
        capture("tv-connection-health")
        key("Open library request settings", Key.DirectionCenter)
        rule.runOnIdle { assertEquals(IntegrationWorkflow.SAVE_TO_LIBRARY, opened) }
    }
}
