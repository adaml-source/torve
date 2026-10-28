package com.torve.android.tv.components

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TvLocalizedPresentationTest {
    private val original = MediaItem("provider:godfather", tmdbId = 238, imdbId = "tt0068646",
        type = MediaType.MOVIE, title = "The Godfather", posterUrl = "english.jpg", overview = "English overview",
        ratings = MediaRatings(imdbScore = 9.2f), year = 1972)
    @Test fun translatedPresentationKeepsProviderIdentityAndRatings() {
        val translated = MediaItem("238", type = MediaType.MOVIE, title = "Der Pate", posterUrl = "german.jpg", overview = "Deutsche Beschreibung")
        val result = original.withTvLocalizedPresentation(translated)
        assertEquals("Der Pate", result.title)
        assertEquals("german.jpg", result.posterUrl)
        assertEquals("Deutsche Beschreibung", result.overview)
        assertEquals(original.id, result.id)
        assertEquals(original.tmdbId, result.tmdbId)
        assertEquals(original.imdbId, result.imdbId)
        assertEquals(original.ratings, result.ratings)
        assertEquals(original.year, result.year)
    }
    @Test fun unavailableTranslationAndArtworkKeepTheOriginal() {
        val empty = MediaItem("238", type = MediaType.MOVIE, title = "", posterUrl = " ", overview = "")
        assertEquals(original, original.withTvLocalizedPresentation(empty))
    }
    @Test fun translatedShowTitleRetainsTheRowsSeasonAndEpisode() {
        val episode = original.copy(type = MediaType.SERIES, title = "S3 E2 · Slow Horses")
        val translated = episode.copy(id = "95396", title = "Slow Horses – Ein Fall für Jackson Lamb")
        val result = episode.withTvLocalizedPresentation(translated)
        assertEquals("S03E02", result.tvPreviewEpisodeCode())
        assertEquals(translated.title, result.tvPreviewShowTitle())
        assertEquals(episode.id, result.id)
        val calendar = episode.copy(id = "trakt-calendar:95396:3:2", title = "Slow Horses")
        assertEquals("S03E02", calendar.withTvLocalizedPresentation(translated).tvPreviewEpisodeCode())
    }
    @Test fun cachedPresentationIsSeparatedByLanguageAndProfile() {
        val english = tvLocalizedPresentationKey(original, "profile-a", "en")
        assertNotEquals(english, tvLocalizedPresentationKey(original, "profile-a", "de"))
        assertNotEquals(english, tvLocalizedPresentationKey(original, "profile-b", "en"))
        assertNotEquals(english, tvLocalizedPresentationKey(original.copy(type = MediaType.SERIES), "profile-a", "en"))
    }
    @Test fun disablingMetadataEnrichmentKeepsTheProviderPresentation() {
        cacheTvLocalizedPresentation(tvLocalizedPresentationKey(original, "off-test", "de"), original.copy(title = "Der Pate", posterUrl = "german.jpg"))
        assertEquals("Der Pate", localizedTvPresentation(original, "off-test", "de").title)
        assertEquals(original, localizedTvPresentation(original, "off-test", "de", enabled = false))
    }
}
