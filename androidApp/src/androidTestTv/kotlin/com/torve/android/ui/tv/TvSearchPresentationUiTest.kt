package com.torve.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvSearchDensity
import com.torve.android.tv.screens.TvSearchResultCard
import com.torve.android.tv.screens.TvSearchResultsLayout
import com.torve.android.tv.screens.TvSearchSelectedPreviewV2
import com.torve.android.tv.screens.TvSearchProviderRatingCards
import com.torve.android.tv.screens.TvSearchToolbar
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.MediaRatings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvSearchPresentationUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun titlesYearsAndTypeSymbolsStayVisibleAtEveryPosterSize() {
        var selections = 0
        composeRule.setContent {
            MaterialTheme {
                Row {
                    TvSearchDensity.entries.forEach { density ->
                        TvSearchResultCard(MediaItem(id = density.name, title = density.label, year = 2024,
                            type = MediaType.SERIES), density, Modifier.width(150.dp).aspectRatio(2f / 3f),
                            onFocused = {}, onClick = { selections++ })
                    }
                }
            }
        }
        TvSearchDensity.entries.forEach { composeRule.onNodeWithText(it.label).assertIsDisplayed() }
        composeRule.onAllNodesWithText("2024", useUnmergedTree = true).assertCountEquals(4)
        composeRule.onAllNodesWithContentDescription("TV series", useUnmergedTree = true).assertCountEquals(4)
        focus("Compact")
        key("Compact", Key.DirectionCenter)
        composeRule.runOnIdle { assertEquals(1, selections) }
    }

    @Test
    fun previewDoesNotResizeTheGridWhenFocusOrHydratedMetadataChanges() {
        var item by mutableStateOf(MediaItem(id = "gentlemen", title = "The Gentlemen", year = 2024, type = MediaType.SERIES))
        composeRule.setContent {
            MaterialTheme {
                TvSearchResultsLayout(Modifier.fillMaxSize(), showPreview = true,
                    preview = { modifier -> Box(modifier) { Text("Selection preview") } }) {
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("results")) {
                        TvSearchResultCard(item, TvSearchDensity.RELAXED, Modifier.width(150.dp)
                            .aspectRatio(2f / 3f).testTag("result"), onFocused = {}, onClick = {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("Selection preview").assertIsDisplayed()
        val before = composeRule.onNodeWithTag("results").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("result").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.runOnIdle { item = item.copy(overview = "Hydrated metadata") }
        composeRule.onNodeWithTag("result").assertIsFocused()
        val after = composeRule.onNodeWithTag("results").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 1f)
        assertEquals(before.width, after.width, 1f)
    }

    @Test
    fun shortAndLongTitlesHaveTheSameFooterHeightWithLargeText() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Row {
                        listOf("short" to "Fallout", "long" to "A very long movie title spanning several lines").forEach { (id, title) ->
                            TvSearchResultCard(MediaItem(id = id, title = title, type = MediaType.MOVIE, year = 2026),
                                TvSearchDensity.RELAXED, Modifier.width(150.dp).aspectRatio(2f / 3f), onFocused = {}, onClick = {})
                        }
                    }
                }
            }
        }
        val short = composeRule.onNodeWithTag("search_footer:short", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val long = composeRule.onNodeWithTag("search_footer:long", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(short.height, long.height, 1f)
        assertEquals(short.top, long.top, 1f)
        composeRule.onNodeWithText("Fallout", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("2026", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun viewChoiceAndRepeatedBackRestoreActualWindowFocusToView() {
        var density by mutableStateOf(TvSearchDensity.RELAXED)
        var backCalls = 0
        composeRule.setContent {
            MaterialTheme {
                BackHandler { backCalls++ }
                ToolbarHarness(density = density, onDensityChange = { density = it })
            }
        }
        focus("View")
        key("View", Key.DirectionCenter)
        waitForFocus("Medium · 8 per row", inPopup = true)
        key("Medium · 8 per row", Key.DirectionDown)
        key("Small · 10 per row", Key.DirectionDown)
        key("Compact · 12 per row", Key.DirectionCenter)
        waitForFocus("View")
        composeRule.runOnIdle { assertEquals(TvSearchDensity.COMPACT, density) }
        repeat(3) {
            key("View", Key.DirectionCenter)
            waitForFocus("Compact · 12 per row", inPopup = true)
            key("Compact · 12 per row", Key.Back)
            waitForFocus("View")
        }
        key("View", Key.DirectionDown)
        composeRule.onNodeWithText("The Gentlemen").assertIsFocused()
        key("The Gentlemen", Key.DirectionUp)
        composeRule.onNodeWithText("View").assertIsFocused()
        composeRule.runOnIdle { assertEquals(0, backCalls) }
    }

    @Test
    fun recentSearchesOnlyAppearInsidePopupAndSelectionReturnsToSearch() {
        var query by mutableStateOf("")
        var submitted = 0
        composeRule.setContent {
            MaterialTheme { ToolbarHarness(query = query, onQueryCommitted = { query = it; submitted++ },
                recentSearches = listOf("gentlemen", "paradise")) }
        }
        composeRule.onNodeWithText("gentlemen").assertDoesNotExist()
        focus("Search")
        key("Search", Key.DirectionCenter)
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithText("Recent searches").assertIsDisplayed() }.isSuccess }
        focus("gentlemen")
        key("gentlemen", Key.DirectionCenter)
        waitForFocus("gentlemen")
        composeRule.onNodeWithText("Recent searches").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, submitted) }
        key("gentlemen", Key.DirectionDown)
        composeRule.onNodeWithText("The Gentlemen").assertIsFocused()
    }

    @Test
    fun removingHistoryAndCancellingDoesNotCommitADraftOrDropFocus() {
        var history by mutableStateOf(listOf("gentlemen", "paradise"))
        var submissions = 0
        composeRule.setContent {
            MaterialTheme { ToolbarHarness(recentSearches = history, onRemoveRecent = { term -> history = history - term },
                onQueryCommitted = { submissions++ }) }
        }
        focus("Search")
        key("Search", Key.DirectionCenter)
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithText("Recent searches").assertIsDisplayed() }.isSuccess }
        composeRule.onNodeWithContentDescription("Remove recent search: gentlemen")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithContentDescription("Remove recent search: gentlemen").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("gentlemen").assertDoesNotExist()
        focus("Cancel")
        key("Cancel", Key.DirectionCenter)
        waitForFocus("Search")
        composeRule.runOnIdle { assertEquals(0, submissions); assertEquals(listOf("paradise"), history) }
    }

    @Test
    fun ratingArtworkAndScoresRemainVisibleWithLargeText() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { Box(Modifier.width(228.dp)) { TvSearchProviderRatingCards(ratedItem()) } }
            }
        }
        listOf("IMDb", "Rotten Tomatoes", "Audience", "TMDB").forEach {
            composeRule.onNodeWithContentDescription(it, useUnmergedTree = true).assertIsDisplayed()
        }
        listOf("8.1/10", "90%", "95%", "8.0/10", "Critics", "Audience").forEach {
            composeRule.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun previewUsesAvailableTitleArtworkInsteadOfDuplicatingPlainTitle() {
        val artwork = "android.resource://${composeRule.activity.packageName}/${R.drawable.tv_search_logo_imdb}"
        composeRule.setContent {
            MaterialTheme { TvSearchSelectedPreviewV2(ratedItem().copy(logoUrl = artwork), modifier = Modifier.width(228.dp)) }
        }
        // This must also hold while Coil is loading, not only after success.
        composeRule.onNodeWithText("The Gentlemen").assertDoesNotExist()
        composeRule.waitUntil(10_000) {
            runCatching {
                composeRule.onNodeWithTag("search_title_artwork").assertIsDisplayed()
                composeRule.onNodeWithText("The Gentlemen").assertDoesNotExist()
            }.isSuccess
        }
        composeRule.onNodeWithContentDescription("The Gentlemen").assertIsDisplayed()
    }

    @Test
    fun previewReservesArtworkSpaceDuringLookupThenShowsMissingTitle() {
        var pending by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme { TvSearchSelectedPreviewV2(ratedItem().copy(logoUrl = null),
                modifier = Modifier.width(228.dp), artworkLookupPending = pending) }
        }
        val bounds = composeRule.onNodeWithTag("search_title_artwork").getUnclippedBoundsInRoot()
        composeRule.onNodeWithText("The Gentlemen").assertDoesNotExist()
        composeRule.runOnIdle { pending = false }
        composeRule.onNodeWithText("The Gentlemen").assertIsDisplayed()
        org.junit.Assert.assertEquals(bounds, composeRule.onNodeWithTag("search_title_artwork").getUnclippedBoundsInRoot())
    }

    @Composable
    private fun ToolbarHarness(
        query: String = "", density: TvSearchDensity = TvSearchDensity.RELAXED,
        recentSearches: List<String> = emptyList(),
        onQueryCommitted: (String) -> Unit = {}, onRemoveRecent: (String) -> Unit = {},
        onDensityChange: (TvSearchDensity) -> Unit = {},
    ) {
        val requesters = androidx.compose.runtime.remember { List(8) { FocusRequester() } }
        var last by androidx.compose.runtime.remember { mutableStateOf(requesters[0]) }
        Column(Modifier.fillMaxSize()) {
            TvSearchToolbar("test-search", query, false, false, null, 0, density, recentSearches,
                requesters[0], requesters[1], requesters[2], requesters[3], requesters[4], requesters[5], requesters[0], requesters[6],
                onMoveDown = { requesters[6].requestFocus() }, onQueryCommitted = onQueryCommitted,
                onRemoveRecent = onRemoveRecent, onClearRecent = {}, onToggleAi = {}, onFilterTypeChange = {},
                onOpenFilters = {}, onDensityChange = onDensityChange, onContentFocused = { last = it })
            TvSearchResultCard(ratedItem(), density, Modifier.width(150.dp).aspectRatio(2f / 3f)
                .focusRequester(requesters[6]).focusProperties { up = last }, onFocused = {}, onClick = {})
        }
    }

    private fun ratedItem() = MediaItem(id = "gentlemen", title = "The Gentlemen", year = 2024, type = MediaType.SERIES,
        ratings = MediaRatings(imdbScore = 8.1f, rottenTomatoesScore = 90, rtAudienceScore = 95, tmdbScore = 8.0f))
    private fun focus(label: String) { composeRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus) { it() } }
    private fun key(label: String, key: Key) { composeRule.onNodeWithText(label).performKeyInput { pressKey(key) } }
    private fun waitForFocus(label: String, inPopup: Boolean = false) {
        composeRule.waitUntil(10_000) { runCatching {
            composeRule.onNodeWithText(label).assertIsFocused()
            inPopup || composeRule.activity.hasWindowFocus()
        }.getOrDefault(false) }
    }
}
