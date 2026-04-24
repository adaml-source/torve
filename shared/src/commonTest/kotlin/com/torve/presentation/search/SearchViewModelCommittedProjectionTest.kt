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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the committed-visible-projection contract.
 *
 * The grid renders only from [SearchUiState.results] (or
 * [SearchUiState.discoverResults]). Those are committed visible projections
 * for the policy in effect at commit time, NOT live filterItems projections
 * over a mixed SAFE+SENSITIVE raw list. SENSITIVE items live in the
 * committed slice and re-project into `results` only when the ViewModel
 * performs an atomic recompute (e.g. on a sensitive-material policy toggle).
 *
 * Companion suites:
 *  - [SearchViewModelLateClassificationTest] — strict-unknown invariants.
 *  - [SearchViewModelLatestQueryTest] — generation/latest-query invariants.
 *  - [SearchViewModelPolicyFlowTest] — refetch-free toggle behavior.
 */
class SearchViewModelCommittedProjectionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    // ── Slice separation ────────────────────────────────────────────────

    @Test
    fun disabledPolicyCommitsOnlySafeItemsToVisibleGrid() = runTest(dispatcher) {
        val safe = mediaItem("tmdb-1", "Safe Family Movie", adult = false)
        val sensitive = mediaItem("tmdb-2", "Adult Feature", adult = true)
        val viewModel = newViewModel(
            results = listOf(safe, sensitive),
            policy = lockedAdultPolicy(),
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(
            listOf(safe), state.results,
            "Grid-driving `results` must be SAFE-only while sensitive_material is disabled",
        )
        assertFalse(
            state.results.any { it.adult == true },
            "No SENSITIVE item may appear in the committed visible projection",
        )
    }

    @Test
    fun disabledPolicyStoresSensitiveItemsInHiddenCacheNotVisibleList() = runTest(dispatcher) {
        val safe = mediaItem("tmdb-1", "Safe", adult = false)
        val sensitive = mediaItem("tmdb-2", "Sensitive", adult = true)
        val viewModel = newViewModel(
            results = listOf(safe, sensitive),
            policy = lockedAdultPolicy(),
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        // SENSITIVE bucket is captured in the committed slice and exposed
        // separately for inspection — it must be there, not in `results`.
        assertEquals(listOf(sensitive), state.committedSearchSlice.sensitiveItems)
        assertEquals(listOf(safe), state.committedSearchSlice.safeItems)
        assertEquals(listOf(sensitive), state.committedSensitiveHiddenResults)
        assertTrue(
            sensitive !in state.results,
            "SENSITIVE item must not be mixed into the grid-driving list",
        )
    }

    @Test
    fun gridNeverRendersFromMixedSafeSensitiveRawWhileNonGatedQueryAndPolicyDisabled() = runTest(dispatcher) {
        // Same invariant as the "porn" scenario but with a non-sensitive
        // query — proves the slice-storage separation actually works on
        // the per-item filter (not just because the query gate skipped
        // the fetch). We use "matrix" so the sensitive-query gate doesn't
        // pre-empt; a mixed SAFE+SENSITIVE response then exercises the
        // commit-time partition.
        val mixedQuery = mapOf(
            "matrix" to listOf(
                mediaItem("p-1", "Adult Sequel", adult = true),
                mediaItem("p-2", "Family Show", adult = false),
                mediaItem("p-3", "Adult Movie", adult = true),
                mediaItem("p-4", "History Series", adult = false),
            ),
        )
        val viewModel = newViewModel(
            queryResults = mixedQuery,
            policy = lockedAdultPolicy(),
        )

        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        viewModel.updateQuery("matrix")
        advanceTimeBy(350L)
        advanceUntilIdle()
        collector.cancel()

        emissions.forEach { snap ->
            assertTrue(
                snap.results.none { it.adult == true },
                "Mixed grid leak: results=${snap.results} contained an adult item",
            )
            val sensitiveBucket = snap.committedSearchSlice.sensitiveItems
            assertTrue(
                snap.results.none { it in sensitiveBucket },
                "Visible `results` overlaps the SENSITIVE bucket: ${snap.results.intersect(sensitiveBucket.toSet())}",
            )
        }
        val finalSlice = viewModel.state.value.committedSearchSlice
        assertTrue(
            finalSlice.sensitiveItems.any { it.adult == true },
            "Adult-flagged items must be captured in the SENSITIVE bucket, not silently dropped",
        )
    }

    // ── Atomic toggle ───────────────────────────────────────────────────

    @Test
    fun enablingPolicyAtomicallyRevealsCachedSensitiveItemsWithoutRefetch() = runTest(dispatcher) {
        val safe = mediaItem("tmdb-1", "Safe", adult = false)
        val sensitive = mediaItem("tmdb-2", "Sensitive", adult = true)
        val repo = ProbingMetadataRepository(searchMultiResults = listOf(safe, sensitive))
        val policyRepo = MutablePolicyRepo(lockedAdultPolicy())
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = NoopPrefs(),
            contentPolicyRepository = policyRepo,
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()
        val fetchesAfterCommit = repo.fetchCount
        assertEquals(listOf(safe), viewModel.state.value.results)

        policyRepo.emit(unlockedAdultPolicy())
        advanceUntilIdle()

        val after = viewModel.state.value
        assertEquals(
            listOf(safe, sensitive), after.results,
            "Toggle must reveal cached SENSITIVE in original commit order",
        )
        assertEquals(0, after.hiddenResultsCount)
        assertEquals(
            fetchesAfterCommit, repo.fetchCount,
            "Atomic re-projection must not trigger a network refetch",
        )
    }

    @Test
    fun disablingPolicyAtomicallyHidesCachedSensitiveItemsAgain() = runTest(dispatcher) {
        val safe = mediaItem("tmdb-1", "Safe", adult = false)
        val sensitive = mediaItem("tmdb-2", "Sensitive", adult = true)
        val policyRepo = MutablePolicyRepo(unlockedAdultPolicy())
        val viewModel = newViewModel(
            results = listOf(safe, sensitive),
            policy = unlockedAdultPolicy(),
            policyRepo = policyRepo,
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()
        assertEquals(listOf(safe, sensitive), viewModel.state.value.results)

        policyRepo.emit(lockedAdultPolicy())
        advanceUntilIdle()

        assertEquals(listOf(safe), viewModel.state.value.results)
        assertEquals(1, viewModel.state.value.hiddenResultsCount)
        // Sensitive item is preserved in the slice, not refetched.
        assertEquals(listOf(sensitive), viewModel.state.value.committedSearchSlice.sensitiveItems)
        assertEquals(listOf(sensitive), viewModel.state.value.committedSensitiveHiddenResults)
    }

    @Test
    fun policyToggleSequenceIsIdempotentAndPreservesOrder() = runTest(dispatcher) {
        val safe1 = mediaItem("tmdb-1", "Safe One", adult = false)
        val sens1 = mediaItem("tmdb-2", "Sens One", adult = true)
        val safe2 = mediaItem("tmdb-3", "Safe Two", adult = false)
        val sens2 = mediaItem("tmdb-4", "Sens Two", adult = true)
        val policyRepo = MutablePolicyRepo(unlockedAdultPolicy())
        val viewModel = newViewModel(
            results = listOf(safe1, sens1, safe2, sens2),
            policy = unlockedAdultPolicy(),
            policyRepo = policyRepo,
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()
        val openOrder = viewModel.state.value.results

        policyRepo.emit(lockedAdultPolicy())
        advanceUntilIdle()
        val lockedOrder = viewModel.state.value.results

        policyRepo.emit(unlockedAdultPolicy())
        advanceUntilIdle()
        val reopenedOrder = viewModel.state.value.results

        assertEquals(listOf(safe1, sens1, safe2, sens2), openOrder)
        assertEquals(listOf(safe1, safe2), lockedOrder)
        assertEquals(
            openOrder, reopenedOrder,
            "Re-enabling sensitive_material must restore the original commit order, not append at the end",
        )
    }

    // ── Hidden count correctness ────────────────────────────────────────

    @Test
    fun hiddenCountSumsUnresolvedAndSensitiveWhilePolicyDisabled() = runTest(dispatcher) {
        val safe = mediaItem("tmdb-1", "Safe", adult = false)
        val sensitiveA = mediaItem("tmdb-2", "Sens A", adult = true)
        val sensitiveB = mediaItem("tmdb-3", "Sens B", adult = true)
        val unresolved = mediaItem("tmdb-4", "Unknown", adult = null)

        val viewModel = newViewModel(
            results = listOf(safe, sensitiveA, unresolved, sensitiveB),
            policy = lockedAdultPolicy(),
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(safe), state.results)
        // unresolvedHiddenCount = 1 (the null-adult item), sensitive hidden = 2,
        // total = 3.
        assertEquals(3, state.hiddenResultsCount)
        assertEquals(1, state.committedUnresolvedHiddenCount)
    }

    @Test
    fun hiddenCountDropsToUnresolvedOnlyWhenPolicyEnabled() = runTest(dispatcher) {
        val safe = mediaItem("tmdb-1", "Safe", adult = false)
        val sensitive = mediaItem("tmdb-2", "Sens", adult = true)
        val unresolved = mediaItem("tmdb-3", "Unknown", adult = null)
        val policyRepo = MutablePolicyRepo(lockedAdultPolicy())
        val viewModel = newViewModel(
            results = listOf(safe, sensitive, unresolved),
            policy = lockedAdultPolicy(),
            policyRepo = policyRepo,
        )

        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()
        // Locked: 1 unresolved + 1 sensitive hidden = 2.
        assertEquals(2, viewModel.state.value.hiddenResultsCount)

        policyRepo.emit(unlockedAdultPolicy())
        advanceUntilIdle()
        // Unlocked: only unresolved is still hidden — sensitive is now visible.
        assertEquals(1, viewModel.state.value.hiddenResultsCount)
        assertEquals(listOf(safe, sensitive), viewModel.state.value.results)
    }

    // ── Sensitive-query gate ────────────────────────────────────────────

    @Test
    fun sensitiveQueryUnderLockedPolicyCommitsEmptyWithoutFetching() = runTest(dispatcher) {
        // Even if the API would return items, a query whose text itself
        // matches a SENSITIVE_KEYWORD (e.g. "porn") under a locked policy
        // must commit empty results and not even hit the metadata repo.
        // Defends the leak where TMDB returns adult==false documentaries
        // with explicit titles/posters that classify() lets through.
        val safeButTechnicallyExplicit = mediaItem("tmdb-9", "The Secret Lives", adult = false)
        val repo = ProbingMetadataRepository(searchMultiResults = listOf(safeButTechnicallyExplicit))
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = NoopPrefs(),
            contentPolicyRepository = MutablePolicyRepo(lockedAdultPolicy()),
        )

        viewModel.updateQuery("porn")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.results.isEmpty(), "Sensitive query under locked policy must commit empty results")
        assertEquals(0, repo.fetchCount, "Sensitive query under locked policy must not hit the network")
        assertTrue(state.hasActiveSearch, "hasActiveSearch should still be set so the UI shows the empty state")
    }

    @Test
    fun sensitiveQueryUnderUnlockedPolicyStillFetches() = runTest(dispatcher) {
        // Same query but with sensitive material enabled — the gate must
        // not fire, normal search proceeds.
        val item = mediaItem("tmdb-9", "Adult Documentary", adult = true)
        val repo = ProbingMetadataRepository(searchMultiResults = listOf(item))
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = NoopPrefs(),
            contentPolicyRepository = MutablePolicyRepo(unlockedAdultPolicy()),
        )

        viewModel.updateQuery("porn")
        advanceTimeBy(350L)
        advanceUntilIdle()

        assertEquals(1, repo.fetchCount, "Unlocked policy must still allow the fetch")
        assertEquals(listOf(item), viewModel.state.value.results)
    }

    @Test
    fun nonSensitiveQueryUnderLockedPolicyIsNotGated() = runTest(dispatcher) {
        // "matrix" is not sensitive — gate must not fire even under lock.
        val item = mediaItem("tmdb-9", "The Matrix", adult = false)
        val repo = ProbingMetadataRepository(searchMultiResults = listOf(item))
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = NoopPrefs(),
            contentPolicyRepository = MutablePolicyRepo(lockedAdultPolicy()),
        )

        viewModel.updateQuery("matrix")
        advanceTimeBy(350L)
        advanceUntilIdle()

        assertEquals(1, repo.fetchCount)
        assertEquals(listOf(item), viewModel.state.value.results)
    }

    // ── Render phase still consistent ───────────────────────────────────

    @Test
    fun renderPhaseEmptyWhenAllResolvedItemsHiddenByPolicy() = runTest(dispatcher) {
        // All sensitive + adult-flagged: locked policy hides every item.
        val sensitives = (1..5).map { mediaItem("s-$it", "Sens $it", adult = true) }
        val viewModel = newViewModel(
            results = sensitives,
            policy = lockedAdultPolicy(),
        )

        viewModel.updateQuery("nyt")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.results.isEmpty())
        assertEquals(SearchRenderPhase.EMPTY, state.renderPhase)
        assertEquals(5, state.hiddenResultsCount)
    }

    @Test
    fun renderPhaseResultsAfterTogglingSensitiveOn() = runTest(dispatcher) {
        val sensitives = (1..3).map { mediaItem("s-$it", "Sens $it", adult = true) }
        val policyRepo = MutablePolicyRepo(lockedAdultPolicy())
        val viewModel = newViewModel(
            results = sensitives,
            policy = lockedAdultPolicy(),
            policyRepo = policyRepo,
        )

        viewModel.updateQuery("xyz")
        advanceTimeBy(350L)
        advanceUntilIdle()
        assertEquals(SearchRenderPhase.EMPTY, viewModel.state.value.renderPhase)

        policyRepo.emit(unlockedAdultPolicy())
        advanceUntilIdle()
        assertEquals(SearchRenderPhase.RESULTS, viewModel.state.value.renderPhase)
        assertEquals(3, viewModel.state.value.results.size)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun newViewModel(
        results: List<MediaItem> = emptyList(),
        queryResults: Map<String, List<MediaItem>>? = null,
        policy: ContentPolicyState = lockedAdultPolicy(),
        policyRepo: ContentPolicyRepository = MutablePolicyRepo(policy),
    ): SearchViewModel {
        val repo = if (queryResults != null) {
            QueryRepoImpl(queryResults)
        } else {
            ProbingMetadataRepository(searchMultiResults = results)
        }
        return SearchViewModel(
            metadataRepo = repo,
            prefsRepo = NoopPrefs(),
            contentPolicyRepository = policyRepo,
        )
    }

    private fun mediaItem(id: String, title: String, adult: Boolean?): MediaItem =
        MediaItem(
            id = id,
            tmdbId = id.substringAfterLast('-').toIntOrNull(),
            title = title,
            type = MediaType.MOVIE,
            adult = adult,
        )

    private fun lockedAdultPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        isLoading = false,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = false,
    )

    private fun unlockedAdultPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        isLoading = false,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = true,
    )

    private class MutablePolicyRepo(initial: ContentPolicyState) : ContentPolicyRepository {
        private val s = MutableStateFlow(initial)
        override val state: StateFlow<ContentPolicyState> = s
        fun emit(next: ContentPolicyState) { s.value = next }
        override suspend fun refresh() {}
        override suspend fun submitDob(dateOfBirth: String) {}
        override suspend fun enableSensitive(policyVersion: String) {}
        override suspend fun disableSensitive() {}
    }

    private class NoopPrefs : PreferencesRepository {
        override suspend fun getString(key: String): String? = null
        override suspend fun setString(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }

    private class ProbingMetadataRepository(
        private val searchMultiResults: List<MediaItem>,
    ) : MetadataRepository {
        var fetchCount: Int = 0
            private set

        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
            fetchCount += 1
            return searchMultiResults
        }

        override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult =
            PagedResult(items = searchMultiResults, page = 1, totalPages = 1, totalResults = searchMultiResults.size)

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
        override suspend fun getTrendingPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getPopularPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun discover(
            type: String, page: Int, sortBy: String, withGenres: String?, minRating: Float?,
            year: Int?, yearTo: Int?, runtimeGte: Int?, runtimeLte: Int?,
            originCountries: String?, originalLanguage: String?, certification: String?,
            certificationGte: String?, certificationLte: String?, certificationCountry: String?,
            withCast: String?, withCrew: String?, withWatchProviders: String?, watchRegion: String?,
            withKeywords: String?,
        ): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }

    private class QueryRepoImpl(
        private val results: Map<String, List<MediaItem>>,
    ) : MetadataRepository {
        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> =
            results[query] ?: emptyList()
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
        override suspend fun getTrendingPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getPopularPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun discover(
            type: String, page: Int, sortBy: String, withGenres: String?, minRating: Float?,
            year: Int?, yearTo: Int?, runtimeGte: Int?, runtimeLte: Int?,
            originCountries: String?, originalLanguage: String?, certification: String?,
            certificationGte: String?, certificationLte: String?, certificationCountry: String?,
            withCast: String?, withCrew: String?, withWatchProviders: String?, watchRegion: String?,
            withKeywords: String?,
        ): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }
}
