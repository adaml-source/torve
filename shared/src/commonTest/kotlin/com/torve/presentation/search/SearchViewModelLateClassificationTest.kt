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
import com.torve.presentation.contentpolicy.ContentPolicyFilter
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
 * Regression tests for the late-classification flicker. The core defense
 * this file locks in:
 *
 *  - Items whose sensitivity cannot be positively confirmed at commit
 *    time (e.g. `adult == null` from a TMDB response where the API
 *    omitted the field) never enter the rendered list. They count
 *    toward `hiddenResultsCount` but never surface as a card, so no
 *    poster request is made and no "poster appears → data arrives →
 *    item removed" flicker is possible.
 *  - The commit-time filter uses STRICT classification; the combine-
 *    stage filter stays permissive, preserving policy-toggle response
 *    for the existing SAFE committed list.
 *  - `hiddenResultsCount` is preserved through the combine stage (not
 *    zeroed when the committed list is already clean).
 *
 * Companion tests for the enrichment-preservation side of the fix
 * (ratings-only merge so classify-relevant fields cannot change after
 * commit) live at the code-review level — the merge is structural
 * (`existing.copy(ratings = enrichedItem.ratings)`) and cannot mutate
 * any other field by construction. See `SearchViewModel.enrichResults`
 * / `enrichDiscover` for the single-field merge.
 */
class SearchViewModelLateClassificationTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    // ── Unresolved classification → no render ──

    @Test
    fun itemWithNullAdultFlagIsHiddenAtCommit() = runTest(dispatcher) {
        // TMDB returns a result with adult=null (field omitted by API).
        // Classify cannot positively confirm safety from metadata alone.
        // The item must never enter the rendered list, even though no
        // sensitive keyword matched.
        val unresolved = MediaItem(
            id = "tmdb-1",
            tmdbId = 1,
            title = "The Wild Ones",   // innocuous title, no keyword match
            type = MediaType.MOVIE,
            adult = null,              // ← unresolved
            overview = "Two friends embark on a journey.",
        )
        val repo = QueryRepo(mapOf("wild" to listOf(unresolved)))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())
        viewModel.updateQuery("wild")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(
            state.results.isEmpty(),
            "Item with adult==null and no positive safety signal must not be committed, saw ${state.results}",
        )
        assertEquals(
            SearchRenderPhase.EMPTY,
            state.renderPhase,
            "Render phase must be EMPTY when every result is unresolved",
        )
        assertEquals(
            1, state.hiddenResultsCount,
            "Unresolved item must count toward hiddenResultsCount",
        )
    }

    @Test
    fun itemsWithAdultFalseAndCleanMetadataPassThrough() = runTest(dispatcher) {
        // Positive safety signal: adult is explicitly false. Classify
        // returns SAFE. Item renders normally.
        val safe = MediaItem(
            id = "tmdb-1",
            tmdbId = 1,
            title = "Friendly Family Film",
            type = MediaType.MOVIE,
            adult = false,
            overview = "A cheerful story about dogs.",
        )
        val repo = QueryRepo(mapOf("family" to listOf(safe)))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())
        viewModel.updateQuery("family")
        advanceTimeBy(350L)
        advanceUntilIdle()

        assertEquals(listOf(safe), viewModel.state.value.results)
        assertEquals(SearchRenderPhase.RESULTS, viewModel.state.value.renderPhase)
    }

    @Test
    fun mixedResponseSplitsIntoVisibleAndHidden() = runTest(dispatcher) {
        // Three items: one confirmed-safe, one confirmed-sensitive,
        // one unresolved. Only the confirmed-safe appears; the other
        // two count toward hiddenResultsCount.
        val safe = MediaItem(
            id = "tmdb-1", tmdbId = 1, title = "Clean Movie",
            type = MediaType.MOVIE, adult = false,
        )
        val sensitive = MediaItem(
            id = "tmdb-2", tmdbId = 2, title = "Adult Feature",
            type = MediaType.MOVIE, adult = true,
        )
        val unresolved = MediaItem(
            id = "tmdb-3", tmdbId = 3, title = "Ambiguous Title",
            type = MediaType.MOVIE, adult = null,
        )
        val repo = QueryRepo(mapOf("mix" to listOf(safe, sensitive, unresolved)))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())
        viewModel.updateQuery("mix")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(safe), state.results)
        assertEquals(2, state.hiddenResultsCount)
        assertEquals(SearchRenderPhase.RESULTS, state.renderPhase)
    }

    // ── The "porn" scenario ──

    @Test
    fun pornQueryUnderLockedPolicyIsGatedBeforeFetch() = runTest(dispatcher) {
        // The sensitive-query gate pre-empts the fetch entirely when the
        // query string itself contains a SENSITIVE_KEYWORD and the policy
        // is locked. This prevents the documentary-leak case where TMDB
        // returns adult==false items with explicit titles/posters that
        // classify() can't distinguish from genuinely-safe content.
        // hiddenResultsCount is 0 (we never fetched, never classified).
        val confirmedSensitive = (1..10).map {
            MediaItem(id = "s-$it", tmdbId = it, title = "Sensitive $it", type = MediaType.MOVIE, adult = true)
        }
        val unresolved = (11..15).map {
            MediaItem(id = "u-$it", tmdbId = it, title = "Title $it", type = MediaType.MOVIE, adult = null)
        }
        val repo = QueryRepo(mapOf("porn" to confirmedSensitive + unresolved))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())

        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        viewModel.updateQuery("porn")
        advanceTimeBy(350L)
        advanceUntilIdle()
        collector.cancel()

        val state = viewModel.state.value
        assertTrue(state.results.isEmpty(), "No item should render for a gated query")
        assertEquals(SearchRenderPhase.EMPTY, state.renderPhase)
        // Across every emission: no leaked items.
        val leaks = emissions.flatMap { it.results }
        assertTrue(leaks.isEmpty(), "No item should ever appear for a gated query, saw: $leaks")
    }

    @Test
    fun queryKeywordInTitleIsGatedRegardlessOfItemClassification() = runTest(dispatcher) {
        // Same gate behavior — query "porn" is sensitive, so we never
        // fetch. Test items wouldn't matter; visible is empty.
        val keywordItem = MediaItem(
            id = "tmdb-42", tmdbId = 42, title = "Pornography (Documentary)",
            type = MediaType.MOVIE, adult = false,
            overview = "A historical overview of censorship laws.",
        )
        val sensitive = MediaItem(
            id = "tmdb-43", tmdbId = 43, title = "Adult Film",
            type = MediaType.MOVIE, adult = true,
        )
        val repo = QueryRepo(mapOf("porn" to listOf(keywordItem, sensitive)))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())

        viewModel.updateQuery("porn")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun unrelatedQueryWithUnresolvedItemsIsStillEmpty() = runTest(dispatcher) {
        // Query doesn't trigger keyword classification, but response
        // has items without adult flag. Strict mode blocks them.
        val unresolved = (1..3).map {
            MediaItem(id = "u-$it", tmdbId = it, title = "Unresolved $it", type = MediaType.MOVIE, adult = null)
        }
        val repo = QueryRepo(mapOf("weather" to unresolved))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())
        viewModel.updateQuery("weather")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.results.isEmpty())
        assertEquals(3, state.hiddenResultsCount)
    }

    // ── Invariants across emissions ──

    @Test
    fun hiddenResultsCountPreservedAcrossCombineStage() = runTest(dispatcher) {
        // After commit, hiddenResultsCount reflects the commit decision.
        // It must not regress to 0 when the combine stage re-filters
        // the now-clean committed list.
        val safe = MediaItem(id = "tmdb-1", tmdbId = 1, title = "Safe", type = MediaType.MOVIE, adult = false)
        val unresolved = MediaItem(id = "tmdb-2", tmdbId = 2, title = "Unknown", type = MediaType.MOVIE, adult = null)
        val repo = QueryRepo(mapOf("qq" to listOf(safe, unresolved)))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())
        viewModel.updateQuery("qq")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(safe), state.results)
        assertEquals(1, state.hiddenResultsCount, "Unresolved must count; must not be zeroed by combine filter")
    }

    @Test
    fun noProvisionalAdultRiskItemInAnyEmission() = runTest(dispatcher) {
        // Across the whole stream of emissions — IDLE → SEARCHING →
        // RESULTS/EMPTY — `state.results` must never contain a
        // sensitive or unresolved item. Commit is the only path that
        // writes to results, and it writes only confirmed-safe items.
        val sensitive = MediaItem(id = "s-1", tmdbId = 1, title = "Adult", type = MediaType.MOVIE, adult = true)
        val unresolved = MediaItem(id = "u-1", tmdbId = 2, title = "Unknown", type = MediaType.MOVIE, adult = null)
        val repo = QueryRepo(mapOf("qq" to listOf(sensitive, unresolved)))
        val viewModel = buildViewModel(repo, policy = lockedAdultPolicy())

        val emissions = mutableListOf<SearchUiState>()
        val collector = launch { viewModel.state.collect { emissions += it } }

        viewModel.updateQuery("qq")
        advanceTimeBy(350L)
        advanceUntilIdle()
        collector.cancel()

        emissions.forEach { snapshot ->
            assertFalse(
                snapshot.results.any { it.adult == true || it.adult == null },
                "Provisional adult-risk item leaked through results: $snapshot",
            )
        }
    }

    @Test
    fun enforcementDisabledLetsUnresolvedThrough() = runTest(dispatcher) {
        // Non-Play channel (Amazon etc.) where enforcementEnabled is
        // false — strict mode in filterItems short-circuits because
        // enforcement is off, so unresolved items still pass through
        // unchanged. This preserves the Amazon behavior where sensitivity
        // filtering is not applied.
        val unresolved = MediaItem(id = "u-1", tmdbId = 1, title = "Unknown", type = MediaType.MOVIE, adult = null)
        val repo = QueryRepo(mapOf("qq" to listOf(unresolved)))
        val viewModel = buildViewModel(repo, policy = ContentPolicyState.unrestricted())
        viewModel.updateQuery("qq")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(unresolved), state.results)
        assertEquals(0, state.hiddenResultsCount)
    }

    // ── helpers ──

    private fun buildViewModel(
        repo: MetadataRepository,
        policy: ContentPolicyState = ContentPolicyState.unrestricted(),
    ): SearchViewModel {
        return SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePrefs(),
            contentPolicyRepository = FakePolicyRepo(policy),
            contentPolicyFilter = ContentPolicyFilter(),
            ratingsEnricher = null, // enrichment-preservation is structural, see file doc
        )
    }

    private fun lockedAdultPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        isLoading = false,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = false,
    )

    private class FakePolicyRepo(initial: ContentPolicyState) : ContentPolicyRepository {
        private val s = MutableStateFlow(initial)
        override val state: StateFlow<ContentPolicyState> = s
        override suspend fun refresh() {}
        override suspend fun submitDob(dateOfBirth: String) {}
        override suspend fun enableSensitive(policyVersion: String) {}
        override suspend fun disableSensitive() {}
    }

    private class FakePrefs : PreferencesRepository {
        override suspend fun getString(key: String): String? = null
        override suspend fun setString(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }

    private class QueryRepo(
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
