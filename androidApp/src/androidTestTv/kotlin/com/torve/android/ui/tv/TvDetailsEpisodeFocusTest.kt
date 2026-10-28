package com.torve.android.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvEpisodePicker
import com.torve.android.tv.screens.TvDetailsPrimaryEntryFocus
import com.torve.android.tv.screens.scrollToComposedTvDetailsRow
import com.torve.domain.model.Episode
import com.torve.domain.model.Season
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvDetailsEpisodeFocusTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    private val seasons = (1..3).map { season ->
        Season(seasonNumber = season, episodeCount = 18, episodes = (1..18).map {
            Episode(episodeNumber = it, name = "Episode $it")
        })
    }

    @Test
    fun episodeShortcutWaitsForMetadataAndLayoutWithoutFocusingPlay() {
        var metadataReady by mutableStateOf(false)
        var seasonReady by mutableStateOf(false)
        var refresh by mutableStateOf(0)
        var target by mutableStateOf<Pair<Int, Int>?>(2 to 18)
        var playFocusCount = 0
        var restored = 0
        composeRule.setContent {
            val playRequester = androidx.compose.runtime.remember { FocusRequester() }
            val listState = rememberLazyListState()
            TvDetailsPrimaryEntryFocus(
                contentId = if (metadataReady) "series:42" else null,
                episodeEntry = true, sourcePickerVisible = false, playRequester = playRequester,
            )
            LaunchedEffect(metadataReady) {
                if (metadataReady) listState.scrollToComposedTvDetailsRow(1)
            }
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (metadataReady) {
                        item(key = "hero") {
                            Column(Modifier.height(650.dp)) {
                                Text("Watch S2 E18", Modifier.focusRequester(playRequester)
                                    .onFocusChanged { if (it.isFocused) playFocusCount++ }
                                    .clickable {})
                                Text("Metadata revision $refresh")
                            }
                        }
                        item(key = "episodes") {
                            TvEpisodePicker(
                                seasons = seasons, selectedSeason = 2,
                                seasonDetail = if (seasonReady) seasons[1] else null,
                                isLoadingSeasonDetail = !seasonReady,
                                watchedEpisodes = emptySet(), watchProgress = null,
                                railFocusRequester = FocusRequester(),
                                onSeasonSelected = {}, onEpisodeSelected = { _, _ -> },
                                onFirstContentRequester = {}, onContentFocused = {},
                                preferredEpisode = 2 to 1, restoreFocusEpisode = target,
                                heroFocusRequester = playRequester,
                                onEpisodeFocusRestored = { restored++; target = null },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { metadataReady = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, restored); seasonReady = true }
        waitForFocus("S2 E18  Episode 18")
        composeRule.runOnIdle { assertEquals(1, restored); assertEquals(0, playFocusCount) }
        composeRule.onNodeWithText("S2 E18  Episode 18").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForFocus("S2 E17  Episode 17")
        composeRule.runOnIdle { refresh++ }
        waitForFocus("S2 E17  Episode 17")
        composeRule.runOnIdle { assertEquals(0, playFocusCount) }
    }

    @Test
    fun ordinaryTitleEntryStillFocusesPlayAfterMetadataArrives() {
        var metadataReady by mutableStateOf(false)
        composeRule.setContent {
            val playRequester = androidx.compose.runtime.remember { FocusRequester() }
            TvDetailsPrimaryEntryFocus(
                contentId = if (metadataReady) "movie:42" else null,
                episodeEntry = false, sourcePickerVisible = false, playRequester = playRequester,
            )
            MaterialTheme {
                if (metadataReady) Text("Watch now", Modifier.focusRequester(playRequester).clickable {})
            }
        }
        composeRule.runOnIdle { metadataReady = true }
        waitForFocus("Watch now")
    }

    @Test
    fun stoppedEpisodeWaitsForActiveDetailsAndRemainsNavigableAfterRestoration() {
        var active by mutableStateOf(false)
        var restoreTarget by mutableStateOf<Pair<Int, Int>?>(1 to 18)
        var restored = 0
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    TvEpisodePicker(
                        seasons = seasons,
                        selectedSeason = 1,
                        seasonDetail = seasons.first(),
                        isLoadingSeasonDetail = false,
                        watchedEpisodes = emptySet(),
                        watchProgress = null,
                        railFocusRequester = FocusRequester(),
                        onSeasonSelected = {},
                        onEpisodeSelected = { _, _ -> },
                        onFirstContentRequester = {},
                        onContentFocused = {},
                        preferredEpisode = 1 to 1,
                        restoreFocusEpisode = restoreTarget,
                        isActive = active,
                        onEpisodeFocusRestored = {
                            restored++
                            restoreTarget = null
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, restored); active = true }
        waitForFocus("S1 E18  Episode 18")
        composeRule.runOnIdle { assertEquals(1, restored) }

        composeRule.onNodeWithText("S1 E18  Episode 18").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForFocus("S1 E17  Episode 17")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("S1 E17  Episode 17").assertIsFocused()
    }

    @Test
    fun markingSeasonWatchedRepairsFocusLostDuringTheWatchStateUpdate() {
        var watched by mutableStateOf(emptySet<String>())
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val focusManager = LocalFocusManager.current
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    TvEpisodePicker(
                        seasons = seasons,
                        selectedSeason = 1,
                        seasonDetail = seasons.first(),
                        isLoadingSeasonDetail = false,
                        watchedEpisodes = watched,
                        watchProgress = null,
                        railFocusRequester = FocusRequester(),
                        onSeasonSelected = {},
                        onEpisodeSelected = { _, _ -> },
                        onFirstContentRequester = {},
                        onContentFocused = {},
                        onMarkSeasonWatched = { season ->
                            scope.launch {
                                withFrameNanos { }
                                focusManager.clearFocus(force = true)
                                watched = (1..18).map { "s${season}e$it" }.toSet()
                            }
                        },
                    )
                }
            }
        }
        val action = composeRule.activity.getString(R.string.tv_season_mark_watched, 1)
        val complete = composeRule.activity.getString(R.string.tv_season_watched, 1)
        composeRule.onNodeWithText(action).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText(action).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus(complete)
        composeRule.onNodeWithText(complete).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForFocus("S3")
    }

    @Test
    fun seasonActionKeepsItsFocusWhenSelectedSeasonChanges() {
        var selectedSeason by mutableStateOf(1)
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    TvEpisodePicker(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        seasonDetail = seasons[selectedSeason - 1],
                        isLoadingSeasonDetail = false,
                        watchedEpisodes = emptySet(),
                        watchProgress = null,
                        railFocusRequester = FocusRequester(),
                        onSeasonSelected = { selectedSeason = it },
                        onEpisodeSelected = { _, _ -> },
                        onFirstContentRequester = {},
                        onContentFocused = {},
                    )
                }
            }
        }
        val firstAction = composeRule.activity.getString(R.string.tv_season_mark_watched, 1)
        composeRule.onNodeWithText(firstAction).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.runOnIdle { selectedSeason = 2 }
        waitForFocus(composeRule.activity.getString(R.string.tv_season_mark_watched, 2))
    }

    @Test
    fun receivingWatchedEpisodesFromAnotherTvKeepsTheCurrentEpisodeFocused() {
        var watched by mutableStateOf(emptySet<String>())
        var preferred by mutableStateOf(2 to 1)
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    TvEpisodePicker(
                        seasons = seasons,
                        selectedSeason = 2,
                        seasonDetail = seasons[1],
                        isLoadingSeasonDetail = false,
                        watchedEpisodes = watched,
                        watchProgress = null,
                        railFocusRequester = FocusRequester(),
                        onSeasonSelected = {},
                        onEpisodeSelected = { _, _ -> },
                        onFirstContentRequester = {},
                        onContentFocused = {},
                        preferredEpisode = preferred,
                    )
                }
            }
        }
        val first = "S2 E1  Episode 1"
        composeRule.onNodeWithText(first).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        waitForFocus(first)
        composeRule.runOnIdle {
            watched = setOf("s2e1", "s2e2", "s2e3")
            preferred = 2 to 4
        }
        waitForFocus(first)
        composeRule.onNodeWithText(first).performKeyInput { pressKey(Key.DirectionRight) }
        waitForFocus("S2 E2  Episode 2")
    }

    @Test
    fun upFromTheSeasonAndWatchedActionReturnsToThePrimaryPlayAction() {
        val heroRequester = FocusRequester()
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Text("Watch S2 E4", Modifier.focusRequester(heroRequester).clickable {})
                    TvEpisodePicker(
                        seasons = seasons, selectedSeason = 2, seasonDetail = seasons[1],
                        isLoadingSeasonDetail = false, watchedEpisodes = setOf("s2e1", "s2e2", "s2e3"),
                        watchProgress = null, railFocusRequester = FocusRequester(),
                        onSeasonSelected = {}, onEpisodeSelected = { _, _ -> },
                        onFirstContentRequester = {}, onContentFocused = {},
                        heroFocusRequester = heroRequester,
                    )
                }
            }
        }
        composeRule.onNodeWithText("S2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("S2").performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocus("Watch S2 E4")
        val action = composeRule.activity.getString(R.string.tv_season_mark_watched, 2)
        composeRule.onNodeWithText(action).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText(action).performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocus("Watch S2 E4")
        composeRule.onNodeWithText("Episodes · S2 · 3 of 18 watched").assertExists()
    }

    private fun waitForFocus(label: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { composeRule.onNodeWithText(label).assertIsFocused(); true }.getOrDefault(false)
        }
    }
}
