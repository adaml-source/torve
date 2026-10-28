package com.torve.android.ui.product

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.sync.model.TorveRemoteCommand
import com.torve.android.ui.components.*
import com.torve.android.ui.player.MobileDelayBar
import com.torve.android.ui.sync.MobileRemoteContent
import com.torve.android.ui.sync.RemoteTarget
import com.torve.android.ui.theme.TorveTheme
import com.torve.domain.model.*
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MobileProductQualityUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val today = LocalDate(2026, 10, 7)
    private fun episode(id: String = "236235", date: LocalDate = today, title: String = "The Gentlemen") = WatchingEpisode(
        MediaItem(id, tmdbId = id.toInt(), type = MediaType.SERIES, title = title), 2,
        Episode(5, "A Suburban A-Road on the Outskirts of Basingstoke"), date, remainingAired = 4)
    private fun capture(name: String) {
        rule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "product-quality-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun libraryToolsStayReachableWithLargeTextAndNavigateToTheirDestinations() {
        val opened = mutableListOf<String>()
        var backed = false
        rule.setContent { TorveTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.35f)) {
                MobileLibraryToolsScreen({ backed = true }, opened::add)
            }
        } }
        for ((label, route) in listOf("Catalog groups" to "catalog_groups", "Trakt lists" to "trakt_lists", "Household profiles" to "profiles")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals(route, opened.last()) }
        }
        capture("library-large-text")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertTrue(backed) }
    }

    @Test fun pageHeadingUsesReadableForegroundOnTheDarkBackground() {
        rule.setContent { TorveTheme { ProductPage("Library tools", onBack = {}) {} } }
        val pixels = rule.onNodeWithText("Library tools").captureToImage().toPixelMap()
        var brightPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].luminance() > .5f) brightPixels++
        }
        assertTrue("The heading must be legible on a dark page", brightPixels > 20)
    }

    @Test fun floatingBackKeepsItsOwnWidthAndRemainsAnAccessibleTouchTarget() {
        var backed = false
        rule.setContent { TorveTheme {
            Box(Modifier.fillMaxSize()) {
                FloatingBackButton({ backed = true }, Modifier.padding(12.dp).testTag("floating_back"))
            }
        } }
        val bounds = rule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertTrue("Back must not stretch across the page", bounds.width / density < 160f)
        assertTrue("Back keeps a 48 dp touch target", bounds.height / density >= 48f)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertTrue(backed) }
        capture("floating-back")
    }

    @Test fun recentSearchesOnlyOpenWithTheSearchFieldAndTypingRemainsAvailable() {
        var query by mutableStateOf("")
        var searches = 0
        rule.setContent { TorveTheme { ProductPage("Search") {
            MobileSearchField(query, { query = it }, { query = "" }, { searches++ }, listOf("München", "The Gentlemen"), {}, Modifier.padding(20.dp))
        } } }
        rule.onNodeWithText("Recent searches").assertDoesNotExist()
        rule.onNodeWithTag("mobile_search_input").performClick()
        rule.onNodeWithText("Recent searches").assertIsDisplayed()
        rule.onNodeWithTag("mobile_search_input").performTextInput("Arrival")
        rule.onNodeWithText("Recent searches").assertDoesNotExist()
        rule.onNodeWithTag("mobile_search_input").performImeAction()
        rule.runOnIdle { assertEquals("Arrival", query); assertEquals(1, searches) }
        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.onNodeWithTag("mobile_search_input").performClick()
        rule.onNodeWithText("The Gentlemen").performClick()
        rule.runOnIdle { assertEquals("The Gentlemen", query) }
        rule.onNodeWithText("Recent searches").assertDoesNotExist()
        capture("search")
    }

    @Test fun exactEpisodeEntryAndPendingWatchedActionKeepTheShowVisible() {
        val next = episode()
        var state by mutableStateOf(WatchingHubState(today = today, upNext = listOf(next)))
        var opened: WatchingEpisode? = null
        var marks = 0
        rule.setContent { TorveTheme { MobileWatchingHubContent(state, {}, { opened = it }, {}, {
            marks++
            state = state.copy(markingEpisodes = setOf("236235_2_5"))
        }) } }
        rule.onNodeWithTag("episode_236235_2_5").performClick()
        rule.runOnIdle { assertEquals(2, opened?.season); assertEquals(5, opened?.episode?.episodeNumber) }
        rule.onNodeWithTag("mark_236235_2_5").performClick()
        rule.runOnIdle { assertEquals(0, marks) }
        rule.onNodeWithTag("episode_mark_confirm").performClick()
        rule.onNodeWithTag("mark_236235_2_5").assertIsNotEnabled()
        rule.onNodeWithText("The Gentlemen").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, marks) }
        capture("your-shows")
    }

    @Test fun calendarUsesLocalDayHeadersAndKeepsFutureEpisodesOutOfWatchedActions() {
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(today = today,
            calendar = listOf(episode(), episode("20", LocalDate(2026, 10, 8), "Welcome to Wrexham"))), {}, {}, {}, {}, initialCalendar = true) } }
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithText("Tomorrow").assertIsDisplayed()
        rule.onNodeWithTag("mark_20_2_5").assertDoesNotExist()
        rule.onNodeWithTag("episode_20_2_5").assertIsDisplayed()
        capture("calendar")
    }

    @Test fun posterCellsAlignLongTitlesAndMissingRatingsAndKeepTypeArtworkAccessible() {
        var opened = 0
        rule.setContent { TorveTheme {
            CompositionLocalProvider(LocalRatingPrefs provides RatingDisplayPrefs(pillPosition = RatingPillPosition.OUTSIDE, maxRatingsOnCard = 2)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PosterCard(MediaItem("1", type = MediaType.MOVIE, title = "A long film title with München and São Paulo", year = 2026,
                        ratings = MediaRatings(imdbScore = 8.1f)), { opened++ }, Modifier.width(132.dp).testTag("long_card"),
                        cardStyle = CardStyle().let { it.copy(appearance = it.appearance.copy(showTypeBadge = true)) }, fillCell = false)
                    PosterCard(MediaItem("2", type = MediaType.MOVIE, title = "Arrival"), { opened++ }, Modifier.width(132.dp).testTag("short_card"), fillCell = false)
                }
            }
        } }
        val first = rule.onNodeWithTag("long_card").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("short_card").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, second.height, 1f)
        rule.onNodeWithText("A long film title with München and São Paulo").performClick()
        rule.runOnIdle { assertEquals(1, opened) }
        rule.onNodeWithContentDescription("Movie", useUnmergedTree = true).assertExists()
        capture("poster-captions")
    }

    @Test fun remoteRequiresATargetAndBlocksAnotherCommandWhilePending() {
        var selected by mutableStateOf<String?>(null)
        var busy by mutableStateOf(false)
        val commands = mutableListOf<TorveRemoteCommand>()
        rule.setContent { TorveTheme { MobileRemoteContent(listOf(RemoteTarget("tv:1", "Living room", tvId = "1")), selected,
            ready = selected != null, busy = busy, seekSeconds = 10, error = null, onBack = {}, onSelect = { selected = it },
            onCommand = { commands += it; busy = true }, onConnect = {}, onSeekStep = {}, onDevices = {}) } }
        rule.onNodeWithTag("remote_UP").assertIsNotEnabled()
        rule.onNodeWithTag("remote_target").performClick()
        rule.onNodeWithText("Living room").performClick()
        rule.onNodeWithTag("remote_UP").assertIsEnabled().performClick().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf(TorveRemoteCommand.UP), commands); busy = false }
        rule.onNodeWithTag("remote_PLAY_PAUSE").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(TorveRemoteCommand.PLAY_PAUSE, commands.last()); busy = false }
        capture("remote")
    }

    @Test fun serviceSyncRemainsIndependentAndKeepsTheBusyActionInPlace() {
        var traktBusy by mutableStateOf(false)
        var trakt = 0
        var simkl = 0
        rule.setContent { TorveTheme { ProductPage("Connection Center", onBack = {}) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MobileSyncStatusCard("Trakt", true, traktBusy, 1_791_324_000_000L, 2, "Saved changes are waiting for a connection.", { trakt++; traktBusy = true }, {}, "Watch history and personal lists.")
                MobileSyncStatusCard("SIMKL", true, false, null, 0, null, { simkl++ }, {}, "Movies, shows and anime.")
            }
        } } }
        rule.onNodeWithTag("sync_action_Trakt").performClick().assertIsNotEnabled()
        rule.onNodeWithTag("sync_action_SIMKL").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, trakt); assertEquals(1, simkl) }
        capture("sync-status")
    }

    @Test fun delayPreviewUses50MillisecondsAndSaveCommitsWithoutRestoringTheOldValue() {
        var open by mutableStateOf(true)
        var preview = 600
        var saved = 600
        rule.setContent { TorveTheme { Box(Modifier.fillMaxSize().background(Color(0xFF263E4A))) {
            Text("Video remains visible", Modifier.align(Alignment.Center), color = Color.White)
            if (open) MobileDelayBar("Audio delay", 600, { preview = it }, { saved = it; open = false }, { open = false })
        } } }
        rule.onNodeWithTag("delay_plus").performClick()
        rule.runOnIdle { assertEquals(650, preview) }
        val bar = rule.onNodeWithTag("mobile_delay_bar").fetchSemanticsNode().boundsInRoot
        assertTrue("The overlay must stay compact", bar.height <= 112 * rule.activity.resources.displayMetrics.density)
        capture("delay")
        rule.onNodeWithTag("delay_save").performClick()
        rule.runOnIdle { assertEquals(650, saved); assertEquals(650, preview) }
    }

    @Test fun closingSubtitleDelayRestoresTheSavedOffsetWithoutChangingIt() {
        var open by mutableStateOf(true)
        var preview = 0
        var saves = 0
        rule.setContent { TorveTheme { if (open) MobileDelayBar("Subtitle delay", 0, { preview = it }, { saves++ }, { open = false }) } }
        rule.onNodeWithTag("delay_minus").performClick()
        rule.runOnIdle { assertEquals(-50, preview) }
        rule.onNodeWithTag("delay_close").performClick()
        rule.runOnIdle { assertEquals(0, preview); assertEquals(0, saves) }
    }

    @Test fun groupEditorValidatesANameAndRetainsUnavailableCatalogSelections() {
        var saved: CatalogGroup? = null
        rule.setContent { TorveTheme { MobileCatalogGroupEditor(CatalogGroup("1", "", listOf("missing")),
            listOf(CatalogGroupSource("available", "New releases", emptyList())), false, null, {}, { saved = it }) } }
        rule.onNodeWithText("Save group").assertIsNotEnabled()
        rule.onNodeWithTag("group_name").performTextInput("Family films")
        rule.onNodeWithText("New releases").performClick()
        rule.onNodeWithText("Save group").performClick()
        rule.runOnIdle { assertEquals("Family films", saved?.name); assertEquals(setOf("missing", "available"), saved?.catalogIds?.toSet()) }
        capture("catalog-editor")
    }
}
