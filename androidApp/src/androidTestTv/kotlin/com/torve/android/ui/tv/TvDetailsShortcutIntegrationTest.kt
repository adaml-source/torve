package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvDetailsScreen
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.detail.DetailViewModel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the complete detail screen and its view model, rather than only the episode picker. */
@OptIn(ExperimentalTestApi::class)
class TvDetailsShortcutIntegrationTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()
    private lateinit var originalMetadata: MetadataRepository
    private lateinit var detailViewModel: DetailViewModel
    private val seasons = (1..3).map { number ->
        Season(seasonNumber = number, episodeCount = 18, episodes = (1..18).map {
            Episode(episodeNumber = it, name = "Episode $it", airDate = "2020-01-01")
        })
    }

    @Before fun useOfflineMetadata() {
        composeRule.runOnUiThread { composeRule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val koin = getKoin()
        originalMetadata = koin.get()
        val fixture = object : MetadataRepository by originalMetadata {
            override suspend fun getDetail(type: String, id: Int): MediaItem {
                delay(80)
                return MediaItem(id = "tv_shortcut_integration", tmdbId = id, type = MediaType.SERIES,
                    title = "Shortcut integration", seasons = seasons)
            }
            override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season {
                delay(180)
                return seasons.first { it.seasonNumber == seasonNumber }
            }
            override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
            override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> =
                (1..12).map { MediaItem("shortcut_similar_$it", type = MediaType.MOVIE, title = "Similar $it") }
        }
        koin.declare<MetadataRepository>(fixture, allowOverride = true)
        detailViewModel = koin.get()
    }

    @After fun restoreMetadata() {
        if (::detailViewModel.isInitialized) detailViewModel.clear()
        if (::originalMetadata.isInitialized) getKoin().declare(originalMetadata, allowOverride = true)
    }

    @Test fun fullDetailScreenFocusesTheExactEpisodeAfterAsynchronousLoading() {
        showDetails(requestId = Long.MAX_VALUE, pinEntry = true)
        composeRule.onNodeWithText("S2 E18  Episode 18").assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(2, detailViewModel.state.value.streamContextSeason)
            assertEquals(18, detailViewModel.state.value.streamContextEpisode)
        }
        moveLeftFromRestoredEpisode()
    }

    @Test fun playbackReturnFocusesThePrimaryActionWhileTheNextPlayTargetRefreshes() {
        showDetails(requestId = 1L, pinEntry = false)
        composeRule.waitUntil(10_000) { detailViewModel.state.value.nextEpisode != null }
        composeRule.onNodeWithText("Watch S1 E1").assertIsFocused()
        composeRule.runOnIdle { assertEquals(1, detailViewModel.state.value.streamContextEpisode) }
    }

    @Test fun ordinaryDetailEntryKeepsThePrimaryActionFocusedThroughWatchLoading() {
        showDetails(requestId = 0L, pinEntry = false)
        composeRule.waitUntil(10_000) { detailViewModel.state.value.nextEpisode != null }
        composeRule.onNodeWithText("Watch S1 E1").assertIsFocused()
    }

    private fun moveLeftFromRestoredEpisode() {
        composeRule.onNodeWithText("S2 E18  Episode 18").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithText("S2 E17  Episode 17").assertIsFocused()
    }

    private fun showDetails(requestId: Long, pinEntry: Boolean) {
        val contentFocusCount = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                TvDetailsScreen(type = "tv", id = 999942, autoPlay = false, focusEpisodes = pinEntry,
                    railFocusRequester = FocusRequester(), onBack = {},
                    onPlayResolved = { _, _, _, _, _, _, _ -> },
                    onFirstContentRequester = {}, onContentFocused = { contentFocusCount.incrementAndGet() },
                    playbackFocusRestoreRequestId = requestId,
                    playbackFocusRestoreSeason = 2.takeIf { requestId > 0 },
                    playbackFocusRestoreEpisode = 18.takeIf { requestId > 0 },
                    preserveEntryPlaybackTarget = pinEntry,
                    detailViewModel = detailViewModel)
            }
        }
        try {
            composeRule.waitUntil(15_000) { contentFocusCount.get() > 0 }
        } finally {
            val state = detailViewModel.state.value
            println("Shortcut state: loading=${state.isLoading}, selectedSeason=${state.selectedSeason}, " +
                "loadedSeason=${state.seasonDetail?.seasonNumber}, episodeCount=${state.seasonDetail?.episodes?.size}, " +
                "seasonLoading=${state.isLoadingSeasonDetail}, context=${state.streamContextSeason}:${state.streamContextEpisode}, " +
                "contentFocusCount=${contentFocusCount.get()}, mediaSeasons=${state.mediaItem?.seasons?.size}")
        }
    }
}
