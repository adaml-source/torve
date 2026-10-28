package com.torve.presentation.catalog

import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class CatalogDiscoverySnapshotTest {
    private val prefs = MemoryDiscoveryPreferences()
    private var now = 1_000_000L
    private val movie = MediaItem("7", tmdbId = 7, title = "Saved movie", type = MediaType.MOVIE,
        posterUrl = "poster", backdropUrl = "backdrop", ratings = MediaRatings(imdbScore = 8f))
    private fun store(type: String = "movie") = CatalogDiscoverySnapshotStore(prefs, type, { now })

    @Test fun restoredFirstPageNeedsNoMetadataOrRatingsRequests() = runTest {
        store().write(CatalogUiState(trendingItems = listOf(movie), popularItems = listOf(movie), topRatedItems = listOf(movie)))
        val saved = assertNotNull(store().read())
        assertEquals(movie, saved.trending.single())
        assertEquals("backdrop", saved.popular.single().backdropUrl)
        assertEquals(8f, saved.topRated.single().ratings?.imdbScore)
    }

    @Test fun movieAndTvAndProfileCopiesAreIsolated() = runTest {
        store().write(CatalogUiState(trendingItems = listOf(movie)))
        assertNull(store("tv").read())
        prefs.person = "B"; assertNull(store().read())
        prefs.person = "A"; assertEquals(movie, store().read()?.trending?.single())
    }

    @Test fun languageChangeDoesNotRestoreThePreviousLanguage() = runTest {
        prefs.setString("app_language", "ENGLISH")
        store().write(CatalogUiState(trendingItems = listOf(movie)))
        prefs.setString("app_language", "GERMAN")
        assertNull(store().read())
    }

    @Test fun expiredFutureAndCorruptSnapshotsAreMisses() = runTest {
        store().write(CatalogUiState(trendingItems = listOf(movie)))
        now += 8L * 24 * 60 * 60 * 1000; assertNull(store().read())
        now = 999_999L; assertNull(store().read())
        prefs.setString("catalog_discovery_snapshot_v1_movie", "broken"); assertNull(store().read())
    }

    @Test fun upgradeReusesSavedHomeRowsBeforeAnotherCatalogueFetch() = runTest {
        val shelves = listOf(CatalogShelf("trending-movies", "Trending Movies", listOf(movie)),
            CatalogShelf("popular-movies", "Popular Movies", listOf(movie)),
            CatalogShelf("trending-tv", "Trending TV", listOf(movie.copy(id = "9", type = MediaType.SERIES))))
        prefs.setString("home_snapshot_v5", buildJsonObject {
            put("savedAtMs", now);put("shelves", Json.encodeToJsonElement(shelves))
        }.toString())
        assertEquals(movie, assertNotNull(store().read()).trending.single())
        assertEquals(MediaType.SERIES, assertNotNull(store("tv").read()).trending.single().type)
    }

    @Test fun successfulEmptyResponseReplacesOldRowsAndProviderPagesDoNotOverwriteDiscovery() = runTest {
        store().write(CatalogUiState(trendingItems = listOf(movie)))
        store().write(CatalogUiState(providerId = 8, trendingItems = emptyList()))
        assertEquals(listOf(movie), store().read()?.trending)
        store().write(CatalogUiState(shelvesLoaded = true))
        assertTrue(assertNotNull(store().read()).trending.isEmpty())
    }
}

private class MemoryDiscoveryPreferences : PreferencesRepository {
    var person = "A"
    private val values = mutableMapOf<Pair<String, String>, String>()
    override suspend fun getString(key: String) = values[person to key]
    override suspend fun setString(key: String, value: String) { values[person to key] = value }
    override suspend fun remove(key: String) { values.remove(person to key) }
}
