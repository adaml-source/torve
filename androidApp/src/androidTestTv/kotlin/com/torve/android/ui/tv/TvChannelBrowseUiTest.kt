package com.torve.android.ui.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.TvScaffold
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import com.torve.android.tv.screens.TvEpgGrid
import com.torve.android.tv.screens.TvGuideProgrammeActionOverlay
import com.torve.android.tv.screens.TvIptvScreen
import com.torve.android.tv.screens.TvIptvRailState
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.PlaylistType
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.stableChannelId
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class TvChannelBrowseUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()
    private val start = (System.currentTimeMillis() / 1_800_000L) * 1_800_000L
    private fun channel(name: String, playlist: String = "qa-guide") = Channel(name,
        "https://example.test/$playlist/${name.replace(' ', '-')}", tvgId = name,
        groupTitle = "News", playlistId = playlist, contentType = ChannelContentType.LIVE)
    private val news = channel("News One")
    private val current = EpgProgramme("News One", start - 60_000L, start + 1_800_000L, "On now")
    private val future = EpgProgramme("News One", start + 1_800_000L, start + 7_200_000L, "Next match")

    @Test fun fiveAndSixDigitChannelNumbersStayCompleteOnOneLineInListAndGuide() {
        var guide by mutableStateOf(false)
        var textScale by androidx.compose.runtime.mutableFloatStateOf(1f)
        val channels = listOf(32143, 123456).map { number -> EnrichedChannel(news.copy(name = "Channel $number", url = "https://example.test/number-$number", channelNumber = number)) }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, textScale)) {
                MaterialTheme { grid(channels, emptyList(), showGuide = guide) }
            }
        }
        for (mode in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
            composeRule.runOnIdle { guide = mode; textScale = scale }
            settle()
            for (number in listOf(32143, 123456)) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                composeRule.onNodeWithTag("channel_number:$number", useUnmergedTree = true)
                    .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertEquals("Channel number wrapped in guide=$mode, fontScale=$scale", 1, results.single().lineCount)
                val layout = results.single()
                val last = layout.getBoundingBox(number.toString().lastIndex)
                val report = "number=$number guide=$mode fontScale=$scale size=${layout.size} " +
                    "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                    "lastGlyph=$last lineRight=${layout.getLineRight(0)} lineBottom=${layout.getLineBottom(0)}"
                java.io.File(composeRule.activity.getExternalFilesDir(null), "catalog-channel-number-layout.txt").appendText(report + "\n")
                assertTrue("Channel number clipped: $report", last.right <= layout.size.width + 1f && last.bottom <= layout.size.height + 1f)
            }
        }
    }

    @Test fun futureProgrammeOpensDetailsWithoutTuningLiveChannel() {
        var played = 0
        var actions = 0
        composeRule.setContent {
            TvTestSize { grid(listOf(EnrichedChannel(news)), listOf(current, future), col = 1,
                onPlay = { played++ }, onActions = { _, programme -> assertEquals(future, programme); actions++ }) }
        }
        settle()
        composeRule.onNode(hasText("Next match") and hasClickAction()).assertIsFocused()
        key("Next match", Key.DirectionCenter)
        composeRule.runOnIdle { assertEquals(0, played); assertEquals(1, actions) }
    }

    @Test fun currentProgrammePlaysOnceAndTwoHourTimelineFitsInsideTheViewport() {
        var played = 0
        composeRule.setContent { TvTestSize {
            grid(listOf(EnrichedChannel(news)), listOf(current, future), onPlay = { played++ })
        } }
        settle()
        val lastSlot = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start + 5_400_000L))
        composeRule.onNodeWithText(lastSlot).assertIsDisplayed()
        val header = composeRule.onNodeWithText(lastSlot).fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("guide").fetchSemanticsNode().boundsInRoot
        assertTrue("The last half-hour must fit in the guide", header.right <= viewport.right)
        key("On now", Key.DirectionCenter)
        composeRule.runOnIdle { assertEquals(1, played) }
    }

    @Test fun compactListCentersMiddleRowsAndStopsAtBothEndsWithoutGaps() {
        assertChannelScrollBounds(showGuide = false)
    }

    @Test fun currentTimeStaysOnTheTimelineAndDisappearsWhenPagingAway() {
        var window by androidx.compose.runtime.mutableLongStateOf(start)
        composeRule.setContent { TvTestSize {
            grid(listOf(EnrichedChannel(news)), listOf(current, future), window = window)
        } }
        settle()
        val timeline = composeRule.onNodeWithTag("epg_timeline").getUnclippedBoundsInRoot()
        val time = composeRule.onNodeWithTag("epg_current_time").assertIsDisplayed().getUnclippedBoundsInRoot()
        val rows = composeRule.onNodeWithTag("channels-row-viewport").getUnclippedBoundsInRoot()
        val line = composeRule.onNodeWithTag("epg_current_time_line").getUnclippedBoundsInRoot()
        assertTrue("Current time must fit inside the time ruler", time.top >= timeline.top && time.bottom <= timeline.bottom)
        assertTrue("The badge must not cover the first programme row", time.bottom <= rows.top)
        assertTrue("Current time must fit horizontally inside its timeline", time.left >= timeline.left && time.right <= timeline.right)
        assertTrue("The thin line must begin at the first channel row", abs((line.top - rows.top).value) <= 1f)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(composeRule.activity.getExternalFilesDir(null), "comparison-epg-timeline-time.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        composeRule.runOnIdle { window = start + 7_200_000L }
        composeRule.onNodeWithTag("epg_current_time").assertDoesNotExist()
        composeRule.onNodeWithTag("epg_current_time_line").assertDoesNotExist()
        composeRule.runOnIdle { window = start - 7_200_000L }
        composeRule.onNodeWithTag("epg_current_time").assertDoesNotExist()
        composeRule.onNodeWithTag("epg_current_time_line").assertDoesNotExist()
    }

    @Test fun programmeGuideCentersMiddleRowsAndStopsAtBothEndsWithoutGaps() {
        assertChannelScrollBounds(showGuide = true)
    }

    private fun assertChannelScrollBounds(showGuide: Boolean) {
        var row by mutableIntStateOf(0)
        var token by mutableIntStateOf(0)
        val channels = (1..24).map { EnrichedChannel(channel("News ${it.toString().padStart(2, '0')}")) }
        fun label(index: Int) = if (showGuide) "Schedule unavailable" else "News ${index.toString().padStart(2, '0')}"
        composeRule.setContent { TvTestSize {
            grid(channels, emptyList(), row = row, token = token, showGuide = showGuide,
                onMove = { row = (row + it).coerceIn(0, channels.lastIndex); token++ })
        } }
        settle()
        val rows = composeRule.onNodeWithTag("channels-row-viewport").getUnclippedBoundsInRoot()
        val firstRow = composeRule.onNode(hasText(label(1)) and isFocused() and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("The first channel must start at the top without centre padding", abs((firstRow.top - rows.top).value) <= 4f)
        repeat(8) { index -> key(label(index + 1), Key.DirectionDown) }
        val selected = composeRule.onNode(hasText(label(9)) and isFocused() and hasClickAction())
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        composeRule.runOnIdle { assertEquals(8, row) }
        assertTrue(selected.top >= rows.top && selected.bottom <= rows.bottom)
        assertTrue("Scrolling channels must hold focus in the middle of the list",
            abs(((selected.top + selected.bottom - rows.top - rows.bottom) / 2).value) <= 4f)
        key(label(9), Key.DirectionUp)
        composeRule.runOnIdle { assertEquals(7, row) }
        repeat(16) { index -> key(label(index + 8), Key.DirectionDown) }
        composeRule.runOnIdle { assertEquals(23, row) }
        val finalRow = composeRule.onNode(hasText(label(24)) and isFocused() and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("The last channel must reach the bottom without centre padding", abs((rows.bottom - finalRow.bottom).value) <= 4f)
        repeat(23) { index -> key(label(24 - index), Key.DirectionUp) }
        val returnedFirstRow = composeRule.onNode(hasText(label(1)) and isFocused() and hasClickAction()).getUnclippedBoundsInRoot()
        composeRule.runOnIdle { assertEquals(0, row) }
        assertTrue("Scrolling back to the first channel must remove the top gap", abs((returnedFirstRow.top - rows.top).value) <= 4f)
    }

    @Test fun shortChannelListStartsAtTopEvenWhenTheLastChannelIsFocused() {
        composeRule.setContent { TvTestSize {
            grid(listOf(EnrichedChannel(news), EnrichedChannel(channel("News Two"))), emptyList(), row = 1, showGuide = false)
        } }
        settle()
        composeRule.onNode(hasText("News Two") and hasClickAction()).assertIsFocused()
        val rows = composeRule.onNodeWithTag("channels-row-viewport").getUnclippedBoundsInRoot()
        val firstRow = composeRule.onNode(hasText("News One") and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("A short list must not shift down to centre its selected channel", abs((firstRow.top - rows.top).value) <= 4f)
    }

    @Test fun favoriteActionDoesNotStartPlaybackAndClosingMenuRestoresChannelFocus() {
        var showActions by mutableStateOf(false)
        var favorite by mutableStateOf(false)
        var token by mutableIntStateOf(0)
        var played = 0
        composeRule.setContent { TvTestSize {
            Box(Modifier.width(620.dp).height(380.dp)) {
                grid(listOf(EnrichedChannel(news)), emptyList(), token = token, showGuide = false,
                    onPlay = { played++ }, onActions = { _, _ -> showActions = true })
                if (showActions) TvGuideProgrammeActionOverlay(news, null, false,
                    onWatchLive = { played++ }, onStartFromBeginning = {}, isFavorite = favorite,
                    onToggleFavorite = { favorite = !favorite }, onDismiss = { showActions = false; token++ })
            }
        } }
        settle()
        key("News One", Key.Menu)
        key("Watch channel", Key.DirectionDown)
        composeRule.onNodeWithText("Add to Favorites").assertIsFocused()
        key("Add to Favorites", Key.DirectionCenter)
        composeRule.onNodeWithText("Remove from Favorites").assertIsFocused()
        key("Remove from Favorites", Key.Back)
        composeRule.onNodeWithText("News One").assertIsFocused()
        composeRule.runOnIdle { assertTrue(favorite); assertEquals(0, played) }
    }

    @Test fun browserEntersSavedGroupAndChannelAndBackReturnsToGroupsThenNavigation() {
        val vm = browser()
        composeRule.waitUntil(10_000) { vm.state.value.selectedChannel?.name == "News Two" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("News").assertIsFocused()
        val narrowBounds = composeRule.onNodeWithTag("channels-row-viewport").getUnclippedBoundsInRoot()
        key("News", Key.DirectionRight)
        composeRule.onNodeWithTag("channels-groups").assertDoesNotExist()
        val wideBounds = composeRule.onNodeWithTag("channels-row-viewport").getUnclippedBoundsInRoot()
        assertTrue("Hiding both sidebars must give the channels substantial space",
            (wideBounds.right - wideBounds.left) - (narrowBounds.right - narrowBounds.left) > 200.dp)
        composeRule.runOnIdle { assertTrue(TvIptvRailState.hideRail.value) }
        composeRule.onNode(hasText("News Two") and hasClickAction()).assertIsFocused()
        key("News Two", Key.Back)
        composeRule.onNodeWithText("News").assertIsFocused()
        composeRule.onNodeWithTag("channels-groups").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!TvIptvRailState.hideRail.value) }
        key("News", Key.Back)
        composeRule.onNodeWithText("Navigation").assertIsFocused()
    }

    @Test fun collapsedMainMenuBackRestoresAnOffscreenGroupAcrossRepeatedPanelAnimations() {
        browser(withExtraGroups = true, withCollapsingNavigation = true)
        key("News", Key.DirectionDown)
        repeat(16) { index -> key("Other ${(index + 1).toString().padStart(2, '0')}", Key.DirectionDown) }
        composeRule.onNodeWithText("Other 17").assertIsFocused()
        repeat(4) {
            key("Other 17", Key.DirectionRight)
            composeRule.onNode(hasText("Extra 17") and isFocused()).assertIsFocused()
            key("Extra 17", Key.Back)
            composeRule.onNodeWithText("Other 17").assertIsFocused()
            composeRule.onNodeWithTag("channels-groups").assertIsDisplayed()
        }
        key("Other 17", Key.Back)
        composeRule.onNode(hasText("Channels") and isFocused()).assertIsFocused().assertIsDisplayed()
        screenshot("liquid-navigation.png")
    }

    @Test fun collapsedMainMenuPreservesChannelCursorWhenGroupsReopen() {
        browser(withCollapsingNavigation = true, withLongChannelList = true)
        key("News", Key.DirectionRight)
        var cursor = "News Two"
        for (number in 3..10) {
            key(cursor, Key.DirectionDown)
            cursor = "News ${number.toString().padStart(2, '0')}"
        }
        key(cursor, Key.Back)
        repeat(3) {
            composeRule.onNodeWithText("News").assertIsFocused()
            key("News", Key.DirectionRight)
            composeRule.onNode(hasText(cursor) and isFocused()).assertIsFocused()
            key(cursor, Key.Back)
        }
        key("News", Key.Back)
        composeRule.onNode(hasText("Channels") and isFocused()).assertIsFocused()
    }

    @Test fun longGroupSidebarCentersMiddleGroupsAndStopsAtBothEndsWithoutGaps() {
        browser(withExtraGroups = true)
        val groups = composeRule.onNodeWithTag("channels-groups").getUnclippedBoundsInRoot()
        val screen = composeRule.onNodeWithTag("channels-browser").getUnclippedBoundsInRoot()
        key("News", Key.DirectionUp)
        key("All Channels", Key.DirectionUp)
        key("Recently Viewed", Key.DirectionUp)
        val favorites = composeRule.onNode(hasText("Favorites") and hasClickAction()).assertIsFocused().getUnclippedBoundsInRoot()
        assertTrue("Favorites must start at the top without centre padding", abs((favorites.top - groups.top).value) <= 4f)
        key("Favorites", Key.DirectionDown)
        key("Recently Viewed", Key.DirectionDown)
        key("All Channels", Key.DirectionDown)
        key("News", Key.DirectionDown)
        repeat(11) { index -> key("Other ${(index + 1).toString().padStart(2, '0')}", Key.DirectionDown) }
        composeRule.onNodeWithText("Other 12").assertIsFocused()
        val focusedGroup = composeRule.onNode(hasText("Other 12") and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("Remote navigation must keep the entire focused group visible", focusedGroup.top >= groups.top && focusedGroup.bottom <= groups.bottom)
        val centerY = (screen.top + screen.bottom) / 2
        assertTrue("Groups must stay at screen centre", abs(((focusedGroup.top + focusedGroup.bottom) / 2 - centerY).value) <= 4f)
        repeat(5) { index -> key("Other ${(12 - index).toString().padStart(2, '0')}", Key.DirectionUp) }
        composeRule.onNodeWithText("Other 07").assertIsFocused()
        val returned = composeRule.onNode(hasText("Other 07") and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("Scrolling upward must use the same centre", abs(((returned.top + returned.bottom) / 2 - centerY).value) <= 4f)
        repeat(33) { index -> key("Other ${(7 + index).toString().padStart(2, '0')}", Key.DirectionDown) }
        val lastGroup = composeRule.onNode(hasText("Other 40") and hasClickAction()).assertIsFocused().getUnclippedBoundsInRoot()
        assertTrue("The last group must reach the bottom without centre padding", abs((groups.bottom - lastGroup.bottom).value) <= 4f)
        key("Other 40", Key.DirectionDown)
        composeRule.onNodeWithText("Other 40").assertIsFocused()
    }

    @Test fun returningFromPlaybackRestoresTheChannelAfterUsingTheListToolbar() {
        browser(withPlayerReturn = true)
        key("News", Key.DirectionRight)
        key("News Two", Key.DirectionUp)
        key("News One", Key.DirectionUp)
        composeRule.onNodeWithTag("channels-guide-tool-0").assertIsFocused()
        tagKey("channels-guide-tool-0", Key.DirectionDown)
        key("News One", Key.DirectionCenter)
        composeRule.onNodeWithText("Stop test playback").assertIsFocused()
        key("Stop test playback", Key.DirectionCenter)
        composeRule.onNode(hasText("News One") and hasClickAction()).assertIsFocused()
    }

    @Test fun browserFiltersAndGuideDayPickerReturnToTheirOpeningControls() {
        browser(withGuide = true)
        composeRule.waitForIdle()
        screenshot("liquid-channels-guide.png")
        composeRule.onNodeWithText("Filters").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key("Filters", Key.DirectionCenter)
        composeRule.onNodeWithText("Quality: All qualities").assertIsFocused()
        screenshot("liquid-channels-options.png")
        key("Quality: All qualities", Key.Back)
        composeRule.onNodeWithText("Filters").assertIsFocused()
        key("Filters", Key.DirectionRight)
        composeRule.onNodeWithText("Manage").assertIsFocused()
        key("Manage", Key.DirectionLeft)
        composeRule.onNodeWithText("Filters").assertIsFocused()
        composeRule.onNodeWithText("News").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key("News", Key.DirectionRight)
        key("Second programme", Key.DirectionUp)
        key("On now", Key.DirectionUp)
        composeRule.onNodeWithTag("channels-guide-tool-0").assertIsFocused()
        tagKey("channels-guide-tool-0", Key.DirectionCenter)
        composeRule.onNodeWithTag("channels-guide-tool-0").assertIsSelected()
        screenshot("liquid-channels-list.png")
        tagKey("channels-guide-tool-0", Key.DirectionRight)
        tagKey("channels-guide-tool-1", Key.DirectionCenter)
        composeRule.onNodeWithTag("channels-guide-tool-1").assertIsFocused().assertIsSelected()
        composeRule.onNodeWithTag("channels-guide-tool-4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        tagKey("channels-guide-tool-4", Key.DirectionCenter)
        composeRule.onNode(hasText("Now") and isFocused()).assertIsFocused()
        key("Now", Key.Back)
        composeRule.onNodeWithTag("channels-guide-tool-4").assertIsFocused()
    }

    @Test fun unavailableGuideExplainsMissingDataAndKeepsRemoteFocus() {
        browser()
        key("News", Key.DirectionRight)
        key("News Two", Key.DirectionUp)
        key("News One", Key.DirectionUp)
        composeRule.onNodeWithTag("channels-guide-tool-0").assertIsFocused().assertIsSelected()
        tagKey("channels-guide-tool-0", Key.DirectionRight)
        tagKey("channels-guide-tool-1", Key.DirectionCenter)
        composeRule.onNodeWithTag("channels-guide-tool-1").assertIsFocused()
        composeRule.onNodeWithTag("channels-guide-tool-0").assertIsSelected()
        composeRule.onNodeWithText("No programme information is supplied for this group. You can still watch its channels.").assertIsDisplayed()
        tagKey("channels-guide-tool-1", Key.Back)
        composeRule.onNodeWithText("News").assertIsFocused()
    }

    @Test fun absentAndUnavailableLogosKeepChannelTitlesAlignedAndQualitySeparate() {
        val first = channel("News HD")
        val second = channel("Sports FHD").copy(tvgLogo = "file:///missing-channel-logo.png")
        composeRule.setContent { TvTestSize { grid(listOf(EnrichedChannel(first), EnrichedChannel(second)), emptyList(), showGuide = false) } }
        settle()
        val firstTitle = composeRule.onNodeWithText("News", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val secondTitle = composeRule.onNodeWithText("Sports", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(firstTitle.left, secondTitle.left, 0.5f)
        composeRule.onNodeWithText("HD", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("FHD", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun removingTheOnlyFavoriteReturnsToTheEmptyFavoritesGroupWithFocus() {
        val vm = browser()
        composeRule.waitUntil(10_000) { vm.state.value.selectedChannel?.name == "News Two" }
        val selected = vm.state.value.selectedChannel!!
        composeRule.runOnIdle { vm.toggleFavorite(selected) }
        composeRule.waitUntil(5_000) { vm.state.value.favorites.size == 1 }
        key("News", Key.DirectionUp)
        key("All Channels", Key.DirectionUp)
        key("Recently Viewed", Key.DirectionUp)
        key("Favorites", Key.DirectionRight)
        key("News Two", Key.Menu)
        key("Watch channel", Key.DirectionDown)
        key("Remove from Favorites", Key.DirectionCenter)
        composeRule.waitUntil(5_000) { vm.state.value.favorites.isEmpty() }
        key("Add to Favorites", Key.Back)
        composeRule.onNodeWithText("Favorites").assertIsFocused()
        composeRule.onNodeWithText("Your favorite channels appear here").assertIsDisplayed()
    }

    private fun browser(withGuide: Boolean = false, withExtraGroups: Boolean = false, withPlayerReturn: Boolean = false,
        withCollapsingNavigation: Boolean = false, withLongChannelList: Boolean = false): ChannelsViewModel {
        composeRule.runOnIdle { TvScreenCache.clear() }
        val id = "qa-browser-${System.nanoTime()}"
        val first = channel("News One", id)
        val second = channel("News Two", id)
        val channels = listOf(first, second) + (if (withExtraGroups) (1..40).map { number ->
            val label = number.toString().padStart(2, '0')
            channel("Extra $label", id).copy(groupTitle = "Other $label")
        } else emptyList()) + if (withLongChannelList) (3..30).map { number ->
            channel("News ${number.toString().padStart(2, '0')}", id)
        } else emptyList()
        val programmes = if (withGuide) listOf(current, current.copy(channelId = "News Two", title = "Second programme")) else emptyList()
        val guide = EpgData(programmes = programmes, programmesByChannelKey = if (withGuide) mapOf(
            canonicalEpgChannelKey(id, first)!! to listOf(programmes[0]), canonicalEpgChannelKey(id, second)!! to listOf(programmes[1])) else emptyMap())
        val repo = AndroidFakeChannelRepository(listOf(ChannelPlaylist(id, "Test source", "https://example.test/feed", type = PlaylistType.M3U,
            epgUrl = if (withGuide) "https://example.test/guide" else null)),
            mutableMapOf(id to channels), mutableMapOf(id to channels), cachedGuide = guide)
        val prefs = AndroidFakePreferencesRepository(mutableMapOf("channels_selected_playlist" to id,
            "channels_selected_group_$id" to "News", "channels_selected_channel_$id" to stableChannelId(second)))
        val vm = ChannelsViewModel(repo, prefs)
        composeRule.setContent { TvTestSize {
            val rail = remember { FocusRequester() }
            var autoFocus by remember { mutableStateOf(true) }
            var playerActive by remember { mutableStateOf(false) }
            var restorePlayerFocus by remember { mutableStateOf(false) }
            var lastContentRequester by remember { mutableStateOf<FocusRequester?>(null) }
            var railFocused by remember { mutableStateOf(false) }
            var railReturnRequested by remember { mutableStateOf(false) }
            val playerStopRequester = remember { FocusRequester() }
            LaunchedEffect(playerActive, restorePlayerFocus) {
                if (playerActive) { delay(40); playerStopRequester.requestFocus() }
                else if (restorePlayerFocus) { delay(40); lastContentRequester?.requestFocus(); restorePlayerFocus = false }
            }
            val browserContent: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    TvIptvScreen(railFocusRequester = rail, onChannelPlay = { if (withPlayerReturn) playerActive = true }, onProgrammePlayFromBeginning = { _, _, _ -> },
                        onFirstContentRequester = {}, onContentFocused = { autoFocus = false; lastContentRequester = it }, viewModel = vm,
                        isActive = !playerActive, isSubRouteActive = playerActive,
                        isRailFocused = withCollapsingNavigation && railFocused && !autoFocus,
                        shouldAutoFocus = autoFocus,
                        onNavigateUp = {
                            railReturnRequested = withCollapsingNavigation
                            rail.requestFocus()
                        })
                    if (playerActive) Text("Stop test playback", modifier = Modifier.focusRequester(playerStopRequester).clickable {
                        playerActive = false; restorePlayerFocus = true
                    })
                }
            }
            if (withCollapsingNavigation) {
                TvScaffold(showRail = railFocused || railReturnRequested, background = {},
                    leftRail = {
                        TvNavRail(destinations = tvTopDestinations, selectedRoute = TvRoutes.IPTV,
                            activeRoute = TvRoutes.IPTV, isExpanded = false, railFocusRequester = rail,
                            isVisible = railFocused || railReturnRequested,
                            isFocusEnabled = !TvIptvRailState.hideRail.value || railReturnRequested,
                            preferredEntryRoute = if (railReturnRequested) TvRoutes.IPTV else null,
                            onRailFocusChanged = { railFocused = it },
                            onPreferredEntryRouteConsumed = { railReturnRequested = false },
                            onMoveToContent = { lastContentRequester?.requestFocus() }, onNavigate = {})
                    }, content = { Row(Modifier.width(960.dp).height(540.dp)) { browserContent() } })
            } else {
                Row(Modifier.width(960.dp).height(540.dp)) {
                    Text("Navigation", modifier = Modifier.width(60.dp).fillMaxHeight().focusRequester(rail)
                        .onFocusChanged { railFocused = it.hasFocus }.clickable {})
                    browserContent()
                }
            }
        } }
        composeRule.waitUntil(10_000) {
            vm.state.value.selectedChannel?.name == "News Two" &&
                composeRule.onAllNodes(hasText("News") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        if (withGuide) composeRule.waitUntil(10_000) { vm.state.value.guideProgrammes.isNotEmpty() }
        return vm
    }

    @Composable private fun grid(channels: List<EnrichedChannel>, programmes: List<EpgProgramme>, row: Int = 0,
        col: Int = 0, token: Int = 1, showGuide: Boolean = true, enabled: Boolean = true,
        onMove: (Int) -> Unit = {}, onPlay: (Channel) -> Unit = {}, onActions: (Channel, EpgProgramme?) -> Unit = { _, _ -> },
        window: Long = start) {
        val guide = if (programmes.isEmpty()) emptyMap() else mapOf(canonicalEpgChannelKey(news.playlistId, news)!! to programmes)
        TvEpgGrid(channels, guide, playlistId = news.playlistId, windowStartMs = window, windowEndMs = window + 7_200_000L,
            canPageBackward = true, canPageForward = true, focusRowIndex = row, focusColIndex = col,
            focusRequestToken = token, isFocusEnabled = enabled, onChannelFocused = { _, _ -> }, onGridCellFocused = { _, _ -> },
            onMoveVertical = onMove, onChannelPlay = onPlay, onProgrammeActions = onActions, onTimeForward = {},
            modifier = Modifier.width(620.dp).height(380.dp).testTag("guide"), channelColumnWidth = 148.dp, showGuide = showGuide)
    }

    @Composable private fun TvTestSize(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
            com.torve.android.ui.theme.TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) { content() }
        }
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(composeRule.activity.getExternalFilesDir(null), name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun key(label: String, key: Key) {
        composeRule.onNode(hasText(label) and isFocused()).assertIsFocused()
        if (key == Key.Back) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        else composeRule.onNode(hasText(label) and isFocused()).performKeyInput { pressKey(key) }
        composeRule.mainClock.advanceTimeBy(250)
        Thread.sleep(180)
        composeRule.waitForIdle()
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(400)
        Thread.sleep(200)
        composeRule.waitForIdle()
    }

    private fun tagKey(tag: String, key: Key) {
        if (key == Key.Back) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        else composeRule.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        settle()
    }
}
