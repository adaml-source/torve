package com.torve.presentation.catalog

import com.torve.data.auth.UserIdProvider
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.settings.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WatchedDiscoveryFilterTest {
    private inline fun <reified T> stub(crossinline response: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> response(method.name) } as T
    @Test fun onlyCompletedTitlesAreHiddenAndProfileChangesCannotReuseAnotherOwnersHistory() = runBlocking {
        var owner = "parent"
        var hide = true
        var saved = listOf(WatchProgress("10", MediaType.MOVIE, "Finished", positionMs = 900, durationMs = 1000),
            WatchProgress("11", MediaType.MOVIE, "Resume", positionMs = 300, durationMs = 1000),
            WatchProgress("12", MediaType.MOVIE, "Skipped", positionMs = 950, durationMs = 1000, verifiedWatched = false))
        var history = listOf(WatchHistoryEntry("import", "20", "tv", "Series", null, null, 1, 0, 1, 1, "Series"))
        val prefs = stub<PreferencesRepository> { if (it == "getString") hide.toString() else Unit }
        val progress = stub<WatchProgressRepository> { if (it == "getAllProgress") saved else kotlinx.coroutines.flow.emptyFlow<Unit>() }
        val watched = stub<WatchHistoryRepository> { history }
        val metadata = stub<MetadataRepository> { error("Unexpected metadata lookup: $it") }
        val filter = WatchedDiscoveryFilter(prefs, progress, watched, UserIdProvider { error("Unused auth") }, metadata,
            SettingsRefreshNotifier(), currentOwner = { owner })
        fun movie(id: String) = MediaItem(id, type = MediaType.MOVIE, title = id)
        try {
            filter.refresh()
            assertFalse(filter.allows(movie("tmdb:10"), ContentAccessContext.DEFAULT_DISCOVERY))
            assertTrue(filter.allows(movie("11"), ContentAccessContext.DEFAULT_DISCOVERY))
            assertTrue(filter.allows(movie("12"), ContentAccessContext.DEFAULT_DISCOVERY))
            assertTrue(filter.allows(movie("10"), ContentAccessContext.LIBRARY_OR_WATCHLIST))
            val template = history.single()
            history = history + listOf(
                template.copy(id = "simkl_movie", mediaId = "30", mediaType = "movie", seasonNumber = null, episodeNumber = null),
                template.copy(id = "31_started", mediaId = "31", mediaType = "movie", seasonNumber = null, episodeNumber = null),
                template.copy(id = "trakt_movie", mediaId = "32", mediaType = "movie", seasonNumber = null, episodeNumber = null))
            filter.refresh()
            assertFalse(filter.allows(movie("30"), ContentAccessContext.DIRECT_SEARCH))
            assertFalse(filter.allows(movie("32"), ContentAccessContext.DEFAULT_DISCOVERY))
            assertTrue(filter.allows(movie("31"), ContentAccessContext.DEFAULT_DISCOVERY))
            assertEquals(setOf("sports:10"), WatchedDiscoveryFilter.keys("sports:10"))
            val series = MediaItem("20", type = MediaType.SERIES, title = "Series", seasons = listOf(Season(1, 2)))
            assertTrue(filter.allows(series, ContentAccessContext.DIRECT_SEARCH))
            history = history + history.first().copy(id = "import2", episodeNumber = 2)
            filter.refresh()
            assertFalse(filter.allows(series, ContentAccessContext.DIRECT_SEARCH))
            assertTrue(filter.allows(series.copy(id = "opaque", seasons = emptyList()), ContentAccessContext.DEFAULT_DISCOVERY))
            owner = "child"
            assertTrue(filter.allows(movie("10"), ContentAccessContext.DEFAULT_DISCOVERY))
            saved = emptyList(); history = emptyList(); filter.refresh()
            assertTrue(filter.allows(movie("10"), ContentAccessContext.DEFAULT_DISCOVERY))
            owner = "parent"; hide = false; filter.refresh()
            assertTrue(filter.allows(movie("10"), ContentAccessContext.DIRECT_SEARCH))
        } finally { filter.close() }
    }
}
