package com.torve.presentation.home

import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HomeStartupSnapshotTest {
    private class Saved(private val values: Map<String, String>) : PreferencesRepository {
        val reads = mutableListOf<String>()
        override suspend fun getString(key: String): String? { reads += key; return values[key] }
        override suspend fun setString(key: String, value: String) = error("Read must not rewrite saved data")
        override suspend fun remove(key: String) = error("Read must not delete saved data")
    }
    @Test fun smallSnapshotIsReadWithoutMaterializingTheCompleteCatalogue() = runTest {
        val prefs = Saved(mapOf(HOME_STARTUP_SNAPSHOT_KEY to "small", HOME_COMPLETE_SNAPSHOT_KEY to "large"))
        assertEquals("small", readHomeStartupSnapshot(prefs))
        assertEquals(listOf(HOME_STARTUP_SNAPSHOT_KEY), prefs.reads)
    }
    @Test fun anExistingInstallationKeepsItsLegacySnapshotUntilTheSmallOneIsSaved() = runTest {
        val prefs = Saved(mapOf(HOME_COMPLETE_SNAPSHOT_KEY to "existing"))
        assertEquals("existing", readHomeStartupSnapshot(prefs))
        assertEquals(listOf(HOME_STARTUP_SNAPSHOT_KEY, HOME_COMPLETE_SNAPSHOT_KEY), prefs.reads)
    }
    @Test fun startupPreviewKeepsFirstPostersAndResumeWithoutCopyingTheWholeLibrary() {
        val items = (1..80).map { id -> MediaItem(id = "tmdb:$id", type = MediaType.SERIES,
            title = "Series $id", posterUrl = "https://images.example/$id.jpg", overview = "detail ".repeat(200),
            cast = listOf(CastMember(id, "Person")), seasons = listOf(Season(1, 24)),
            familySourceKey = "extension:chosen", isContentPlaceholder = id == 1) }
        val rows = (1..84).map { CatalogShelf("row:$it", "Row $it", items) }
        val progress = (1..20).map { id -> WatchProgress(mediaId = "tmdb:$id", mediaType = MediaType.SERIES,
            title = "Series $id", positionMs = 12_345, durationMs = 100_000) }
        val original = HomeUiState(shelves = rows, addonShelves = rows, continueWatching = progress,
            watchlistItems = items, upcomingSchedule = items)
        val preview = original.compactStartupSnapshot()
        assertEquals(rows.take(4).map { it.id }, preview.addonShelves.map { it.id })
        assertTrue(preview.addonShelves.all { it.items.size == 8 })
        assertEquals(items.take(8).map { it.id }, preview.watchlistItems.map { it.id })
        assertEquals(progress.take(8), preview.continueWatching)
        assertEquals(12_345L, preview.continueWatching.first().positionMs)
        assertEquals("extension:chosen", preview.shelves.first().items.first().familySourceKey)
        assertTrue(preview.shelves.first().items.first().isContentPlaceholder)
        assertTrue(preview.shelves.first().items.all { it.cast.isEmpty() && it.seasons.isEmpty() && it.overview!!.length <= 320 })
        assertEquals(84, original.addonShelves.size)
        assertEquals(80, original.watchlistItems.size)
        assertEquals(20, original.continueWatching.size)
    }
    @Test fun unrelatedRatingAliasesDoNotInflateTheStartupPreview() {
        val item = MediaItem("tmdb:42", tmdbId = 42, type = MediaType.MOVIE, title = "Selected")
        val visible = item.ratingEnrichmentLookupKeys().first()
        val ratings = MediaRatings()
        val original = HomeUiState(shelves = listOf(CatalogShelf("first", "First", listOf(item))),
            continueWatchingRatings = mapOf(visible to ratings) + (1..4_000).associate { "unseen:$it" to ratings })
        val preview = original.compactStartupSnapshot()
        assertEquals(mapOf(visible to ratings), preview.continueWatchingRatings)
        assertEquals(4_001, original.continueWatchingRatings.size)
    }
}
