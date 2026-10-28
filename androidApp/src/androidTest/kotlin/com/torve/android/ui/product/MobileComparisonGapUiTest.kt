package com.torve.android.ui.product

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTheme
import com.torve.android.ui.watchlist.MobileLibraryTabs
import com.torve.android.ui.settings.StatusRepairNavigationHeader
import com.torve.domain.model.*
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileComparisonGapUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val today = LocalDate(2026, 10, 8)
    private val next = WatchingEpisode(MediaItem("12", tmdbId = 12, type = MediaType.SERIES, title = "The Gentlemen"),
        2, Episode(5, "The secret reveal", "Story spoilers", "https://scene", "2026-10-08"), today, 4)
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(rule.activity.getExternalFilesDir(null), "comparison-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun providerRepairKeepsGeneralSettingsReachable() {
        var settingsOpened = false
        var backedOut = false
        rule.setContent { TorveTheme { StatusRepairNavigationHeader(
            onBack = { backedOut = true }, onOpenSettings = { settingsOpened = true },
        ) } }
        rule.onNodeWithContentDescription("Open settings").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(settingsOpened); assertFalse(backedOut) }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertTrue(backedOut) }
    }

    @Test fun yourShowsIsADirectLibraryDestinationAndKeepsTheSelectedCollection() {
        var selected by mutableIntStateOf(0)
        var opened = false
        rule.setContent { TorveTheme { MobileLibraryTabs(listOf("Watchlist", "Favorites", "Requests & downloads"),
            selected, { selected = it }, { opened = true }) } }
        rule.onNodeWithTag("library_your_shows").assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        rule.runOnIdle { assertTrue(opened); assertEquals(0, selected) }
        capture("library-your-shows")
        rule.onNodeWithTag("library_tab_1").performClick()
        rule.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun homeUpNextOpensTheExactEpisodeAndTheFullQueue() {
        var chosen: WatchingEpisode? = null
        var all = false
        rule.setContent { TorveTheme { MobileUpNextShelf(WatchingHubState(upNext = listOf(next), today = today), { chosen = it }, { all = true }) } }
        rule.onNodeWithTag("home_next_12_2_5").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(2, chosen?.season); assertEquals(5, chosen?.episode?.episodeNumber) }
        rule.onNodeWithText("Your shows").performClick()
        rule.runOnIdle { assertTrue(all) }
        capture("home-up-next")
    }
    @Test fun hiddenSpoilersCanBeRevealedAndTheOptionsDoNotReplaceTheEpisodeRow() {
        var state by mutableStateOf(WatchingHubState(upNext = listOf(next), today = today, hideSpoilers = true))
        rule.setContent { TorveTheme { MobileWatchingHubContent(state, {}, {}, {}, {}, onHideSpoilers = { state = state.copy(hideSpoilers = it) }) } }
        rule.onNodeWithText("S2 E5 · Episode 5").assertIsDisplayed()
        rule.onNodeWithText("S2 E5 · The secret reveal").assertDoesNotExist()
        rule.onNodeWithTag("shows_options").performClick()
        capture("episode-preferences")
        rule.onNodeWithTag("shows_spoilers").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("episode_12_2_5").assertIsDisplayed()
        rule.onNodeWithText("S2 E5 · The secret reveal").assertIsDisplayed()
    }
    @Test fun calendarDateRangesSeparateFutureReleasesAndWatchedHistory() {
        val aired = next.copy(episode = next.episode.copy(episodeNumber = 4), date = LocalDate(2026, 10, 7), watched = true)
        val future = next.copy(episode = next.episode.copy(episodeNumber = 6), date = LocalDate(2026, 10, 9))
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(calendar = listOf(aired, next, future), today = today),
            {}, {}, {}, { error("Watched history cannot be marked twice") }, initialCalendar = true) } }
        rule.onNodeWithTag("episode_12_2_4").assertDoesNotExist()
        rule.onNodeWithTag("episode_12_2_6").assertIsDisplayed()
        rule.onNodeWithTag("calendar_range_-7").performClick()
        rule.onNodeWithTag("episode_12_2_4").assertIsDisplayed()
        rule.onNodeWithTag("mark_12_2_4").assertIsNotEnabled()
        rule.onNodeWithTag("episode_12_2_6").assertDoesNotExist()
        capture("calendar-ranges")
    }
    @Test fun notificationDenialShowsAnActionableRepairAndAnUncheckedSwitch() {
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(today = today, episodeNotificationsEnabled = true),
            {}, {}, {}, {}, notificationPermissionMissing = true) } }
        rule.onNodeWithTag("shows_options").performClick()
        rule.onNodeWithTag("shows_alerts").assertIsOff()
        rule.onNodeWithText("Open Android settings").assertIsDisplayed()
        capture("notification-repair")
    }
}
