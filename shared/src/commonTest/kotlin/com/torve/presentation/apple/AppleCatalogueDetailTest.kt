package com.torve.presentation.apple

import com.torve.data.addon.*
import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppleCatalogueDetailTest {
    @Test fun tmdbAddonCatalogueRatingIsTmdbWhileDetailAddsSeparateImdb() = runTest {
        val seed = AppleCatalogueDetail.decode(AppleCatalogueDetail.extensionCatalogueSeed(
            """{"id":"tt45368240","type":"movie","name":"You Can See Everything","imdbRating":"9.1"}""", "movie", "tmdb-addon"))
        assertEquals(9.1f, seed.ratings?.tmdbScore)
        assertNull(seed.ratings?.imdbScore)
        val result = AppleCatalogueDetail.resolve(seed, listOf(addon("metadata")),
            { _, _, id -> StremioMeta(id, "movie", seed.title, imdbRating = "7.9") }, { null }, extensionAuthoritative = true)
        assertEquals(7.9f, result.ratings?.imdbScore)
        assertEquals(9.1f, result.ratings?.tmdbScore)
        assertEquals(seed.id, result.id)
    }

    @Test fun ordinaryExtensionsAndExplicitRatingsKeepTheirDeclaredSources() {
        val raw = """{"id":"tt41368989","type":"movie","name":"Finding Harry","imdbRating":"7.7"}"""
        val ordinary = AppleCatalogueDetail.decode(AppleCatalogueDetail.extensionCatalogueSeed(raw, "movie", "other-addon"))
        assertEquals(7.7f, ordinary.ratings?.imdbScore); assertNull(ordinary.ratings?.tmdbScore)
        val explicit = raw.dropLast(1) + """, "torve_ratings":{"imdbScore":6.9,"tmdbScore":7.7}}"""
        val item = AppleCatalogueDetail.decode(AppleCatalogueDetail.extensionCatalogueSeed(explicit, "movie", "tmdb-addon"))
        assertEquals(6.9f, item.ratings?.imdbScore); assertEquals(7.7f, item.ratings?.tmdbScore)
        assertEquals(7.7f, AppleCatalogueDetail.decode(AppleCatalogueDetail.extensionSeed(raw, "movie")).ratings?.imdbScore)
    }

    private val film = MediaItem("tt0000001", imdbId = "tt0000001", type = MediaType.MOVIE,
        title = "Catalogue film", posterUrl = "https://example.invalid/poster.jpg")
    private fun addon(id: String, resources: List<String> = listOf("meta"), enabled: Boolean = true,
                      prefixes: List<String> = emptyList()) = InstalledAddon("https://$id.example/manifest.json",
        AddonManifest(id, id, "1", resources = resources, types = listOf("movie", "series"), idPrefixes = prefixes), isEnabled = enabled)

    @Test fun catalogueOnlyAddonRemainsUsableWithoutTmdbOrAMetadataProvider() = runTest {
        var fetched = false
        val seed = AppleCatalogueDetail.decode(AppleCatalogueDetail.encode(film))
        val result = AppleCatalogueDetail.resolve(seed, listOf(addon("catalogue", listOf("catalog"))),
            { _, _, _ -> fetched = true; null }, { null })
        assertEquals(film, result)
        assertFalse(fetched)
        assertNull(AppleDetailNavigation.catalogId(result.id))
    }

    @Test fun imdbLookupAddsDetailsWithoutChangingTheSelectedPlaybackIdOrArtwork() = runTest {
        val result = AppleCatalogueDetail.resolve(film, emptyList(), { _, _, _ -> error("No provider") }, {
            assertEquals("tt0000001", it.id)
            MediaItem("42", tmdbId = 42, imdbId = film.imdbId, type = MediaType.MOVIE, title = "Localized title",
                posterUrl = "https://tmdb.example/different.jpg", overview = "Full description", trailerKey = "trailer")
        })
        assertEquals(film.id, result.id); assertEquals(film.title, result.title)
        assertEquals(film.posterUrl, result.posterUrl); assertEquals(42, result.tmdbId)
        assertEquals("Full description", result.overview); assertEquals("trailer", result.trailerKey)
    }

    @Test fun opaqueSeriesUsesInstalledMetadataAndRetainsEpisodesEvenWhenTmdbIsUnavailable() = runTest {
        val seed = MediaItem("provider:show", type = MediaType.SERIES, title = "Show")
        val requests = mutableListOf<String>()
        val result = AppleCatalogueDetail.resolve(seed, listOf(addon("disabled", enabled = false),
            addon("wrong-prefix", prefixes = listOf("tt")), addon("metadata")), { provider, type, id ->
            requests += provider.manifest.id
            assertEquals("series", type); assertEquals(seed.id, id)
            StremioMeta(id = id, name = "Show", description = "Provider detail", videos = listOf(
                StremioVideo("provider:show:1:2", "Second episode", season = 1, episode = 2),
                StremioVideo("provider:show:1:1", "First episode", season = 1, episode = 1)))
        }, { throw IllegalStateException("TMDB unavailable") })
        assertEquals(listOf("metadata"), requests)
        assertEquals(seed.id, result.id); assertEquals(MediaType.SERIES, result.type)
        assertEquals("Provider detail", result.overview)
        assertEquals(listOf(1, 2), result.seasons.single().episodes.map { it.episodeNumber })
    }

    @Test fun failedOrMismatchedProvidersCannotReplaceTheSelectedTitle() = runTest {
        val result = AppleCatalogueDetail.resolve(film, listOf(addon("offline"), addon("wrong-id"), addon("wrong-type")),
            { addon, _, id -> when (addon.manifest.id) {
                "offline" -> throw IllegalStateException("Offline")
                "wrong-id" -> StremioMeta("tt9999999", "movie", "Wrong title")
                else -> StremioMeta(id, "series", "Wrong type")
            } }, { null })
        assertEquals(film, result)
    }

    @Test fun cancellationDoesNotLoadThePreviousAccountsSelection() = runTest {
        assertFailsWith<CancellationException> {
            AppleCatalogueDetail.resolve(film, listOf(addon("metadata")),
                { _, _, _ -> throw CancellationException("Account changed") }, { null })
        }
    }

    @Test fun nativeLocaleReplacesOldEnglishCardTextWithoutWaitingForInstalledProviders() = runTest {
        val english = film.copy(tmdbId = 42, overview = "English plot")
        val result = AppleCatalogueDetail.resolve(english, listOf(addon("slow")),
            { _, _, _ -> error("Native identity must not wait for an extension") },
            { english.copy(id = "42", title = "Deutscher Titel", overview = "Deutsche Handlung") }, nativeFirst = true)
        assertEquals(english.id, result.id)
        assertEquals("Deutscher Titel", result.title)
        assertEquals("Deutsche Handlung", result.overview)
        assertEquals(42, result.tmdbId)
    }

    @Test fun nativeIdentityMismatchCannotReplaceTheSelectedTitle() = runTest {
        val selected = film.copy(tmdbId = 42)
        val result = AppleCatalogueDetail.resolve(selected, emptyList(), { _, _, _ -> null },
            { selected.copy(tmdbId = 999, title = "Wrong title") }, nativeFirst = true)
        assertEquals(selected, result)
    }
}
