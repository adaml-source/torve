package com.torve.presentation.home

import com.torve.domain.model.MediaType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeProgressArtworkTest {
    @Test fun missingPersonalPosterComesBeforeFortyCatalogueSynopsisRepairs() {
        val catalog = (1..40).map { MediaItem("$it", tmdbId = it, type = MediaType.SERIES,
            title = "Show $it", posterUrl = "poster-$it.jpg") }
        val missing = MediaItem("94997", tmdbId = 94997, type = MediaType.SERIES, title = "House of the Dragon")
        val result = (catalog + missing).prioritizeHomeArtwork().take(40)
        assertEquals(missing, result.first())
        assertEquals(catalog.take(39), result.drop(1))
    }
    @Test fun newerCrossDeviceProgressKeepsResolvedArtworkWithoutRestoringOldWatchState() {
        val cached = WatchProgress("94997", MediaType.SERIES, "House of the Dragon", posterUrl = "poster.jpg", backdropUrl = "backdrop.jpg", positionMs = 10_000, updatedAt = 1)
        val incoming = cached.copy(mediaId = "series:94997", posterUrl = null, backdropUrl = "", positionMs = 50_000, updatedAt = 2, seasonNumber = 2, episodeNumber = 3)
        val result = listOf(incoming).withHomeArtworkFrom(listOf(cached)).single()
        assertEquals(incoming.copy(posterUrl = "poster.jpg", backdropUrl = "backdrop.jpg"), result)
    }

    @Test fun matchingNumericIdsOfDifferentTypesCannotShareArtwork() {
        val movie = WatchProgress("7", MediaType.MOVIE, "Movie", posterUrl = "movie.jpg")
        val show = WatchProgress("7", MediaType.SERIES, "Show")
        assertEquals(show, listOf(show).withHomeArtworkFrom(listOf(movie)).single())
    }

    @Test fun explicitFreshArtworkReplacesTheCachedArtwork() {
        val cached = WatchProgress("7", MediaType.SERIES, "Show", posterUrl = "old.jpg")
        val incoming = cached.copy(posterUrl = "new.jpg")
        assertEquals(incoming, listOf(incoming).withHomeArtworkFrom(listOf(cached)).single())
    }
}
