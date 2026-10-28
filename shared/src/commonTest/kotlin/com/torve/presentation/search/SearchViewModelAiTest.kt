package com.torve.presentation.search

import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.metadata.TmdbKeyword
import com.torve.data.metadata.TmdbPerson
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.ContentAgeBand
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

import com.torve.data.ai.KeywordSearchResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SearchViewModelAiTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }
    private fun item(id: Int, type: MediaType = MediaType.MOVIE, adult: Boolean = false) =
        MediaItem(id = "tmdb-$id", tmdbId = id, title = "Title $id", type = type, adult = adult)
    private fun vm(repo: MetadataRepository, resolver: SearchAiResolver? = null,
        policy: ContentPolicyState = ContentPolicyState.unrestricted()) = SearchViewModel(
        metadataRepo = repo, prefsRepo = FakePreferencesRepository(),
        contentPolicyRepository = FakeContentPolicyRepository(policy), aiResolver = resolver)

    @Test fun aiModeWaitsForSubmissionAndSwitchingOffRestoresOrdinarySearch() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(mapOf("space" to listOf(item(1))),
            discoverResults = mapOf(("movie" to 1) to listOf(item(2))))
        var calls = 0
        val model = vm(repo, SearchAiResolver { calls++; KeywordSearchResult("Space movies", mediaType = "movie") })
        model.setAiSearchEnabled(true)
        model.updateQuery("space")
        advanceUntilIdle()
        assertEquals(0, calls)
        assertTrue(repo.ordinaryQueries.isEmpty())
        assertEquals(SearchRenderPhase.IDLE, model.state.value.renderPhase)
        model.submitSearch(); advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf(item(2)), model.state.value.displayItems)
        assertEquals("Space movies", model.state.value.aiSearchTitle)
        model.setAiSearchEnabled(false); advanceUntilIdle()
        assertEquals(listOf(item(1)), model.state.value.displayItems)
        assertEquals(1, calls)
    }

    @Test fun aiInterpretationIsReusedForFiltersAndNextPages() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(emptyMap(), discoverResults = mapOf(
            ("movie" to 1) to listOf(item(1)), ("tv" to 1) to listOf(item(2, MediaType.SERIES)),
            ("movie" to 2) to listOf(item(3)), ("tv" to 2) to listOf(item(4, MediaType.SERIES))), discoverTotalPages = 2)
        var calls = 0
        val model = vm(repo, SearchAiResolver { calls++; KeywordSearchResult("Sci-fi", genreIds = listOf(878)) })
        model.setAiSearchEnabled(true); model.updateQuery("sci-fi"); model.submitSearch(); advanceUntilIdle()
        assertEquals(2, model.state.value.displayItems.size)
        assertTrue(repo.discoverCalls.contains(Triple("tv", 1, "10765")))
        model.loadMore(); advanceUntilIdle()
        assertEquals(4, model.state.value.displayItems.size)
        model.applyFilter(SearchFilter(mediaType = "tv")); advanceUntilIdle()
        assertEquals(listOf(item(2, MediaType.SERIES)), model.state.value.displayItems)
        assertEquals(1, calls)
        assertTrue(repo.ordinaryQueries.isEmpty())
    }

    @Test fun staleAiResponseCannotOverwriteStandardMode() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = QueryVaryingMetadataRepository(mapOf("space" to listOf(item(1))),
            discoverResults = mapOf(("movie" to 1) to listOf(item(2))))
        val model = vm(repo, SearchAiResolver { withContext(NonCancellable) { gate.await() }; KeywordSearchResult("AI", mediaType = "movie") })
        model.setAiSearchEnabled(true); model.updateQuery("space"); model.submitSearch(); advanceUntilIdle()
        model.setAiSearchEnabled(false); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(model.state.value.aiSearchEnabled)
        assertEquals(listOf(item(1)), model.state.value.displayItems)
        assertNull(model.state.value.aiSearchTitle)
    }

    @Test fun tvGenreMappingSurvivesUiFiltersAndPagination() = runTest(dispatcher) {
        val first = item(1, MediaType.SERIES).copy(genreIds = listOf(10765))
        val second = item(2, MediaType.SERIES).copy(genreIds = listOf(10765))
        val repo = QueryVaryingMetadataRepository(emptyMap(), discoverResults = mapOf(
            ("tv" to 1) to listOf(first), ("tv" to 2) to listOf(second)), discoverTotalPages = 2)
        val model = vm(repo, SearchAiResolver { KeywordSearchResult("Sci-fi") })
        model.setAiSearchEnabled(true); model.updateQuery("sci-fi")
        model.applyFilter(SearchFilter(mediaType = "tv", genreIds = listOf(878)))
        model.submitSearch(); advanceUntilIdle()
        assertEquals(listOf(first), model.state.value.displayItems)
        model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(first, second), model.state.value.displayItems)
    }

    @Test fun unmappableGenreDoesNotBecomeUnfilteredDiscovery() = runTest(dispatcher) {
        val horror = item(1).copy(genreIds = listOf(27))
        val unrelated = item(2, MediaType.SERIES)
        val repo = QueryVaryingMetadataRepository(emptyMap(), discoverResults = mapOf(
            ("movie" to 1) to listOf(horror), ("tv" to 1) to listOf(unrelated)))
        val model = vm(repo, SearchAiResolver { KeywordSearchResult("Horror", genreIds = listOf(27)) })
        model.setAiSearchEnabled(true); model.updateQuery("horror"); model.submitSearch(); advanceUntilIdle()
        assertEquals(listOf(horror), model.state.value.displayItems)
        assertTrue(repo.discoverCalls.none { it.first == "tv" })
    }

    @Test fun editedQueryInvalidatesAiResultBeforeNextSubmission() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = QueryVaryingMetadataRepository(emptyMap())
        var calls = 0
        val model = vm(repo, SearchAiResolver { calls++; withContext(NonCancellable) { gate.await() }; KeywordSearchResult("Old") })
        model.setAiSearchEnabled(true); model.updateQuery("old query"); model.submitSearch(); advanceUntilIdle()
        model.updateQuery("new query"); gate.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.awaitingAiSubmission)
        assertEquals(SearchRenderPhase.IDLE, model.state.value.renderPhase)
        assertTrue(model.state.value.displayItems.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun missingConfigurationShowsSetupMessageWithoutOrdinaryFallback() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(emptyMap())
        val model = vm(repo)
        model.setAiSearchEnabled(true); model.updateQuery("space"); model.submitSearch(); advanceUntilIdle()
        assertEquals(SearchRenderPhase.ERROR, model.state.value.renderPhase)
        assertTrue(model.state.value.error.orEmpty().contains("API key in Settings"))
        assertTrue(repo.ordinaryQueries.isEmpty())
    }

    @Test fun providerFailureNeverEchoesCredentialsAndCanBeRetried() = runTest(dispatcher) {
        var calls = 0
        val repo = QueryVaryingMetadataRepository(emptyMap())
        val model = vm(repo, SearchAiResolver {
            calls++
            if (calls == 1) error("https://example.test/?apikey=secret")
            KeywordSearchResult("Recovered", mediaType = "movie")
        })
        model.setAiSearchEnabled(true); model.updateQuery("space"); model.submitSearch(); advanceUntilIdle()
        assertFalse(model.state.value.error.orEmpty().contains("secret"))
        model.retry(); advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(2, calls)
    }

    @Test fun clearKeepsAiToggleAndDoesNotRunDiscoveryOrAi() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(emptyMap())
        var calls = 0
        val model = vm(repo, SearchAiResolver { calls++; KeywordSearchResult("AI") })
        model.setAiSearchEnabled(true); model.updateQuery("space"); model.clearSearch(); model.ensureDiscovery(); advanceUntilIdle()
        assertTrue(model.state.value.aiSearchEnabled)
        assertTrue(model.state.value.awaitingAiSubmission)
        assertTrue(repo.discoverCalls.isEmpty())
        assertEquals(0, calls)
    }

    @Test fun lockedSensitiveQueryIsBlockedBeforeSendingToAi() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(emptyMap())
        var calls = 0
        val model = vm(repo, SearchAiResolver { calls++; KeywordSearchResult("AI") }, ContentPolicyState.lockedBootstrap(enforcementEnabled = true))
        model.setAiSearchEnabled(true); model.updateQuery("porn"); model.submitSearch(); advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(SearchRenderPhase.EMPTY, model.state.value.renderPhase)
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
            val items = discoverResults[type to page].orEmpty()
            return PagedResult(
                items = items,
                page = page,
                totalPages = discoverTotalPages,
                totalResults = discoverResults.values.sumOf { it.size },
            )
        }
        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }
}
