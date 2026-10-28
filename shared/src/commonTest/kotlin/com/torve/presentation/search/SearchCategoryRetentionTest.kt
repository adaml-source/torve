package com.torve.presentation.search

import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.metadata.TmdbKeyword
import com.torve.data.metadata.TmdbPerson
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

class SearchCategoryRetentionTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }
    private fun item(id: Int, type: MediaType = MediaType.MOVIE) =
        MediaItem("title-$id", tmdbId = id, title = "Title $id", type = type, adult = false)
    private fun model(repo: MetadataRepository, policy: FakeContentPolicyRepository = FakeContentPolicyRepository(ContentPolicyState.unrestricted())) =
        SearchViewModel(repo, FakePreferencesRepository(), policy)

    @Test fun recordingSequenceReusesAllMovieTvAndPrewarmedPeopleWithoutLoading() = runTest(dispatcher) {
        val movie = item(1); val show = item(2, MediaType.SERIES)
        val repo = QueryVaryingMetadataRepository(emptyMap(), mapOf(("movie" to 1) to listOf(movie), ("tv" to 1) to listOf(show)))
        val vm = model(repo); vm.ensureDiscovery(); advanceUntilIdle()
        for ((category, expected) in listOf("movie" to listOf(movie), "tv" to listOf(show), "all" to listOf(movie, show))) {
            vm.selectCategory(category)
            assertEquals(expected, vm.state.value.displayItems)
            assertFalse(vm.state.value.isDiscovering)
        }
        vm.selectCategory("people")
        assertEquals(listOf(PersonSummary(1, "Person")), vm.state.value.peopleResults)
        assertFalse(vm.state.value.isSearching)
        vm.selectCategory("all"); advanceUntilIdle()
        assertEquals(2, repo.discoverCalls.size)
        assertEquals(1, repo.peopleCalls)
    }

    @Test fun categoryKeepsItsActualPaginationAndLoadedPages() = runTest(dispatcher) {
        val first = item(1); val second = item(2); val show = item(3, MediaType.SERIES)
        val repo = QueryVaryingMetadataRepository(emptyMap(), mapOf(("movie" to 1) to listOf(first),
            ("movie" to 2) to listOf(second), ("tv" to 1) to listOf(show)), 2)
        repo.typeTotalPages["tv"] = 1
        val vm = model(repo); vm.ensureDiscovery(); advanceUntilIdle()
        vm.selectCategory("tv"); assertFalse(vm.state.value.discoverHasMore)
        vm.loadMore(); advanceUntilIdle(); assertEquals(2, repo.discoverCalls.size)
        vm.selectCategory("movie"); assertTrue(vm.state.value.discoverHasMore)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf(first, second), vm.state.value.displayItems)
        vm.selectCategory("all"); vm.selectCategory("movie")
        assertEquals(2, vm.state.value.discoverPage)
        assertEquals(listOf(first, second), vm.state.value.displayItems)
        vm.loadMore(); advanceUntilIdle(); assertEquals(3, repo.discoverCalls.size)
    }

    @Test fun emptyPagesAreLoadedResultsAndDoNotRefetchOnReappearance() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(emptyMap()); repo.popularPeople = emptyList()
        val vm = model(repo); vm.ensureDiscovery(); advanceUntilIdle()
        repeat(3) {
            vm.ensureDiscovery(); vm.selectCategory("movie"); vm.ensureDiscovery()
            vm.selectCategory("people"); vm.ensureDiscovery(); vm.selectCategory("all")
        }
        advanceUntilIdle()
        assertTrue(vm.state.value.discoveryLoaded)
        assertEquals(2, repo.discoverCalls.size); assertEquals(1, repo.peopleCalls)
    }

    @Test fun anEditedQueryCannotCacheThePreviousQueriesPosters() = runTest(dispatcher) {
        val old = item(1); val new = item(2, MediaType.SERIES)
        val repo = QueryVaryingMetadataRepository(mapOf("old" to listOf(old), "new" to listOf(new)))
        val vm = model(repo); vm.updateQuery("old"); advanceUntilIdle()
        vm.updateQuery("new"); vm.selectCategory("tv")
        assertTrue(vm.state.value.displayItems.isEmpty())
        advanceUntilIdle(); assertEquals(listOf(new), vm.state.value.displayItems)
        assertEquals(1, repo.ordinaryQueries.count { it == "new" }, "The pending debounce must not duplicate the category request")
        vm.selectCategory("all"); advanceUntilIdle()
        assertEquals(listOf(new), vm.state.value.displayItems)
        assertFalse(vm.state.value.displayItems.contains(old))
    }

    @Test fun lateRefreshCannotOverwriteARestoredCategory() = runTest(dispatcher) {
        val movie = item(1); val show = item(2, MediaType.SERIES)
        val repo = QueryVaryingMetadataRepository(emptyMap(), mapOf(("movie" to 1) to listOf(movie), ("tv" to 1) to listOf(show)))
        val vm = model(repo); vm.ensureDiscovery(); advanceUntilIdle(); vm.selectCategory("movie")
        val gate = CompletableDeferred<Unit>(); repo.discoverGate = "movie" to gate
        vm.retry(); runCurrent(); assertTrue(vm.state.value.isDiscovering)
        vm.selectCategory("all")
        assertEquals(listOf(movie, show), vm.state.value.displayItems)
        assertFalse(vm.state.value.isDiscovering)
        vm.selectCategory("movie")
        assertEquals(listOf(movie), vm.state.value.displayItems, "An in-flight refresh must not evict the usable page")
        assertFalse(vm.state.value.isDiscovering)
        vm.selectCategory("all")
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("all", vm.state.value.category)
        assertEquals(listOf(movie, show), vm.state.value.displayItems)
    }

    @Test fun retryKeepsContentThroughFailureAndCanRecover() = runTest(dispatcher) {
        val movie = item(1)
        val repo = QueryVaryingMetadataRepository(emptyMap(), mapOf(("movie" to 1) to listOf(movie)))
        val vm = model(repo); vm.ensureDiscovery(); advanceUntilIdle()
        repo.failDiscovery = true; vm.retry(); advanceUntilIdle()
        assertEquals(listOf(movie), vm.state.value.displayItems); assertNotNull(vm.state.value.error)
        repo.failDiscovery = false; vm.retry(); advanceUntilIdle()
        assertEquals(listOf(movie), vm.state.value.displayItems); assertNull(vm.state.value.error)
    }

    @Test fun languageRefreshDiscardsOtherCategoriesAndDoesNotSubmitDraftAi() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(emptyMap(), mapOf(("movie" to 1) to listOf(item(1))))
        val vm = model(repo); vm.ensureDiscovery(); advanceUntilIdle(); vm.selectCategory("movie")
        vm.refreshLocalizedContent(); advanceUntilIdle(); assertEquals(3, repo.discoverCalls.size)
        vm.selectCategory("tv"); advanceUntilIdle(); assertEquals(4, repo.discoverCalls.size)
        vm.setAiSearchEnabled(true); vm.updateQuery("draft"); vm.refreshLocalizedContent(); advanceUntilIdle()
        assertTrue(vm.state.value.awaitingAiSubmission); assertTrue(repo.ordinaryQueries.isEmpty())
    }

    @Test fun aPolicyLockCannotRestorePreviouslyCachedSensitiveQueryResults() = runTest(dispatcher) {
        val policy = FakeContentPolicyRepository(ContentPolicyState(enforcementEnabled = true, isSignedIn = true,
            ageBand = ContentAgeBand.ADULT, adultEligible = true, sensitiveMaterialEnabled = true))
        val repo = QueryVaryingMetadataRepository(mapOf("porn" to listOf(item(1))))
        val vm = model(repo, policy); vm.updateQuery("porn"); advanceUntilIdle()
        vm.selectCategory("movie"); advanceUntilIdle(); assertTrue(vm.state.value.displayItems.isNotEmpty())
        val calls = repo.ordinaryQueries.size
        policy.emit(ContentPolicyState.lockedBootstrap(true)); advanceUntilIdle()
        vm.selectCategory("all"); assertTrue(vm.state.value.displayItems.isEmpty())
        advanceUntilIdle(); assertTrue(vm.state.value.displayItems.isEmpty())
        assertEquals(calls, repo.ordinaryQueries.size)
    }

    @Test fun retainedPagesNeverCrossAProfileBoundaryEvenBeforeItsCollectorRuns() = runTest(dispatcher) {
        var profile = "first"
        val settings = object : com.torve.domain.repository.DeviceLocalSettingsRepository {
            override suspend fun getString(key: String): String? = null
            override suspend fun setString(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        val secrets = object : com.torve.domain.security.SecureStorage {
            override suspend fun getString(key: String): String? = null
            override suspend fun putString(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        val family = com.torve.data.profile.FamilyControls(settings, secrets, null,
            com.torve.presentation.settings.SettingsRefreshNotifier(), lookup = { emptyList() },
            scope = backgroundScope, accountId = { "account" }, profileId = { profile })
        family.initialize()
        val pages = mutableMapOf(("movie" to 1) to listOf(item(1)))
        val repo = QueryVaryingMetadataRepository(emptyMap(), pages)
        val vm = SearchViewModel(repo, FakePreferencesRepository(), FakeContentPolicyRepository(ContentPolicyState.unrestricted()),
            contentPolicyFilter = com.torve.presentation.contentpolicy.ContentPolicyFilter(family))
        vm.ensureDiscovery(); advanceUntilIdle(); vm.selectCategory("movie")
        assertEquals(listOf(item(1)), vm.state.value.displayItems)
        profile = "second"; family.updateSession(); pages["movie" to 1] = listOf(item(2))
        vm.selectCategory("movie")
        assertTrue(vm.state.value.displayItems.isEmpty())
        advanceUntilIdle(); assertEquals(listOf(item(2)), vm.state.value.displayItems)
        profile = "first"; family.updateSession(); pages["movie" to 1] = listOf(item(3))
        vm.selectCategory("movie"); assertTrue(vm.state.value.displayItems.isEmpty())
        advanceUntilIdle(); assertEquals(listOf(item(3)), vm.state.value.displayItems)
    }

    private class FakeContentPolicyRepository(initial: ContentPolicyState) : ContentPolicyRepository {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<ContentPolicyState> = _state
        fun emit(next: ContentPolicyState) { _state.value = next }
        override suspend fun refresh() {}
        override suspend fun submitDob(dateOfBirth: String) {}
        override suspend fun enableSensitive(policyVersion: String) {}
        override suspend fun disableSensitive() {}
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val store = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }

    /**
     * Metadata fake keyed by query prefix. Supports per-query gates to
     * force a specific query to block on a deferred so tests can interleave
     * a newer query before the older one completes.
     */
    private class QueryVaryingMetadataRepository(
        private val results: Map<String, List<MediaItem>>,
        private val discoverResults: Map<Pair<String, Int>, List<MediaItem>> = emptyMap(),
        private val discoverTotalPages: Int = 1,
    ) : MetadataRepository {
        val ordinaryQueries = mutableListOf<String>()
        val discoverCalls = mutableListOf<Triple<String, Int, String?>>()
        val typeTotalPages = mutableMapOf<String, Int>()
        var discoverGate: Pair<String, CompletableDeferred<Unit>>? = null
        var failDiscovery = false
        var peopleCalls = 0
        var popularPeople = listOf(PersonSummary(1, "Person"))
        private val queryGates = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun setQueryGate(query: String, gate: CompletableDeferred<Unit>) {
            queryGates[query] = gate
        }

        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
            queryGates[query]?.await()
            return results[query] ?: emptyList()
        }

        override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult {
            ordinaryQueries += query
            queryGates[query]?.await()
            val items = results[query] ?: emptyList()
            return PagedResult(items = items, page = 1, totalPages = 1, totalResults = items.size)
        }
        override suspend fun getTrending(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getPopular(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getTopRated(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getUpcoming(page: Int): List<MediaItem> = emptyList()
        override suspend fun getNowPlaying(page: Int): List<MediaItem> = emptyList()
        override suspend fun getAiringToday(page: Int): List<MediaItem> = emptyList()
        override suspend fun findByImdbId(imdbId: String, preferredType: String?): MediaItem? = null
        override suspend fun getDetail(type: String, id: Int): MediaItem = error("unused")
        override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
        override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
        override suspend fun getHomeShelves(): List<CatalogShelf> = emptyList()
        override suspend fun getPersonCredits(personId: Int): List<MediaItem> = emptyList()
        override suspend fun getPersonDetail(personId: Int): TmdbPerson = error("unused")
        override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season = error("unused")
        override suspend fun getTrendingPaged(type: String, page: Int): PagedResult =
            PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getPopularPaged(type: String, page: Int): PagedResult =
            PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult =
            PagedResult(emptyList(), 1, 1, 0)
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
            discoverCalls += Triple(type, page, withGenres)
            discoverGate?.takeIf { it.first == type }?.let { withContext(NonCancellable) { it.second.await() } }
            if (failDiscovery) error("Offline")
            val items = discoverResults[type to page].orEmpty()
            return PagedResult(
                items = items,
                page = page,
                totalPages = typeTotalPages[type] ?: discoverTotalPages,
                totalResults = discoverResults.values.sumOf { it.size },
            )
        }
        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> { peopleCalls++; return popularPeople }
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }
}
