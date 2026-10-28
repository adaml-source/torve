package com.torve.presentation.catalog

import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProviderCatalogSnapshotTest {
    private val prefs = ProviderSnapshotPreferences()
    private var now = 1_000_000L
    private val movie = MediaItem("7", tmdbId = 7, title = "Saved film", type = MediaType.MOVIE,
        posterUrl = "https://example.invalid/poster.jpg", ratings = MediaRatings(imdbScore = 8f, rottenTomatoesScore = 90))
    private val state = CatalogUiState(providerId = 9, items = listOf(movie), totalPages = 8)
    private fun store(type: String = "movie") = ProviderCatalogSnapshotStore(prefs, type) { now }

    @Test fun savedProviderPageAndRatingsRestoreBeforeAnyProviderLookup() = runTest {
        val store = store(); val key = store.fingerprint(state, "US")
        store.write(9, key, "9|119", state)
        val saved = assertNotNull(store.read(9, key))
        assertEquals(listOf(movie), saved.items); assertEquals("9|119", saved.providerIds); assertEquals(8, saved.totalPages)
    }

    @Test fun countryLanguageTypeProviderAndProfileCannotReuseAnotherPage() = runTest {
        val store = store(); val key = store.fingerprint(state, "US")
        store.write(9, key, "9", state)
        assertNull(store.read(9, store.fingerprint(state, "DE")))
        assertNull(store("tv").read(9, key)); assertNull(store.read(8, key))
        prefs.setString("app_language", "GERMAN")
        assertNull(store.read(9, store.fingerprint(state, "US")))
        prefs.person = "other"; assertNull(store.read(9, key))
    }

    @Test fun changedGenreYearOrImdbMinimumNeverFlashesUnfilteredResults() = runTest {
        val store = store(); val key = store.fingerprint(state, "US")
        store.write(9, key, "9", state)
        for (filtered in listOf(state.copy(selectedGenreId = 18), state.copy(filter = CatalogFilter(year = 2024)),
            state.copy(filter = CatalogFilter(minRating = 9f)), state.copy(filter = CatalogFilter(sortBy = SortOption.RELEASE_DATE_DESC)))) {
            assertNull(store.read(9, store.fingerprint(filtered, "US")))
        }
    }

    @Test fun expiredFutureAndCorruptPagesAreMissesAndLaterPagesCannotSkipPagination() = runTest {
        val store = store(); val key = store.fingerprint(state, "US")
        store.write(9, key, "9", state)
        store.write(9, key, "9", state.copy(currentPage = 2, items = listOf(movie.copy(id = "second-page"))))
        assertEquals(movie, store.read(9, key)?.items?.single())
        now += 8L * 86_400_000; assertNull(store.read(9, key))
        now = 999_999; assertNull(store.read(9, key))
        prefs.setString("provider_catalog_snapshot_v1_movie_9", "broken"); assertNull(store.read(9, key))
    }

    @Test fun onlySixFirstPagesAreRetainedAndSuccessfulEmptyResultsReplaceStaleTitles() = runTest {
        val store = store()
        val keys = (2000..2006).map { year ->
            val page = state.copy(filter = CatalogFilter(year = year)); val key = store.fingerprint(page, "US")
            store.write(9, key, "9", page); key
        }
        assertNull(store.read(9, keys.first()))
        assertNotNull(store.read(9, keys.last()))
        store.write(9, keys.last(), "9", state.copy(items = emptyList()))
        assertTrue(assertNotNull(store.read(9, keys.last())).items.isEmpty())
    }
}

private class ProviderSnapshotPreferences : PreferencesRepository {
    var person = "first"
    private val values = mutableMapOf<Pair<String, String>, String>()
    override suspend fun getString(key: String) = values[person to key]
    override suspend fun setString(key: String, value: String) { values[person to key] = value }
    override suspend fun remove(key: String) { values.remove(person to key) }
}
