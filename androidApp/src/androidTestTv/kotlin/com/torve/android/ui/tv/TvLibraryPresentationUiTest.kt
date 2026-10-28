package com.torve.android.ui.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.remote.TorveRemoteInput
import com.torve.android.remote.TorveRemoteInputSurface
import com.torve.android.sync.model.TorveRemoteCommand
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.screens.TvWatchingHubContent
import com.torve.android.ui.theme.Charcoal
import com.torve.domain.model.*
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TvLibraryPresentationUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val today = LocalDate(2026, 10, 7)
    private fun focusedTag(tag: String) { rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun focusText(label: String) { rule.waitUntil(5_000) { runCatching { rule.onNode(hasText(label) and isFocused()).assertExists(); true }.getOrDefault(false) } }
    private fun key(key: Key, tag: String? = null) {
        val target = if (tag == null) rule.onNode(isFocused()) else rule.onNodeWithTag(tag)
        target.performKeyInput { pressKey(key) }; rule.waitForIdle()
    }
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    private fun sample(id: Int, name: String, episode: Int = 5, date: LocalDate = today) = WatchingEpisode(
        MediaItem(id = id.toString(), tmdbId = id, type = MediaType.SERIES, title = name), 2,
        Episode(episode, "A long episode name that remains readable without pushing the row out of alignment"), date,
        remainingAired = 4, resume = id == 2)
    private fun sampleState() = WatchingHubState(today = today, upNext = listOf(sample(1, "The Gentlemen"), sample(2, "Welcome to Wrexham"),
        sample(3, "The Simpsons"), sample(4, "The Big Bang Theory")), calendar = listOf(sample(1, "The Gentlemen"), sample(5, "New series", date = LocalDate(2026, 10, 8))))

    @Composable private fun headerMenu(onSelected: (TvLibraryTool) -> Unit = {}, onShows: () -> Unit = {}) {
        var open by remember { mutableStateOf(false) }
        val tools = remember { FocusRequester() }
        val tabs = remember { List(4) { FocusRequester() } }
        val shows = remember { FocusRequester() }
        TorveRemoteInputSurface()
        Box(Modifier.fillMaxSize().background(Charcoal)) {
            TvLibraryHeader(listOf("Watchlist", "Favorites", "Requests & downloads", "VOD"), 0, tools, tabs, tabs[0],
                onTools = { open = true }, onTab = {}, onFocused = {}, yourShowsRequester = shows, onYourShows = onShows)
        }
        LaunchedEffect(open) { if (!open) { withFrameNanos { }; tools.requestFocus() } }
        if (open) TvLibraryToolsMenu(TvLibraryTool.entries, { open = false; onSelected(it) }, { open = false })
    }

    @Test fun libraryToolsMatchesTheTabsAndReturnsFocusAfterBack() {
        rule.setContent { MaterialTheme { headerMenu() } }
        focusedTag("library_tools_button")
        val tools = rule.onNodeWithTag("library_tools_button").fetchSemanticsNode().boundsInRoot
        val tab = rule.onNodeWithTag("library_tab_0").fetchSemanticsNode().boundsInRoot
        assertEquals("The action must share the tab baseline", tab.top, tools.top, 1f)
        assertEquals("The action must be as compact as the tabs", tab.height, tools.height, 1f)
        key(Key.DirectionRight); focusedTag("library_tab_0")
        key(Key.DirectionLeft); focusedTag("library_tools_button")
        key(Key.DirectionCenter); focusedTag("library_tool_catalogs")
        back(); focusedTag("library_tools_button")
    }

    @Test fun compactMenuHasAlignedRowsAndAllDestinationsAreReachable() {
        var selected: TvLibraryTool? = null
        rule.setContent { MaterialTheme { headerMenu(onSelected = { selected = it }) } }
        focusedTag("library_tools_button"); key(Key.DirectionCenter); focusedTag("library_tool_catalogs")
        val rows = TvLibraryTool.entries.map { rule.onNodeWithTag("library_tool_${it.name.lowercase()}").fetchSemanticsNode().layoutInfo }
        // Compare measured row sizes; the focused row intentionally scales visually.
        for (row in rows) { assertEquals(rows.first().width, row.width); assertEquals(rows.first().height, row.height) }
        capture("library-tools-menu-polished.png")
        var current = "catalogs"
        for (destination in listOf("trakt", "profiles")) {
            key(Key.DirectionDown, "library_tool_$current"); focusedTag("library_tool_$destination"); current = destination
        }
        key(Key.DirectionCenter, "library_tool_profiles")
        rule.runOnIdle { assertEquals(TvLibraryTool.PROFILES, selected) }
    }

    @Test fun pairedPhoneCanNavigateTheMenuAndCloseItWithoutLosingTheHeader() {
        rule.setContent { MaterialTheme { headerMenu() } }
        focusedTag("library_tools_button"); key(Key.DirectionCenter); focusedTag("library_tool_catalogs")
        rule.waitUntil(5_000) { TorveRemoteInput.hasForegroundSurface() }
        rule.runOnIdle { assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.DOWN)) }; focusedTag("library_tool_trakt")
        rule.runOnIdle { assertTrue(TorveRemoteInput.dispatch(TorveRemoteCommand.BACK)) }; focusedTag("library_tools_button")
    }

    @Test fun episodeArtworkOpensTheExactEpisodeAndWatchedMutationKeepsFocus() {
        var state by mutableStateOf(sampleState())
        var opened: WatchingEpisode? = null
        rule.setContent { MaterialTheme { TvWorkflowSurface("watching_presentation") {
            TvWatchingHubContent(state, {}, { opened = it }, {}, {}, { next ->
                state = state.copy(upNext = state.upNext.map { if (it.show.id == next.show.id) it.copy(episode = it.episode.copy(episodeNumber = 6), remainingAired = 3) else it })
            })
        } } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithContentDescription("Back").assertIsFocused(); true }.getOrDefault(false) }
        key(Key.DirectionDown); focusText("Up Next")
        key(Key.DirectionDown); focusedTag("watching_episode_1_5")
        key(Key.DirectionCenter); rule.runOnIdle { assertEquals(2, opened?.season); assertEquals(5, opened?.episode?.episodeNumber) }
        key(Key.DirectionRight); focusedTag("watching_actions_1_5")
        key(Key.DirectionCenter); focusedTag("watching_mark_confirm")
        key(Key.DirectionCenter, "watching_mark_confirm"); focusedTag("watching_episode_1_6")
        key(Key.DirectionRight); focusedTag("watching_actions_1_6")
        key(Key.DirectionCenter); focusedTag("watching_mark_confirm")
        rule.onNodeWithText(rule.activity.getString(com.torve.android.R.string.flow_aired_remaining, 3)).assertExists()
        key(Key.Back, "watching_mark_confirm"); focusedTag("watching_episode_1_6")
    }

    @Test fun yourShowsIsBesideWatchlistAndCanOpenWithoutUsingTools() {
        var opened = false
        rule.setContent { MaterialTheme { headerMenu(onShows = { opened = true }) } }
        focusedTag("library_tools_button")
        key(Key.DirectionRight); focusedTag("library_tab_0")
        key(Key.DirectionRight); focusedTag("library_your_shows")
        val shows = rule.onNodeWithTag("library_your_shows").fetchSemanticsNode().boundsInRoot
        val watchlist = rule.onNodeWithTag("library_tab_0").fetchSemanticsNode().boundsInRoot
        assertEquals(watchlist.top, shows.top, 1f)
        val showsBounds = rule.onNodeWithTag("library_your_shows").getUnclippedBoundsInRoot()
        val watchlistBounds = rule.onNodeWithTag("library_tab_0").getUnclippedBoundsInRoot()
        assertEquals("Your shows must use the shared pill spacing", 12f, (showsBounds.left - watchlistBounds.right).value, .5f)
        key(Key.DirectionCenter); rule.runOnIdle { assertTrue(opened) }
        key(Key.DirectionRight); focusedTag("library_tab_1")
        key(Key.DirectionLeft); focusedTag("library_your_shows")
        key(Key.DirectionLeft); focusedTag("library_tab_0")
        capture("library-your-shows-direct.png")
    }

    @Test fun calendarGroupsLocalDatesAndPreventsMarkingFutureEpisodesWatched() {
        rule.setContent { MaterialTheme { TvWorkflowSurface("calendar_presentation") {
            TvWatchingHubContent(sampleState(), {}, {}, {}, {}, {})
        } } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithContentDescription("Back").assertIsFocused(); true }.getOrDefault(false) }
        key(Key.DirectionDown); focusText("Up Next")
        key(Key.DirectionRight); focusText("Calendar")
        key(Key.DirectionCenter); focusText("Calendar")
        rule.onNodeWithText("Today").assertIsDisplayed(); rule.onNodeWithText("Tomorrow").assertIsDisplayed()
        rule.onNodeWithTag("watching_actions_1_5").assertExists()
        rule.onNodeWithTag("watching_actions_5_5").assertDoesNotExist()
        capture("your-shows-calendar-polished.png")
    }

    @Test fun fourArtworkRowsFitAndBackgroundRefreshDoesNotStealFocus() {
        var state by mutableStateOf(sampleState())
        rule.setContent { MaterialTheme { TvWorkflowSurface("refresh_presentation") {
            TvWatchingHubContent(state, {}, {}, {}, {}, {})
        } } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithContentDescription("Back").assertIsFocused(); true }.getOrDefault(false) }
        capture("your-shows-up-next-polished.png")
        rule.onNodeWithTag("watching_artwork_1_5", useUnmergedTree = true).assertIsDisplayed()
        val lastRow = rule.onNodeWithTag("watching_episode_4_5").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The fourth episode row must fit completely on screen", lastRow.height > 0f && lastRow.bottom <= screen.bottom)
        key(Key.DirectionDown); focusText("Up Next")
        key(Key.DirectionRight); focusText("Calendar")
        key(Key.DirectionRight); focusText("Refresh")
        rule.runOnIdle { state = state.copy(loading = true) }; focusText("Refresh")
        rule.onNodeWithText("Pick up where you left off.").assertExists()
        rule.runOnIdle { state = state.copy(loading = false) }; focusText("Refresh")
        capture("your-shows-up-next-polished.png")
        key(Key.DirectionDown)
        back()
        rule.onNodeWithContentDescription("Back").assertIsFocused()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
