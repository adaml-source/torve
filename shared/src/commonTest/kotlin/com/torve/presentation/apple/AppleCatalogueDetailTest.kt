package com.torve.presentation.apple

import com.torve.data.addon.*
import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppleCatalogueDetailTest {
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
}
