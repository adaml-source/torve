package com.torve.data.addon

import com.torve.domain.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ExtensionStreamRatingsTest {
    private fun row(id: String = "tt45368240", score: String = "9.2", votes: String = "21,386") = StremioStream(
        name = "IMDb Rating", description = "────────\n⭐  IMDb:  $score/10\n ($votes votes)\n────────",
        externalUrl = "https://www.imdb.com/title/$id/")
    private fun addon() = InstalledAddon("https://user-chosen.example/config/manifest.json",
        AddonManifest("imdb.ratings.local", "IMDb Ratings", "1.0.0", resources = listOf("stream"), types = listOf("movie", "series")))

    @Test fun readsActualNonPlayableRatingEntryAndExactVotes() {
        val result = ExtensionStreamRatings.parse(listOf(row()), "tt45368240")!!
        assertEquals(9.2f, result.imdbScore); assertEquals(21386, result.imdbVotes)
    }
    @Test fun acceptsConfiguredParentalGuideLinkAndDecimalComma() {
        val result = ExtensionStreamRatings.parse(listOf(row(score = "9,2").copy(
            externalUrl = "https://www.imdb.com/title/tt45368240/parentalguide")), "tt45368240")!!
        assertEquals(9.2f, result.imdbScore)
    }
    @Test fun rejectsWrongTitleSpoofedHostAndPlayableVideos() {
        for (entry in listOf(row("tt1234567"), row().copy(externalUrl = "https://www.imdb.com.evil.invalid/title/tt45368240/"),
            row().copy(url = "https://example.invalid/video.mp4"), row().copy(infoHash = "hash"),
            row().copy(magnet = "magnet:hash"), row().copy(externalUrl = null))) {
            assertNull(ExtensionStreamRatings.parse(listOf(entry), "tt45368240"))
        }
    }
    @Test fun rejectsOutOfRangeMissingSourceAndConflictingRatings() {
        for (score in listOf("0", "10.1", "NaN", "-9.2")) assertNull(ExtensionStreamRatings.parse(listOf(row(score = score)), "tt45368240"))
        assertNull(ExtensionStreamRatings.parse(listOf(row().copy(description = "TMDB: 9.2/10")), "tt45368240"))
        assertNull(ExtensionStreamRatings.parse(listOf(row(), row(score = "7.9")), "tt45368240"))
        assertNull(ExtensionStreamRatings.parse(emptyList(), "tt45368240"))
    }
    @Test fun neverMistakesOrdinaryStreamProvidersForRatingsExtensions() {
        assertTrue(ExtensionStreamRatings.supports(addon().manifest))
        assertFalse(ExtensionStreamRatings.supports(addon().manifest.copy(id = "torrentio", name = "Torrentio")))
        assertFalse(ExtensionStreamRatings.supports(addon().manifest.copy(resources = listOf("meta"))))
        assertFalse(ExtensionStreamRatings.supports(addon().manifest.copy(catalogs = listOf(AddonCatalog("movie", "popular")))))
    }
    @Test fun installedRatingsOverrideOnlyImdbAndPreservePlaybackIdentityAndArtwork() = runTest {
        val seed = MediaItem("tmdb:42", type = MediaType.MOVIE, title = "Deutscher Titel", imdbId = "tt45368240",
            posterUrl = "https://example.invalid/poster", ratings = MediaRatings(imdbScore = 7.9f, tmdbScore = 9.1f, rottenTomatoesScore = 97))
        val resolved = ExtensionStreamRatings.enrich(seed, listOf(addon())) { provider, type, id ->
            assertEquals("https://user-chosen.example/config/manifest.json", provider.manifestUrl)
            assertEquals("movie", type); assertEquals(seed.imdbId, id); listOf(row())
        }
        assertEquals(seed.id, resolved.id); assertEquals(seed.title, resolved.title); assertEquals(seed.posterUrl, resolved.posterUrl)
        assertEquals(9.2f, resolved.ratings?.imdbScore); assertEquals(9.1f, resolved.ratings?.tmdbScore)
        assertEquals(97, resolved.ratings?.rottenTomatoesScore)
    }
    @Test fun disabledOrMissingExtensionsNeverMakeARequestAndNoImdbMappingIsInvented() = runTest {
        val seed = MediaItem("tmdb:42", type = MediaType.MOVIE, title = "Seed")
        assertEquals(seed, ExtensionStreamRatings.enrich(seed, listOf(addon())) { _,_,_ -> error("No exact IMDb identity") })
        val imdb = seed.copy(imdbId = "tt45368240")
        assertEquals(imdb, ExtensionStreamRatings.enrich(imdb, listOf(addon().copy(isEnabled = false))) { _,_,_ -> error("Disabled") })
        assertEquals(imdb, ExtensionStreamRatings.enrich(imdb, emptyList()) { _,_,_ -> error("Not installed") })
    }
    @Test fun providerFailureIsOptionalAndDoesNotDeleteExistingMetadata() = runTest {
        val seed = MediaItem("tt45368240", type = MediaType.SERIES, title = "Seed", ratings = MediaRatings(imdbScore = 7.9f))
        assertEquals(seed, ExtensionStreamRatings.enrich(seed, listOf(addon())) { _,_,_ -> error("offline") })
    }
}
