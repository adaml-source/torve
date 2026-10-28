package com.torve.presentation.apple

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Episode
import com.torve.domain.model.Season
import com.torve.presentation.detail.DetailUiState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AppleTvDetailLayoutTest {
    private fun movie(id: String) = MediaItem(id = id, title = id, type = MediaType.MOVIE, year = 2024)
    private fun row(item: MediaItem) = AppleTvRow("${item.type}:${item.id}", item.title,
        targetId = item.id, mediaType = if (item.type == MediaType.SERIES) "tv" else "movie", year = item.year)

    @Test fun relatedTitlesKeepDestinationsWithoutSelfDuplicatesOrBlankTargets() {
        val showWithSameId = movie("1").copy(type = MediaType.SERIES)
        val shelf = AppleTvDetailLayout.related(DetailUiState(mediaItem = movie("1"),
            similar = listOf(movie("1"), movie("2"), movie("2"), movie(""), showWithSameId)), ::row)
        assertEquals(listOf("2", "1"), shelf.items.map { it.targetId })
        assertEquals(listOf("movie", "tv"), shelf.items.map { it.mediaType })
        assertTrue(shelf.items.all { it.year == 2024 })
    }

    @Test fun emptySeriesRailRetainsItsHeaderAndEmptyMessage() {
        val shelf = AppleTvDetailLayout.related(DetailUiState(mediaItem = movie("1").copy(type = MediaType.SERIES)), ::row)
        assertEquals("Similar TV shows", shelf.title)
        assertTrue(shelf.items.isEmpty())
        assertTrue(shelf.emptyMessage.isNotBlank())
    }

    @Test fun snapshotWireContractRetainsRelatedDataAndLoadingFailureState() {
        val shelf = AppleTvDetailLayout.related(DetailUiState(mediaItem = movie("1"), similar = listOf(movie("2"))), ::row)
        val snapshot = AppleTvSnapshot(screen = "detail", related = shelf, relatedBusy = true,
            relatedError = "recommendations_failed", detailId = "1", detailType = "movie", hideUnwatchedSpoilers = true)
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<AppleTvSnapshot>(json.encodeToString(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals("2", decoded.related?.items?.single()?.targetId)
    }

    @Test fun episodeCardsKeepTheirOwnSynopsisAndPlaybackDestination() {
        val state = DetailUiState(mediaItem = movie("show").copy(type = MediaType.SERIES), selectedSeason = 2,
            seasonDetail = Season(2, 1, episodes = listOf(Episode(3, "Episode title", "Episode synopsis", "still", "2024-01-01"))))
        val row = AppleTvDetailLayout.episodes(state).single()
        assertEquals("Episode synopsis", row.overview)
        assertEquals("Episode title", row.title)
        assertEquals("show", row.targetId)
        assertEquals(2, row.season)
        assertEquals(3, row.episode)
    }

    @Test fun spoilerHidingSuppressesUnwatchedSynopsisAndStillWithoutChangingEpisodeIdentity() {
        val state = DetailUiState(mediaItem = movie("show").copy(type = MediaType.SERIES), selectedSeason = 2,
            hideUnwatchedSpoilers = true,
            seasonDetail = Season(2, 1, episodes = listOf(Episode(3, "Spoiler title", "Spoiler synopsis", "spoiler still"))))
        val hidden = AppleTvDetailLayout.episodes(state).single()
        assertEquals("Episode 3", hidden.title)
        assertNull(hidden.overview)
        assertNull(hidden.artwork)
        val watched = AppleTvDetailLayout.episodes(state.copy(watchedEpisodes = setOf("s2e3"))).single()
        assertEquals(hidden.id, watched.id)
        assertEquals("Spoiler synopsis", watched.overview)
        assertEquals("spoiler still", watched.artwork)
    }
}
