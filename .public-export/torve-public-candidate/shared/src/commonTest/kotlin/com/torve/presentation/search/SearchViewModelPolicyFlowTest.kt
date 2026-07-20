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
import kotlin.test.assertTrue

class SearchViewModelPolicyFlowTest {

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
    fun sensitiveItemsAreNeverEmittedWhileSensitiveMaterialIsDisabled() = runTest(dispatcher) {
        val safeItem = mediaItem(id = "tmdb-1", title = "Safe Movie", adult = false)
        val sensitiveItem = mediaItem(id = "tmdb-2", title = "Sensitive Movie", adult = true)

        val repo = FakeMetadataRepository(searchMultiResults = listOf(safeItem, sensitiveItem))
        val policyRepo = FakeContentPolicyRepository(lockedAdultPolicy())
        val fetches = repo.searchMultiFetchCount

        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        viewModel.updateQuery("sensitive")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val visible = viewModel.state.value.results
        assertEquals(
            listOf(safeItem),
            visible,
            "The UI-observable StateFlow must never expose sensitive items when policy is locked",
        )
        assertEquals(1, viewModel.state.value.hiddenResultsCount)
        assertTrue(repo.searchMultiFetchCount > fetches)
    }

    @Test
    fun enablingSensitiveMaterialReEmitsFilteredListWithoutRefetch() = runTest(dispatcher) {
        val safeItem = mediaItem(id = "tmdb-1", title = "Safe Movie", adult = false)
        val sensitiveItem = mediaItem(id = "tmdb-2", title = "Sensitive Movie", adult = true)

        val repo = FakeMetadataRepository(searchMultiResults = listOf(safeItem, sensitiveItem))
        val policyRepo = FakeContentPolicyRepository(lockedAdultPolicy())

        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        viewModel.updateQuery("mixed")
        advanceTimeBy(350L)
        advanceUntilIdle()

        assertEquals(listOf(safeItem), viewModel.state.value.results)
        val fetchesBeforeToggle = repo.searchMultiFetchCount

        // Flip the policy on — should re-filter the existing raw list without refetching.
        policyRepo.emit(unlockedAdultPolicy())
        advanceUntilIdle()

        assertEquals(
            listOf(safeItem, sensitiveItem),
            viewModel.state.value.results,
            "After enabling sensitive material the full list must be visible",
        )
        assertEquals(0, viewModel.state.value.hiddenResultsCount)
        assertEquals(
            fetchesBeforeToggle,
            repo.searchMultiFetchCount,
            "Toggling sensitive_material must not trigger a fresh API search",
        )
    }

    @Test
    fun policyEnforcementDisabledPassesRawResultsThrough() = runTest(dispatcher) {
        val safeItem = mediaItem(id = "tmdb-1", title = "Safe Movie", adult = false)
        val sensitiveItem = mediaItem(id = "tmdb-2", title = "Sensitive Movie", adult = true)

        val repo = FakeMetadataRepository(searchMultiResults = listOf(safeItem, sensitiveItem))
        // Non-Google-Play channel or enforcement disabled — filter is a no-op.
        val policyRepo = FakeContentPolicyRepository(ContentPolicyState.unrestricted())

        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        viewModel.updateQuery("mixed")
        advanceTimeBy(350L)
        advanceUntilIdle()

        assertEquals(listOf(safeItem, sensitiveItem), viewModel.state.value.results)
        assertEquals(0, viewModel.state.value.hiddenResultsCount)
    }

    @Test
    fun emissionSequenceNeverIncludesRawBetweenLoadedAndFiltered() = runTest(dispatcher) {
        val safeItem = mediaItem(id = "tmdb-1", title = "Safe", adult = false)
        val sensitiveItem = mediaItem(id = "tmdb-2", title = "Sensitive", adult = true)

        val repo = FakeMetadataRepository(searchMultiResults = listOf(safeItem, sensitiveItem))
        val policyRepo = FakeContentPolicyRepository(lockedAdultPolicy())
        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        val emissions = mutableListOf<List<MediaItem>>()
        val collector = launch {
            viewModel.state.collect { emissions.add(it.results) }
        }

        viewModel.updateQuery("mixed")
        advanceTimeBy(350L)
        advanceUntilIdle()

        val fetchesAfterSearch = repo.searchMultiFetchCount
        policyRepo.emit(unlockedAdultPolicy())
        advanceUntilIdle()

        collector.cancel()

        // Every emission must be policy-compliant for the policy in effect at
        // that moment. There is no intermediate emission containing unfiltered
        // raw items. Sensitive items only appear once policy is unlocked.
        assertTrue(emissions.isNotEmpty(), "state flow must have produced emissions")
        emissions.forEach { list ->
            if (sensitiveItem in list) {
                // Only legal after the policy flip.
                assertTrue(
                    list.contains(safeItem),
                    "Any emission containing the sensitive item must also contain the safe item",
                )
            }
        }
        // Final state has both items (policy unlocked at the end).
        assertEquals(listOf(safeItem, sensitiveItem), emissions.last())
        val postLoadEmission = emissions.indexOfFirst { it == listOf(safeItem) }
        assertTrue(postLoadEmission >= 0, "Filtered [safeItem] emission must appear before policy toggle")
        // A sensitive-only emission would imply the raw list leaked out unfiltered.
        assertTrue(
            emissions.none { it == listOf(sensitiveItem) },
            "A sensitive-only emission would imply the raw list was exposed before the safe items rendered",
        )
        // Exactly one network fetch was performed throughout the whole dance.
        assertEquals(fetchesAfterSearch, repo.searchMultiFetchCount)
    }

    @Test
    fun lockingPolicyWhileResultsAreVisibleRemovesSensitiveItems() = runTest(dispatcher) {
        val safeItem = mediaItem(id = "tmdb-1", title = "Safe Movie", adult = false)
        val sensitiveItem = mediaItem(id = "tmdb-2", title = "Sensitive Movie", adult = true)

        val repo = FakeMetadataRepository(searchMultiResults = listOf(safeItem, sensitiveItem))
        val policyRepo = FakeContentPolicyRepository(unlockedAdultPolicy())

        val viewModel = SearchViewModel(
            metadataRepo = repo,
            prefsRepo = FakePreferencesRepository(),
            contentPolicyRepository = policyRepo,
        )

        viewModel.updateQuery("mixed")
        advanceTimeBy(350L)
        advanceUntilIdle()

        assertEquals(listOf(safeItem, sensitiveItem), viewModel.state.value.results)
        val fetchesBeforeToggle = repo.searchMultiFetchCount

        policyRepo.emit(lockedAdultPolicy())
        advanceUntilIdle()

        assertEquals(listOf(safeItem), viewModel.state.value.results)
        assertEquals(1, viewModel.state.value.hiddenResultsCount)
        assertEquals(fetchesBeforeToggle, repo.searchMultiFetchCount)
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

    private fun unlockedAdultPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        isLoading = false,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = true,
    )

    private fun mediaItem(id: String, title: String, adult: Boolean): MediaItem =
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

        fun emit(next: ContentPolicyState) {
            _state.value = next
        }

        override suspend fun refresh() {}
        override suspend fun submitDob(dateOfBirth: String) {}
        override suspend fun enableSensitive(policyVersion: String) {}
        override suspend fun disableSensitive() {}
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val store = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) {
            store[key] = value
        }
        override suspend fun remove(key: String) {
            store.remove(key)
        }
    }

    private class FakeMetadataRepository(
        private val searchMultiResults: List<MediaItem>,
    ) : MetadataRepository {
        var searchMultiFetchCount: Int = 0
            private set

        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
            searchMultiFetchCount += 1
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
