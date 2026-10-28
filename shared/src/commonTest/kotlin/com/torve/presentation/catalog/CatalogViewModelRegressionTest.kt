package com.torve.presentation.catalog

import com.torve.data.metadata.TmdbKeyword
import com.torve.data.metadata.TmdbPerson
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogViewModelRegressionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun moviesLoadsContentForEligibleUser() = runTest(dispatcher) {
        val movieItem = mediaItem(id = "movie-1", title = "Movie A", type = MediaType.MOVIE)
        val viewModel = CatalogViewModel(
            metadataRepo = FakeMetadataRepository(
                trendingMoviePage = PagedResult(items = listOf(movieItem), page = 1, totalPages = 1, totalResults = 1),
            ),
            mediaType = "movie",
        )

        advanceUntilIdle()

        assertEquals(listOf(movieItem), viewModel.state.value.items)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun tvShowsLoadContentForEligibleUser() = runTest(dispatcher) {
        val showItem = mediaItem(id = "show-1", title = "Show A", type = MediaType.SERIES)
        val viewModel = CatalogViewModel(
            metadataRepo = FakeMetadataRepository(
                trendingTvPage = PagedResult(items = listOf(showItem), page = 1, totalPages = 1, totalResults = 1),
            ),
            mediaType = "tv",
        )

        advanceUntilIdle()

        assertEquals(listOf(showItem), viewModel.state.value.items)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun contentLoaderSurfacesErrorStateWhenRepositoryFails() = runTest(dispatcher) {
        val viewModel = CatalogViewModel(
            metadataRepo = FakeMetadataRepository(
                trendingFailure = IllegalStateException("catalog backend down"),
            ),
            mediaType = "movie",
        )

        advanceUntilIdle()

        assertEquals("Movies could not be loaded. Please try again.", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    @Test
    fun genreChangesKeepVisibleItemsUntilReplacementArrives() = runTest(dispatcher) {
        for (type in listOf("movie", "tv")) {
            val original = mediaItem("movie-1", "Original", MediaType.MOVIE)
            val replacement = mediaItem("movie-2", "Filtered", MediaType.MOVIE)
            val page = PagedResult(listOf(original), 1, 1, 1)
            val vm = CatalogViewModel(FakeMetadataRepository(trendingMoviePage = page, trendingTvPage = page,
                discoverPage = PagedResult(listOf(replacement), 1, 1, 1), discoverDelayMs = 1000), type)
            advanceUntilIdle()
            vm.selectGenre(28)
            runCurrent()
            assertTrue(vm.state.value.isLoading)
            assertEquals(listOf(original), vm.state.value.items)
            advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertEquals(listOf(replacement), vm.state.value.items)
            vm.close()
        }
    }

    @Test
    fun rapidFilterChangesOnlyPublishTheLatestResult() = runTest(dispatcher) {
        val original = mediaItem("movie-1", "Original", MediaType.MOVIE)
        val replacement = mediaItem("movie-2", "Filtered", MediaType.MOVIE)
        val vm = CatalogViewModel(FakeMetadataRepository(trendingMoviePage = PagedResult(listOf(original), 1, 1, 1),
            discoverPage = PagedResult(listOf(replacement), 1, 1, 1), discoverDelayMs = 1000), "movie")
        advanceUntilIdle()
        vm.selectGenre(28)
        runCurrent()
        vm.selectGenre(null)
        advanceUntilIdle()
        assertEquals(listOf(original), vm.state.value.items)
        assertEquals(null, vm.state.value.selectedGenreId)
        vm.close()
    }

    @Test fun readyTrendingDoesNotWaitForSlowTopRated() = runTest(dispatcher) {
        val ready = mediaItem("movie-7", "Ready trending", MediaType.MOVIE)
        val blocker = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = CatalogViewModel(FakeMetadataRepository(shelfTrending = listOf(ready), shelfBlocker = blocker),
            "movie", watchProgressRepo = EmptyProgress())
        try {
            runCurrent()
            assertEquals(listOf(ready), vm.state.value.trendingItems)
            assertFalse(vm.state.value.shelvesLoaded)
        } finally { vm.close() }
    }

    @Test fun reopeningDiscoveryPublishesSavedArtworkWhileEveryNetworkShelfIsBlocked() = runTest(dispatcher) {
        val movie = mediaItem("movie-8", "Saved movie", MediaType.MOVIE).copy(posterUrl = "saved-poster", backdropUrl = "saved-backdrop")
        val preferences = object : com.torve.domain.repository.PreferencesRepository {
            val values = mutableMapOf<String, String>()
            override suspend fun getString(key: String) = values[key]
            override suspend fun setString(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        }
        CatalogDiscoverySnapshotStore(preferences, "movie").write(CatalogUiState(trendingItems = listOf(movie)))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blocker = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = CatalogViewModel(FakeMetadataRepository(allShelvesBlocker = blocker, shelvesStarted = started),
            "movie", watchProgressRepo = EmptyProgress(), prefsRepo = preferences)
        try {
            started.await()
            assertEquals(movie, vm.state.value.trendingItems.single())
            assertTrue(vm.state.value.shelvesLoaded)
            assertFalse(blocker.isCompleted)
        } finally { vm.close() }
    }

    @Test fun higherRatedNextPageAppendsWithoutMovingVisiblePosters() = runTest(dispatcher) {
        val first = mediaItem("1", "Visible", MediaType.MOVIE).copy(ratings = com.torve.domain.model.MediaRatings(imdbScore = 6f))
        val second = mediaItem("2", "New high score", MediaType.MOVIE).copy(ratings = com.torve.domain.model.MediaRatings(imdbScore = 9f))
        val repo = object : MetadataRepository by FakeMetadataRepository() {
            override suspend fun getTrendingPaged(type: String, page: Int) = PagedResult(listOf(if(page==1) first else second),page,2,2)
        }
        val vm = CatalogViewModel(repo,"movie")
        try {
            advanceUntilIdle()
            assertEquals(listOf("1"),vm.state.value.items.map { it.id })
            vm.loadMore()
            kotlinx.coroutines.withTimeout(5_000) { vm.state.first { it.items.size == 2 } }
            assertEquals(listOf("1","2"),vm.state.value.items.map { it.id })
        } finally { vm.close() }
    }
    private class FakeMetadataRepository(
        private val trendingMoviePage: PagedResult = PagedResult(emptyList(), 1, 1, 0),
        private val trendingTvPage: PagedResult = PagedResult(emptyList(), 1, 1, 0),
        private val trendingFailure: Throwable? = null,
        private val discoverPage: PagedResult = PagedResult(emptyList(), 1, 1, 0),
        private val discoverDelayMs: Long = 0,
        private val shelfTrending: List<MediaItem> = emptyList(),
        private val shelfBlocker: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
        private val allShelvesBlocker: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
        private val shelvesStarted: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : MetadataRepository {
        override suspend fun getTrending(type: String, page: Int): List<MediaItem> { allShelvesBlocker?.await(); return shelfTrending }
        override suspend fun getPopular(type: String, page: Int): List<MediaItem> { allShelvesBlocker?.await(); return emptyList() }
        override suspend fun getTopRated(type: String, page: Int): List<MediaItem> {
            shelvesStarted?.complete(Unit); allShelvesBlocker?.await(); shelfBlocker?.await(); return emptyList()
        }
        override suspend fun getUpcoming(page: Int): List<MediaItem> = emptyList()
        override suspend fun getNowPlaying(page: Int): List<MediaItem> = emptyList()
        override suspend fun getAiringToday(page: Int): List<MediaItem> = emptyList()
        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun findByImdbId(imdbId: String, preferredType: String?): MediaItem? = null
        override suspend fun getDetail(type: String, id: Int): MediaItem = error("unused")
        override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
        override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
        override suspend fun getHomeShelves(): List<CatalogShelf> = emptyList()
        override suspend fun getPersonCredits(personId: Int): List<MediaItem> = emptyList()
        override suspend fun getPersonDetail(personId: Int): TmdbPerson = error("unused")
        override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season = error("unused")

        override suspend fun getTrendingPaged(type: String, page: Int): PagedResult {
            trendingFailure?.let { throw it }
            return if (type == "tv") trendingTvPage else trendingMoviePage
        }

        override suspend fun getPopularPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun discover(
            type: String,
            page: Int,
            sortBy: String,
            withGenres: String?,
            minRating: Float?,
            year: Int?,
            yearTo: Int?,
            runtimeGte: Int?,
            runtimeLte: Int?,
            originCountries: String?,
            originalLanguage: String?,
            certification: String?,
            certificationGte: String?,
            certificationLte: String?,
            certificationCountry: String?,
            withCast: String?,
            withCrew: String?,
            withWatchProviders: String?,
            watchRegion: String?,
            withKeywords: String?,
            withCompanies: String?,
        ): PagedResult {
            kotlinx.coroutines.delay(discoverDelayMs)
            return discoverPage
        }

        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult =
            PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }

    private class EmptyProgress : com.torve.domain.repository.WatchProgressRepository {
        override suspend fun getInProgress(limit: Long) = emptyList<com.torve.domain.model.WatchProgress>()
        override suspend fun getProgress(mediaId: String): com.torve.domain.model.WatchProgress? = null
        override suspend fun saveProgress(progress: com.torve.domain.model.WatchProgress) { }
        override suspend fun getAllProgress() = emptyList<com.torve.domain.model.WatchProgress>()
        override suspend fun deleteProgress(mediaId: String) { }
        override suspend fun clearAllProgress() { }
        override suspend fun syncFromTrakt() { }
    }

    private fun mediaItem(id: String, title: String, type: MediaType): MediaItem {
        return MediaItem(
            id = id,
            tmdbId = id.substringAfterLast('-').toIntOrNull(),
            title = title,
            type = type,
        )
    }
}
