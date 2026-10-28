package com.torve.android.ui.tv

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvWorkflowSurface
import com.torve.android.tv.screens.TvWatchingHubContent
import com.torve.android.ui.theme.TorveTheme
import com.torve.domain.model.*
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvOpenWorkflowsUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val today = LocalDate(2026,10,8)
    private fun episode(id: String = "12") = WatchingEpisode(MediaItem(id, tmdbId = id.toInt(), type = MediaType.SERIES, title = if (id == "12") "The Gentlemen" else "Welcome to Wrexham"),
        2, Episode(5, "Episode name"), today, 4, resume = true, positionMs = 1_260_000, progressPercent = .42f)
    private fun focused(tag: String) {
        try {
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            println("Focus failure for $tag:\n" + rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
            capture("tv-focus-failure")
            throw error
        }
    }
    private fun key(tag: String, key: Key) = rule.onNodeWithTag(tag).performKeyInput { pressKey(key) }
    private fun enterFirstEpisode() {
        rule.onNodeWithContentDescription("Back").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Up Next").performKeyInput { pressKey(Key.DirectionDown) }
        focused("watching_episode_12_5")
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "open-workflows-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun yourShowsIsOnlyInLibraryAndItsPageAcceptsRemoteFocus() {
        assertFalse(com.torve.android.tv.nav.tvTopDestinations.any { it.route == com.torve.android.tv.nav.TvRoutes.WATCHING_HUB })
        var opened by mutableStateOf(false)
        rule.setContent { TorveTheme {
            val rail = remember { androidx.compose.ui.focus.FocusRequester() }
            val tools = remember { androidx.compose.ui.focus.FocusRequester() }
            val tabs = remember { List(2) { androidx.compose.ui.focus.FocusRequester() } }
            val shows = remember { androidx.compose.ui.focus.FocusRequester() }
            if (!opened) com.torve.android.tv.components.TvLibraryHeader(listOf("Watchlist", "Favorites"), 0,
                tools, tabs, rail, {}, {}, {}, yourShowsRequester = shows, onYourShows = { opened = true })
            else TvWorkflowSurface("library-shows-entry") {
                TvWatchingHubContent(WatchingHubState(upNext = listOf(episode()), today = today), { opened = false }, {}, {}, {}, {})
            }
        } }
        rule.onNodeWithTag("library_tab_0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.onNodeWithTag("library_tab_0").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("library_your_shows").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithContentDescription("Back").assertIsFocused()
        enterFirstEpisode()
        focused("watching_episode_12_5")
    }

    @Test fun failedSchedulesShowRecoveryInsteadOfClaimingEverythingIsWatched() {
        rule.setContent { TorveTheme { TvWorkflowSurface("shows-error") {
            TvWatchingHubContent(WatchingHubState(today = today, errorKind = WatchingHubError.PARTIAL_REFRESH, incompleteShows = 2), {}, {}, {}, {}, {})
        } } }
        rule.onNodeWithText("Episode schedules unavailable").assertIsDisplayed()
        rule.onNodeWithText("You’re caught up").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").assertIsFocused()
        rule.onNodeWithContentDescription("Back").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Up Next").assertIsFocused()
        rule.onNodeWithText("Up Next").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Calendar").assertIsFocused()
        capture("tv-shows-schedule-unavailable")
    }
    @Test fun remoteMovesBetweenEpisodeAndSmallMenuAndBackRestoresEpisode() {
        var changes = 0
        rule.setContent { TorveTheme { TvWorkflowSurface("open_shows") { TvWatchingHubContent(WatchingHubState(upNext=listOf(episode()), today=today), {}, {}, {}, {}, { changes++ }) } } }
        enterFirstEpisode()
        rule.onNodeWithText("Resume at 21:00").assertIsDisplayed()
        rule.onNodeWithText("Unwatched aired episodes: 4").assertDoesNotExist()
        key("watching_episode_12_5", Key.DirectionRight); focused("watching_actions_12_5")
        capture("tv-next")
        key("watching_actions_12_5", Key.DirectionCenter); focused("watching_mark_confirm")
        capture("tv-episode-menu")
        key("watching_mark_confirm", Key.Back); focused("watching_episode_12_5")
        rule.runOnIdle { assertEquals(0, changes) }
    }
    @Test fun markingAnEpisodeRestoresTheNextRowAndKeepsNavigationWorking() {
        val next = episode()
        var state by mutableStateOf(WatchingHubState(upNext=listOf(next, episode("13")), today=today))
        var marked: WatchingEpisode? = null
        rule.setContent { TorveTheme { TvWorkflowSurface("open_mark") { TvWatchingHubContent(state, {}, {}, {}, {}, {
            marked=it; state=state.copy(upNext=state.upNext.filterNot { row -> row.show.id == it.show.id })
        }) } } }
        enterFirstEpisode()
        key("watching_episode_12_5",Key.DirectionRight)
        focused("watching_actions_12_5")
        key("watching_actions_12_5",Key.DirectionCenter)
        focused("watching_mark_confirm")
        rule.runOnIdle { assertNull(marked) }
        key("watching_mark_confirm",Key.DirectionCenter)
        focused("watching_episode_13_5")
        rule.runOnIdle { assertEquals(next,marked) }
        key("watching_episode_13_5", Key.DirectionRight); focused("watching_actions_13_5")
    }
    @Test fun primaryEpisodeActionKeepsItsExactResumeTarget() {
        var opened: WatchingEpisode? = null
        val next=episode()
        rule.setContent { TorveTheme { TvWorkflowSurface("open_target") { TvWatchingHubContent(WatchingHubState(upNext=listOf(next), today=today), {}, {opened=it}, {}, {}, {}) } } }
        enterFirstEpisode(); key("watching_episode_12_5",Key.DirectionCenter)
        rule.runOnIdle { assertEquals(next,opened); assertEquals(1_260_000L,opened?.positionMs) }
    }
    @Test fun germanToolbarAndSpoilerPlaceholdersAreTranslatedWithoutLosingFocus() {
        rule.setContent {
            val context=LocalContext.current
            val config=remember { Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.GERMAN)) } }
            val localized=remember { context.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                TorveTheme { TvWorkflowSurface("open_de") { TvWatchingHubContent(WatchingHubState(upNext=listOf(episode()), today=today, hideSpoilers=true), {}, {}, {}, {}, {}) } }
            }
        }
        rule.onNodeWithText("Deine Serien").assertIsDisplayed()
        rule.onNodeWithText("Als Nächstes").assertIsDisplayed()
        rule.onNodeWithText("Episode 5").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zurück").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Als Nächstes").performKeyInput { pressKey(Key.DirectionDown) }
        focused("watching_episode_12_5"); capture("tv-german")
    }
}
