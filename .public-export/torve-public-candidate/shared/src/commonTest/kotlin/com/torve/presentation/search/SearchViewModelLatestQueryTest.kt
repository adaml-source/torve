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

/**
 * Covers the "latest-query-only" invariant and the atomic render-phase
 * contract introduced to fix visible churn on heavily filtered queries
 * (e.g. typing "porn" producing intermediate results for "p", "po", "por"
 * before the final zero-item render).
 *
 * Key assertions:
 *  - An in-flight search for an older query never commits results once a
 *    newer query has claimed a generation.
 *  - The policy-sensitive "porn" scenario produces a single EMPTY phase
 *    with zero visible items; no sensitive raw item ever surfaces.
 *  - Every emission's `renderPhase` is internally consistent with its
 *    `displayItems` and `isSearching` state (no SEARCHING phase with
 *    raw leakage, no RESULTS phase with empty items).
 *  - `generation` monotonically increases and never regresses.
 */
class SearchViewModelLatestQueryTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 1. Latest-query enforcement ──

    @Test
    fun olderQueryCannotOverwriteNewerQueryResults() = runTest(dispatcher) {
        val apple = mediaItem("tmdb-1", "Apple")
        val banana = mediaItem("tmdb-2", "Banana")

        val repo = QueryVaryingMetadataRepository(
            results = mapOf(
                "appl" to listOf(apple),
                "bana" to listOf(banana),
            ),
        )
        val policyRepo = FakeContentPolicyRepository(unrestrictedPolicy())
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        // Gate the slow "appl" search so we can interleave a newer query
        // before it completes.
        val slowGate = CompletableDeferred<Unit>()
        repo.setQueryGate("appl", slowGate)

        viewModel.updateQuery("appl")
        advanceTimeBy(350L) // past debounce, "appl" search is now in flight
        advanceUntilIdle()

        // While "appl" is still blocked on its gate, fire a newer query.
        viewModel.updateQuery("bana")
        advanceTimeBy(350L)
        advanceUntilIdle()

        // Now let the stale "appl" result complete. Its commit must be
        // rejected because a newer generation (from "bana") has already
        // claimed the state.
        slowGate.complete(Unit)
        advanceUntilIdle()

        val finalState = viewModel.state.value
        assertEquals(
            listOf(banana),
            finalState.results,
            "Stale 'appl' results must not overwrite newer 'bana' results",
        )
        assertEquals("bana", finalState.query)
    }

    @Test
    fun generationMonotonicallyIncreases() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(
            results = mapOf(
                "one" to listOf(mediaItem("1", "One")),
                "two" to listOf(mediaItem("2", "Two")),
                "three" to listOf(mediaItem("3", "Three")),
            ),
        )
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = FakeContentPolicyRepository(unrestrictedPolicy()),
        )
        val seenGenerations = mutableListOf<Long>()
        val collector = launch {
            viewModel.state.collect { seenGenerations += it.generation }
        }

        for (q in listOf("one", "two", "three")) {
            viewModel.updateQuery(q)
            advanceTimeBy(350L)
            advanceUntilIdle()
        }
        collector.cancel()

        val distinctAscending = seenGenerations.distinct()
        assertEquals(
            distinctAscending.sorted(),
            distinctAscending,
            "Generation must never go backwards; saw $distinctAscending",
        )
        assertTrue(
            distinctAscending.last() > distinctAscending.first(),
            "Generation must advance after three searches",
        )
    }

    // ── 2. Heavily filtered ("porn") scenario ──

    @Test
    fun pornQueryEmitsEmptyPhaseWithZeroVisibleItems() = runTest(dispatcher) {
        // The sensitive-query gate pre-empts the fetch entirely for
        // explicit queries under a locked policy. Final state is EMPTY
        // with zero visible and zero hidden (we never fetched anything).
        val sensitiveItems = (1..20).map { i ->
            mediaItem("tmdb-$i", "Sensitive $i", adult = true)
        }
        val repo = QueryVaryingMetadataRepository(
            results = mapOf("porn" to sensitiveItems),
        )
        val policyRepo = FakeContentPolicyRepository(lockedAdultPolicy())
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        viewModel.updateQuery("porn")
        advanceTimeBy(350L)
        advanceUntilIdle()
        collector.cancel()

        val finalState = viewModel.state.value
        assertEquals(SearchRenderPhase.EMPTY, finalState.renderPhase)
        assertTrue(finalState.results.isEmpty())
        // No emission may ever have rendered an adult item.
        emissions.forEach { snapshot ->
            assertTrue(
                snapshot.results.none { it.adult == true },
                "No emission may include an adult item",
            )
        }
    }

    @Test
    fun typedBurstOfFilteredQueryProducesNoRawEmission() = runTest(dispatcher) {
        // Simulate "p" → "po" → "por" → "porn" each resolving to adult
        // items. Even without debounce collapsing the burst, the UI must
        // never expose an adult item.
        val results = mapOf(
            "p" to (1..5).map { mediaItem("p-$it", "P$it", adult = true) },
            "po" to (1..8).map { mediaItem("po-$it", "Po$it", adult = true) },
            "por" to (1..15).map { mediaItem("por-$it", "Por$it", adult = true) },
            "porn" to (1..20).map { mediaItem("porn-$it", "Porn$it", adult = true) },
        )
        val repo = QueryVaryingMetadataRepository(results = results)
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = FakeContentPolicyRepository(lockedAdultPolicy()),
        )
        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        // Fast typing past the 300ms debounce for the last char only.
        viewModel.updateQuery("p")
        advanceTimeBy(100L)
        viewModel.updateQuery("po")
        advanceTimeBy(100L)
        viewModel.updateQuery("por")
        advanceTimeBy(100L)
        viewModel.updateQuery("porn")
        advanceTimeBy(400L)
        advanceUntilIdle()
        collector.cancel()

        // At no point in the emission stream should the UI have exposed
        // any adult-flagged raw item. This is the core invariant the
        // refactor is protecting.
        val leaked = emissions.flatMap { it.results }.filter { it.adult == true }
        assertTrue(
            leaked.isEmpty(),
            "No adult raw item may ever appear in state.results; leaked=$leaked",
        )
        // And the final phase is EMPTY, not RESULTS-with-zero-items or
        // SEARCHING-forever.
        assertEquals(SearchRenderPhase.EMPTY, viewModel.state.value.renderPhase)
    }

    // ── 3. Render-phase consistency ──

    @Test
    fun renderPhaseResultsImpliesNonEmptyItems() = runTest(dispatcher) {
        val safeItem = mediaItem("tmdb-1", "Safe")
        val repo = QueryVaryingMetadataRepository(
            results = mapOf("safe" to listOf(safeItem)),
        )
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = FakeContentPolicyRepository(unrestrictedPolicy()),
        )
        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        viewModel.updateQuery("safe")
        advanceTimeBy(350L)
        advanceUntilIdle()
        collector.cancel()

        emissions.filter { it.renderPhase == SearchRenderPhase.RESULTS }.forEach { s ->
            assertTrue(
                s.displayItems.isNotEmpty(),
                "RESULTS phase must never have empty displayItems (saw $s)",
            )
        }
        assertEquals(SearchRenderPhase.RESULTS, viewModel.state.value.renderPhase)
    }

    @Test
    fun renderPhaseEmptyImpliesEmptyDisplayItems() = runTest(dispatcher) {
        // Empty-upstream-response scenario — not policy-blocked, just
        // nothing matched.
        val repo = QueryVaryingMetadataRepository(
            results = mapOf("zxq" to emptyList()),
        )
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = FakeContentPolicyRepository(unrestrictedPolicy()),
        )
        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        viewModel.updateQuery("zxq")
        advanceTimeBy(350L)
        advanceUntilIdle()
        collector.cancel()

        emissions.filter { it.renderPhase == SearchRenderPhase.EMPTY }.forEach { s ->
            assertTrue(
                s.displayItems.isEmpty(),
                "EMPTY phase must never have items",
            )
        }
        assertEquals(SearchRenderPhase.EMPTY, viewModel.state.value.renderPhase)
    }

    @Test
    fun clearSearchReturnsToIdlePhaseAndBumpsGeneration() = runTest(dispatcher) {
        val repo = QueryVaryingMetadataRepository(
            results = mapOf("foo" to listOf(mediaItem("tmdb-1", "Foo"))),
        )
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = FakeContentPolicyRepository(unrestrictedPolicy()),
        )
        viewModel.updateQuery("foo")
        advanceTimeBy(350L)
        advanceUntilIdle()
        val afterSearchGen = viewModel.state.value.generation

        viewModel.clearSearch()
        advanceUntilIdle()
        val afterClearGen = viewModel.state.value.generation

        assertNotEquals(afterSearchGen, afterClearGen)
        assertTrue(
            afterClearGen > afterSearchGen,
            "clearSearch must bump generation to invalidate late writes",
        )
        assertEquals(SearchRenderPhase.IDLE, viewModel.state.value.renderPhase)
        assertTrue(viewModel.state.value.displayItems.isEmpty())
    }

    // ── helpers ──

    private fun lockedAdultPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        isLoading = false,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = false,
    )

    private fun unrestrictedPolicy() = ContentPolicyState.unrestricted()

    private fun mediaItem(id: String, title: String, adult: Boolean = false): MediaItem =
        MediaItem(
            id = id,
            tmdbId = id.substringAfterLast('-').toIntOrNull(),
            title = title,
            type = MediaType.MOVIE,
            adult = adult,
        )

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
    ) : MetadataRepository {
        private val queryGates = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun setQueryGate(query: String, gate: CompletableDeferred<Unit>) {
            queryGates[query] = gate
        }

        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
            queryGates[query]?.await()
            return results[query] ?: emptyList()
        }

        override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult =
            PagedResult(items = results[query] ?: emptyList(), page = 1, totalPages = 1, totalResults = 0)
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
        ): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }
}
