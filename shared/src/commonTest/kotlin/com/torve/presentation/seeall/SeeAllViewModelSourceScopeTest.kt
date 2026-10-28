package com.torve.presentation.seeall

import com.torve.data.metadata.TmdbKeyword
import com.torve.data.metadata.TmdbPerson
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.WatchlistItem
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistMutationResult
import com.torve.domain.repository.WatchlistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeeAllViewModelSourceScopeTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SeeAllViewModel.pendingItems.clear()
    }

    @AfterTest
    fun tearDown() {
        SeeAllViewModel.pendingItems.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun watchlistSeeAllStaysWatchlistOnlyAfterImdbSort() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        val watchlist = FakeWatchlistRepository(
            items = listOf(
                watchlistItem("watch-a", 101, "Watch A"),
                watchlistItem("watch-b", 102, "Watch B"),
            ),
        )
        val viewModel = viewModel(metadata = metadata, watchlist = watchlist)

        viewModel.loadSection("watchlist")
        advanceUntilIdle()
        viewModel.setSortMode(SeeAllSortMode.IMDB_DESC)
        advanceUntilIdle()

        assertEquals(setOf("watch-a", "watch-b"), viewModel.state.value.displayedItems.map { it.id }.toSet())
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun shelfSeeAllFiltersSnapshotWithoutGlobalRefill() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        SeeAllViewModel.pendingItems["addon-row"] = "Addon Row" to listOf(
            media("addon-a", "Addon A", genreIds = listOf(28)),
            media("addon-b", "Addon B", genreIds = listOf(35)),
        )
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection("shelf:addon-row")
        advanceUntilIdle()
        viewModel.toggleGenre(99)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.displayedItems.isEmpty())
        assertEquals(listOf("addon-a", "addon-b"), viewModel.state.value.items.map { it.id })
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun favoritesSeeAllUsesSnapshotOnlyAfterSortAndEmptyFilter() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        SeeAllViewModel.pendingItems["favorites"] = "Favorites" to listOf(
            media("favorite-a", "Favorite A", ratings = MediaRatings(imdbScore = 8.7f), genreIds = listOf(28)),
            media("favorite-b", "Favorite B", ratings = MediaRatings(imdbScore = 7.1f), genreIds = listOf(35)),
        )
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection("shelf:favorites")
        advanceUntilIdle()
        viewModel.setSortMode(SeeAllSortMode.IMDB_DESC)
        viewModel.toggleGenre(99)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.displayedItems.isEmpty())
        assertEquals(listOf("favorite-a", "favorite-b"), viewModel.state.value.items.map { it.id })
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun continueWatchingSeeAllUsesProgressSourceOnly() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        val progress = FakeWatchProgressRepository(
            items = listOf(
                WatchProgress(
                    mediaId = "tmdb:301",
                    mediaType = MediaType.MOVIE,
                    title = "Progress Movie",
                    updatedAt = 20L,
                ),
            ),
        )
        val viewModel = viewModel(metadata = metadata, watchProgress = progress)

        viewModel.loadSection("continue_watching")
        advanceUntilIdle()
        viewModel.setSortMode(SeeAllSortMode.YEAR_DESC)
        advanceUntilIdle()

        assertEquals(listOf("tmdb:301"), viewModel.state.value.displayedItems.map { it.id })
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun recentlyWatchedSeeAllUsesHistorySourceOnly() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        val history = FakeWatchHistoryRepository(
            items = listOf(
                WatchHistoryEntry(
                    id = "h1",
                    mediaId = "tmdb:401",
                    mediaType = "movie",
                    title = "Recent Movie",
                    posterUrl = null,
                    backdropUrl = null,
                    watchedAt = 20L,
                    durationWatchedMs = 100L,
                    seasonNumber = null,
                    episodeNumber = null,
                    showTitle = null,
                ),
            ),
        )
        val viewModel = viewModel(metadata = metadata, history = history)

        viewModel.loadSection("recently_watched")
        advanceUntilIdle()
        viewModel.setSortMode(SeeAllSortMode.A_Z)
        advanceUntilIdle()

        assertEquals(listOf("tmdb:401"), viewModel.state.value.displayedItems.map { it.id })
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun recentlyWatchedExcludesRawSportsAndFilesBeforeLimitingOrResolvingIds() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        val movie = WatchHistoryEntry(
            id = "movie-history", mediaId = "tmdb:401", mediaType = "movie", title = "Recent Movie",
            posterUrl = null, backdropUrl = null, watchedAt = 20L, durationWatchedMs = 100L,
            seasonNumber = null, episodeNumber = null, showTitle = null,
        )
        val rawFiles = (1..90).map { index -> movie.copy(
            id = "sports-history-$index", mediaId = "sports:$index",
            title = "upload/Fussball.Premier.League.2026.mkv",
        ) } + listOf(
            movie.copy(id = "nzb-history", mediaId = "direct:401", title = "upload/Faultierland.20170809.NF.mkv"),
            movie.copy(id = "webdav-history", mediaId = "webdav:401", title = "upload/File.mkv"),
        )
        val history = FakeWatchHistoryRepository(rawFiles + movie)
        val viewModel = viewModel(metadata = metadata, history = history)

        viewModel.loadSection("recently_watched")
        advanceUntilIdle()

        assertEquals(listOf("tmdb:401"), viewModel.state.value.displayedItems.map { it.id })
        assertEquals(rawFiles.size + 1, history.getAll().size)
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
        viewModel.close()
    }

    @Test
    fun continueWatchingSeeAllAlsoExcludesFileAndSportsBookmarks() = runTest(dispatcher) {
        val movie = WatchProgress(mediaId = "tmdb:301", mediaType = MediaType.MOVIE,
            title = "Progress Movie", updatedAt = 20L)
        val progress = FakeWatchProgressRepository(listOf(
            movie.copy(mediaId = "sports:301", title = "upload/Fussball.mkv"),
            movie.copy(mediaId = "webdav:301", title = "upload/File.mkv"),
            movie.copy(mediaId = "direct:301", title = "upload/Direct.mkv"),
            movie,
        ))
        val viewModel = viewModel(metadata = ScopedMetadataRepository(), watchProgress = progress)
        viewModel.loadSection("continue_watching")
        advanceUntilIdle()

        assertEquals(listOf("tmdb:301"), viewModel.state.value.displayedItems.map { it.id })
        assertEquals(4, progress.getAllProgress().size)
        viewModel.close()
    }

    @Test
    fun upcomingSeeAllUsesPendingScheduleSnapshotOnly() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository()
        SeeAllViewModel.pendingItems["upcoming_schedule"] = "Upcoming Schedule" to listOf(
            media("trakt-calendar:show:1:1:2026-06-01", "Show - S1E1", type = MediaType.SERIES),
            media("trakt-calendar:show:1:2:2026-06-08", "Show - S1E2", type = MediaType.SERIES),
        )
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection("upcoming_schedule")
        advanceUntilIdle()
        viewModel.setSortMode(SeeAllSortMode.YEAR_ASC)
        advanceUntilIdle()

        assertEquals(
            listOf("trakt-calendar:show:1:1:2026-06-01", "trakt-calendar:show:1:2:2026-06-08"),
            viewModel.state.value.displayedItems.map { it.id },
        )
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun trendingSeeAllPaginatesTrendingSourceWithoutGenericDiscovery() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository(
            trendingMoviePages = mapOf(
                1 to PagedResult(listOf(media("trend-1", "Trend 1")), page = 1, totalPages = 2, totalResults = 2),
                2 to PagedResult(listOf(media("trend-2", "Trend 2")), page = 2, totalPages = 2, totalResults = 2),
            ),
        )
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection("TRENDING_MOVIES")
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("trend-1", "trend-2"), viewModel.state.value.items.map { it.id })
        assertEquals(listOf("movie:1", "movie:2"), metadata.trendingPagedCalls)
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun pagingRetainsVisibleCardsAndCoalescesRepeatedBoundaryRequests() = runTest(dispatcher) {
        val first = (1..20).map { media("first-$it", "First $it") }
        val gate = CompletableDeferred<Unit>()
        val metadata = ScopedMetadataRepository(trendingMoviePages = mapOf(
            1 to PagedResult(first, 1, 2, 22),
            2 to PagedResult(listOf(first.last(), media("next", "Next"), media("next", "Duplicate")), 2, 2, 22),
        ), pageGate = gate)
        val vm = viewModel(metadata)
        vm.loadSection("TRENDING_MOVIES"); runCurrent()
        repeat(10) { vm.loadMore() }; runCurrent()
        assertEquals(first, vm.state.value.items)
        assertTrue(vm.state.value.isLoading)
        assertEquals(listOf("movie:1", "movie:2"), metadata.trendingPagedCalls)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(first.map { it.id } + "next", vm.state.value.items.map { it.id })
        assertEquals(false, vm.state.value.isLoading)
        assertEquals(false, vm.state.value.hasMore)
        vm.close()
    }

    @Test
    fun aLatePageFromPreviousSectionCannotAppendToTheNewGrid() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val metadata = ScopedMetadataRepository(trendingMoviePages = mapOf(
            1 to PagedResult((1..20).map { media("old-$it", "Old $it") }, 1, 2, 21),
            2 to PagedResult(listOf(media("late", "Late old page")), 2, 2, 21),
        ), popularMoviePages = mapOf(1 to PagedResult(listOf(media("new", "New section")), 1, 1, 1)), pageGate = gate)
        val vm = viewModel(metadata)
        vm.loadSection("TRENDING_MOVIES"); runCurrent()
        vm.loadMore(); runCurrent()
        vm.loadSection("POPULAR_MOVIES"); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("POPULAR_MOVIES", vm.state.value.sectionId)
        assertEquals(listOf("new"), vm.state.value.items.map { it.id })
        assertEquals(false, vm.state.value.isLoading)
        vm.close()
    }

    @Test
    fun aFailedAppendKeepsThePageAvailableForExplicitRetry() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository(trendingMoviePages = mapOf(
            1 to PagedResult((1..20).map { media("item-$it", "Item $it") }, 1, 2, 21),
            2 to PagedResult(listOf(media("next", "Next")), 2, 2, 21),
        ), failNextPage = true)
        val vm = viewModel(metadata)
        vm.loadSection("TRENDING_MOVIES"); runCurrent()
        vm.loadMore(); runCurrent()
        assertEquals(20, vm.state.value.items.size)
        assertEquals(2, vm.state.value.page)
        assertEquals(false, vm.state.value.isLoading)
        assertTrue(vm.state.value.hasMore)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(21, vm.state.value.items.size)
        assertEquals(3, vm.state.value.page)
        vm.close()
    }

    @Test
    fun popularSeeAllPaginatesPopularSourceWithoutGenericDiscovery() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository(
            popularMoviePages = mapOf(
                1 to PagedResult(listOf(media("popular-1", "Popular 1")), page = 1, totalPages = 2, totalResults = 2),
                2 to PagedResult(listOf(media("popular-2", "Popular 2")), page = 2, totalPages = 2, totalResults = 2),
            ),
        )
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection("POPULAR_MOVIES")
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("popular-1", "popular-2"), viewModel.state.value.items.map { it.id })
        assertEquals(listOf("movie:1", "movie:2"), metadata.popularPagedCalls)
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun genreSeeAllContinuesGenreDiscoveryOnly() = runTest(dispatcher) {
        val metadata = ScopedMetadataRepository(
            discoverPages = mapOf(
                "movie:28:1" to PagedResult(listOf(media("genre-1", "Genre 1")), page = 1, totalPages = 2, totalResults = 2),
                "movie:28:2" to PagedResult(listOf(media("genre-2", "Genre 2")), page = 2, totalPages = 2, totalResults = 2),
            ),
        )
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection("MOVIE_GENRE_28")
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("genre-1", "genre-2"), viewModel.state.value.items.map { it.id })
        assertEquals(listOf("movie:28:1", "movie:28:2"), metadata.discoverPageCalls)
        assertEquals(2, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun moreLikeSeeAllLoadsSuggestionsForTheSelectedWatchedPoster() = runTest(dispatcher) {
        val seed = 391312
        val duplicate = media("duplicate", "Duplicate", tmdbId = 501)
        val metadata = ScopedMetadataRepository(
            recommendationPages = mapOf(
                "movie:$seed:1" to listOf(
                    media("seed", "Seed", tmdbId = seed),
                    media("recommendation", "Recommendation", tmdbId = 500),
                    duplicate,
                ),
            ),
            similarPages = mapOf(
                "movie:$seed:1" to listOf(
                    duplicate.copy(id = "duplicate-from-similar"),
                    media("similar", "Similar", tmdbId = 502),
                ),
            ),
        )
        val sectionId = "more_like_movie_$seed"
        SeeAllViewModel.pendingItems[sectionId] = "Because You Watched Eddington" to emptyList()
        val viewModel = viewModel(metadata = metadata)

        viewModel.loadSection(sectionId)
        advanceUntilIdle()

        assertEquals("Because You Watched Eddington", viewModel.state.value.title)
        assertEquals(listOf(500, 501, 502), viewModel.state.value.items.map { it.tmdbId })
        assertEquals(listOf("movie:$seed:1"), metadata.recommendationCalls)
        assertEquals(listOf("movie:$seed:1"), metadata.similarCalls)
        assertEquals(0, metadata.discoverCalls)
        assertEquals(0, metadata.searchCalls)
    }

    @Test
    fun moreLikeRouteRequiresARealTmdbSourceIdentity() {
        assertEquals(MoreLikeSource("movie", 101), parseMoreLikeSectionId("more_like_movie_101"))
        assertEquals(MoreLikeSource("tv", 202), parseMoreLikeSectionId("more_like_tv_202"))
        assertEquals(null, parseMoreLikeSectionId("more_like_movie_0"))
        assertEquals(null, parseMoreLikeSectionId("more_like_person_101"))
    }

    @Test
    fun missingImdbRatingsSortLastWithoutChangingSourceSet() {
        val rated = media("rated", "Rated", ratings = MediaRatings(imdbScore = 8.4f))
        val missing = media("missing", "Missing")

        val sorted = applySortAndFilter(
            items = listOf(missing, rated),
            sortMode = SeeAllSortMode.IMDB_DESC,
            yearFrom = null,
            yearTo = null,
            genreIds = emptySet(),
        )

        assertEquals(listOf("rated", "missing"), sorted.map { it.id })
    }

    private fun viewModel(
        metadata: ScopedMetadataRepository = ScopedMetadataRepository(),
        watchlist: WatchlistRepository = FakeWatchlistRepository(),
        watchProgress: WatchProgressRepository = FakeWatchProgressRepository(),
        history: WatchHistoryRepository = FakeWatchHistoryRepository(),
    ): SeeAllViewModel = SeeAllViewModel(
        metadataRepo = metadata,
        watchHistoryRepo = history,
        watchlistRepo = watchlist,
        prefsRepo = FakePreferencesRepository(),
        watchProgressRepo = watchProgress,
    )

    @Test fun applyingUnchangedDefaultsKeepsTheOriginalTrendingSource() = runTest(dispatcher) {
        val repo=ScopedMetadataRepository();val vm=viewModel(metadata=repo)
        try {
            vm.loadSection("TRENDING_MOVIES");advanceUntilIdle()
            vm.applyCatalogFilters(com.torve.presentation.catalog.CatalogFilter(),null);advanceUntilIdle()
            assertEquals(0,repo.discoverCalls)
            assertEquals(2,repo.trendingPagedCalls.size)
        } finally { vm.close() }
    }
    @Test fun fullCatalogFilterQueriesTheWholeHomeCollectionAndKeepsTheQueryWhilePaging() = runTest(dispatcher) {
        fun page(start: Int, rawPage: Int) = PagedResult((start until start+20).map {
            media("$it", "Title $it", ratings=MediaRatings(imdbScore=8.5f),genreIds=listOf(35))
                .copy(year=2023,voteCount=it)
        },rawPage,8,160)
        val repo=ScopedMetadataRepository(discoverPages=mapOf("movie:35:1" to page(1,3),"movie:35:4" to page(21,4)))
        val vm=viewModel(metadata=repo)
        try {
            vm.loadSection("TRENDING_MOVIES"); advanceUntilIdle()
            val filter=com.torve.presentation.catalog.CatalogFilter(minRating=8f,year=2020,yearTo=2025,
                runtimeFilter=com.torve.presentation.catalog.RuntimeFilter.STANDARD,sortBy=com.torve.presentation.catalog.SortOption.VOTE_COUNT_DESC)
            vm.applyCatalogFilters(filter,35); advanceUntilIdle()
            assertEquals(filter,vm.state.value.catalogFilter)
            assertEquals(20,vm.state.value.displayedItems.size)
            assertEquals(5,vm.state.value.activeFilterCount)
            vm.loadMore(); advanceUntilIdle()
            assertEquals(40,vm.state.value.displayedItems.size)
            assertEquals(listOf("movie:35:1","movie:35:4"),repo.discoverPageCalls)
            assertTrue(repo.filterQueries.all { it == listOf("vote_count.desc","2020","2025","90","150","null") })
            vm.clearFilters(); advanceUntilIdle()
            assertEquals(com.torve.presentation.catalog.CatalogFilter(),vm.state.value.catalogFilter)
            assertEquals(0,vm.state.value.activeFilterCount)
            assertEquals(2,repo.trendingPagedCalls.size)
        } finally { vm.close() }
    }

    @Test fun savedHomeCollectionsUseTheSameFiltersWithoutBorrowingGlobalTitles() = runTest(dispatcher) {
        val repo=ScopedMetadataRepository()
        val items=listOf(media("keep","Keep",ratings=MediaRatings(imdbScore=8.6f),genreIds=listOf(35)).copy(year=2022,runtime=100),
            media("long","Long",ratings=MediaRatings(imdbScore=9f),genreIds=listOf(35)).copy(year=2022,runtime=190),
            media("old","Old",ratings=MediaRatings(imdbScore=9f),genreIds=listOf(35)).copy(year=1990,runtime=100),
            media("genre","Other genre",ratings=MediaRatings(imdbScore=9f),genreIds=listOf(28)).copy(year=2022,runtime=100),
            media("score","Low score",ratings=MediaRatings(imdbScore=5f),genreIds=listOf(35)).copy(year=2022,runtime=100))
        SeeAllViewModel.pendingItems["saved"]="Saved" to items
        val vm=viewModel(metadata=repo)
        try {
            vm.loadSection("shelf:saved");advanceUntilIdle()
            vm.applyCatalogFilters(com.torve.presentation.catalog.CatalogFilter(minRating=8f,year=2020,yearTo=2025,
                runtimeFilter=com.torve.presentation.catalog.RuntimeFilter.STANDARD),35)
            advanceUntilIdle()
            assertEquals(listOf("keep"),vm.state.value.displayedItems.map { it.id })
            assertEquals(0,repo.discoverCalls)
            vm.clearFilters();advanceUntilIdle()
            assertEquals(items.size,vm.state.value.displayedItems.size)
        } finally { vm.close() }
    }

    @Test fun fullFiltersAlsoApplyToTvDiscoveryAndOfferAllGenresBeforeResultsArrive() = runTest(dispatcher) {
        val repo=ScopedMetadataRepository(); val vm=viewModel(metadata=repo)
        try {
            assertTrue(vm.catalogGenres("tv").any { it.id==10762 })
            assertTrue(vm.catalogGenres("movie").any { it.id==28 })
            vm.loadSection("POPULAR_TV");advanceUntilIdle()
            vm.applyCatalogFilters(com.torve.presentation.catalog.CatalogFilter(year=2020,yearTo=2024,
                sortBy=com.torve.presentation.catalog.SortOption.RELEASE_DATE_ASC),16)
            advanceUntilIdle()
            assertEquals("tv:16:1",repo.discoverPageCalls.first())
            assertEquals("primary_release_date.asc",repo.filterQueries.first().first())
        } finally { vm.close() }
    }

    @Test fun savedCollectionFiltersResolveMissingGenreYearAndRuntimeFromItsOwnTitles() = runTest(dispatcher) {
        val source=media("saved-id","Saved",tmdbId=123)
        val detail=source.copy(year=2022,runtime=100,genreIds=listOf(35))
        val repo=ScopedMetadataRepository(detailItems=mapOf(123 to detail))
        SeeAllViewModel.pendingItems["missing-metadata"]="Saved" to listOf(source)
        val vm=viewModel(metadata=repo)
        try {
            vm.loadSection("shelf:missing-metadata");advanceUntilIdle()
            vm.applyCatalogFilters(com.torve.presentation.catalog.CatalogFilter(year=2020,yearTo=2025,
                runtimeFilter=com.torve.presentation.catalog.RuntimeFilter.STANDARD),35)
            repeat(200) {
                runCurrent()
                if(vm.state.value.displayedItems.isEmpty()) withContext(Dispatchers.Default) { kotlinx.coroutines.delay(1) }
            }
            assertEquals(listOf("saved-id"),vm.state.value.displayedItems.map { it.id })
            assertEquals(100,vm.state.value.displayedItems.single().runtime)
            assertEquals(0,repo.discoverCalls)
            assertEquals(0,repo.searchCalls)
        } finally { vm.close() }
    }

    private class ScopedMetadataRepository(
        private val trendingMoviePages: Map<Int, PagedResult> = emptyMap(),
        private val popularMoviePages: Map<Int, PagedResult> = emptyMap(),
        private val discoverPages: Map<String, PagedResult> = emptyMap(),
        private val recommendationPages: Map<String, List<MediaItem>> = emptyMap(),
        private val similarPages: Map<String, List<MediaItem>> = emptyMap(),
        private val pageGate: CompletableDeferred<Unit>? = null,
        private var failNextPage: Boolean = false,
        private val detailItems: Map<Int, MediaItem> = emptyMap(),
    ) : MetadataRepository {
        val filterQueries = mutableListOf<List<String>>()
        var discoverCalls = 0
        var searchCalls = 0
        val trendingPagedCalls = mutableListOf<String>()
        val popularPagedCalls = mutableListOf<String>()
        val discoverPageCalls = mutableListOf<String>()
        val recommendationCalls = mutableListOf<String>()
        val similarCalls = mutableListOf<String>()

        override suspend fun getTrending(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getPopular(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getTopRated(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getUpcoming(page: Int): List<MediaItem> = emptyList()
        override suspend fun getNowPlaying(page: Int): List<MediaItem> = emptyList()
        override suspend fun getAiringToday(page: Int): List<MediaItem> = emptyList()
        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
            searchCalls++
            return emptyList()
        }
        override suspend fun findByImdbId(imdbId: String, preferredType: String?): MediaItem? = null
        override suspend fun getDetail(type: String, id: Int): MediaItem =
            detailItems[id] ?: media("tmdb:$id", "Detail $id", tmdbId = id, rating = 7.0)
        override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> {
            val key = "$type:$id:$page"
            similarCalls += key
            return similarPages[key].orEmpty()
        }
        override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> {
            val key = "$type:$id:$page"
            recommendationCalls += key
            return recommendationPages[key].orEmpty()
        }
        override suspend fun getHomeShelves(): List<CatalogShelf> = emptyList()
        override suspend fun getPersonCredits(personId: Int): List<MediaItem> = emptyList()
        override suspend fun getPersonDetail(personId: Int): TmdbPerson = error("unused")
        override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season = error("unused")
        override suspend fun getTrendingPaged(type: String, page: Int): PagedResult {
            trendingPagedCalls += "$type:$page"
            if (page > 1) {
                if (failNextPage) { failNextPage = false; error("Temporary provider error") }
                withContext(NonCancellable) { pageGate?.await() }
            }
            return if (type == "movie") {
                trendingMoviePages[page] ?: PagedResult(emptyList(), page, page, 0)
            } else {
                PagedResult(emptyList(), page, page, 0)
            }
        }
        override suspend fun getPopularPaged(type: String, page: Int): PagedResult {
            popularPagedCalls += "$type:$page"
            return if (type == "movie") {
                popularMoviePages[page] ?: PagedResult(emptyList(), page, page, 0)
            } else {
                PagedResult(emptyList(), page, page, 0)
            }
        }
        override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), page, page, 0)
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
            discoverCalls++
            filterQueries += listOf(sortBy,year.toString(),yearTo.toString(),runtimeGte.toString(),runtimeLte.toString(),minRating.toString())
            val genreKey = "$type:${withGenres.orEmpty()}:$page"
            discoverPageCalls += genreKey
            return discoverPages[genreKey] ?: PagedResult(listOf(media("global", "Global Result")), page, page, 1)
        }
        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult {
            searchCalls++
            return PagedResult(emptyList(), page, page, 0)
        }
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }

    private class FakeWatchlistRepository(
        private val items: List<WatchlistItem> = emptyList(),
    ) : WatchlistRepository {
        override suspend fun getAll(): List<WatchlistItem> = items
        override suspend fun getByType(mediaType: String): List<WatchlistItem> = items
        override suspend fun isInWatchlist(mediaId: String): Boolean = items.any { it.mediaId == mediaId }
        override suspend fun add(item: WatchlistItem) = Unit
        override suspend fun add(item: WatchlistItem, syncTrakt: Boolean, syncSimkl: Boolean) = Unit
        override suspend fun remove(mediaId: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun syncFromTrakt() = Unit
        override suspend fun addToTraktWatchlist(item: WatchlistItem): WatchlistMutationResult =
            WatchlistMutationResult.Success(item.mediaId, true, item)
        override suspend fun removeFromTraktWatchlist(mediaId: String): WatchlistMutationResult =
            WatchlistMutationResult.Success(mediaId, false)
        override suspend fun toggleTraktWatchlist(item: WatchlistItem): WatchlistMutationResult =
            WatchlistMutationResult.Success(item.mediaId, true, item)
    }

    private class FakeWatchProgressRepository(
        private val items: List<WatchProgress> = emptyList(),
    ) : WatchProgressRepository {
        override suspend fun getInProgress(limit: Long): List<WatchProgress> = items.take(limit.toInt())
        override suspend fun getProgress(mediaId: String): WatchProgress? = items.firstOrNull { it.mediaId == mediaId }
        override suspend fun saveProgress(progress: WatchProgress) = Unit
        override suspend fun getAllProgress(): List<WatchProgress> = items
        override suspend fun deleteProgress(mediaId: String) = Unit
        override suspend fun clearAllProgress() = Unit
        override suspend fun syncFromTrakt() = Unit
    }

    private class FakeWatchHistoryRepository(
        private val items: List<WatchHistoryEntry> = emptyList(),
    ) : WatchHistoryRepository {
        override suspend fun getRecent(limit: Int): List<WatchHistoryEntry> = items.take(limit)
        override suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry> = emptyList()
        override suspend fun getAll(): List<WatchHistoryEntry> = items
        override suspend fun getForMedia(mediaId: String): List<WatchHistoryEntry> = emptyList()
        override suspend fun record(entry: WatchHistoryEntry) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun getCount(): Long = 0L
        override suspend fun syncFromTrakt() = Unit
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}

private fun watchlistItem(id: String, tmdbId: Int, title: String): WatchlistItem =
    WatchlistItem(
        mediaId = id,
        mediaType = MediaType.MOVIE,
        tmdbId = tmdbId,
        title = title,
        addedAt = 1L,
    )

private fun media(
    id: String,
    title: String,
    type: MediaType = MediaType.MOVIE,
    tmdbId: Int? = null,
    genreIds: List<Int> = emptyList(),
    rating: Double? = null,
    ratings: MediaRatings? = null,
): MediaItem = MediaItem(
    id = id,
    tmdbId = tmdbId,
    title = title,
    type = type,
    genreIds = genreIds,
    rating = rating,
    ratings = ratings,
)
