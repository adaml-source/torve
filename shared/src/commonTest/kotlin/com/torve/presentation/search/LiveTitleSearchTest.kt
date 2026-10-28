package com.torve.presentation.search

import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.metadata.MetadataRepositoryImpl
import com.torve.data.metadata.TmdbApiClient
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.catalog.SortOption
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class LiveTitleSearchTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() = Dispatchers.resetMain()
    private fun title(id: Int, name: String = "Guardians of the Galaxy", adult: Boolean? = false) =
        MediaItem("tmdb:$id", tmdbId = id, title = name, type = MediaType.MOVIE, adult = adult, voteCount = 10000)
    private val prefs = object : PreferencesRepository {
        override suspend fun getString(key: String): String? = null
        override suspend fun setString(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }
    private fun policy(value: ContentPolicyState = ContentPolicyState.lockedBootstrap(true)) = object : ContentPolicyRepository {
        override val state = MutableStateFlow(value)
        override suspend fun refresh() {}
        override suspend fun submitDob(dateOfBirth: String) {}
        override suspend fun enableSensitive(policyVersion: String) {}
        override suspend fun disableSensitive() {}
    }
    private fun http(): HttpClient = HttpClient(MockEngine { respond("""{"page":1,"total_pages":1,"results":[]}""",
        headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    private fun repository(client: HttpClient) = MetadataRepositoryImpl(TmdbApiClient(client,
        apiKeyProvider = { "fixture" }, resolvedHostProvider = { null }))

    @Test fun coldPrefixCanPublishPostersBeforeOrdinarySearchAndPeopleComplete() = runTest(dispatcher) {
        val client = http(); val gate = CompletableDeferred<Unit>(); val people = CompletableDeferred<Unit>()
        val movie = title(118340)
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult {
                gate.await(); return PagedResult(emptyList(), 1, 1, 0)
            }
            override fun titleSuggestions(query: String, type: String?) = flowOf(listOf(movie))
            override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> { people.await(); return emptyList() }
        }
        val vm = SearchViewModel(repo, prefs, policy())
        vm.updateQuery("gua"); advanceTimeBy(121); runCurrent()
        assertEquals(listOf(movie), vm.state.value.displayItems)
        assertTrue(vm.state.value.isSearching, "The catalogue page is still waiting; its spinner must not replace these posters")
        gate.complete(Unit); advanceTimeBy(300); runCurrent()
        assertFalse(vm.state.value.isSearching, "People must not hold the title loading state")
        people.complete(Unit); advanceUntilIdle(); client.close()
    }

    @Test fun extendingAndShorteningWordsReusesKnownPostersSynchronously() = runTest(dispatcher) {
        val client = http(); val movie = title(1)
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) = PagedResult(listOf(movie), 1, 1, 1)
            override fun titleSuggestions(query: String, type: String?) = flowOf(emptyList<MediaItem>())
        }
        val vm = SearchViewModel(repo, prefs, policy()); vm.updateQuery("gua"); advanceUntilIdle()
        for (query in listOf("guar", "guardian", "guardians", "gua")) {
            vm.updateQuery(query)
            assertEquals(listOf(movie), vm.state.value.displayItems, "No coroutine or request has run for $query")
        }
        advanceUntilIdle(); client.close()
    }

    @Test fun newQueriesCancelLateCompletionsAndCannotShowTheirPosters() = runTest(dispatcher) {
        val client = http(); val gate = CompletableDeferred<Unit>()
        val repo = object : MetadataRepository by repository(client) {
            override fun titleSuggestions(query: String, type: String?) = flow {
                if (query == "gua") withContext(NonCancellable) { gate.await() }
                emit(listOf(title(if (query == "gua") 1 else 2, query)))
            }
        }
        val vm = SearchViewModel(repo, prefs, policy()); vm.updateQuery("gua"); advanceTimeBy(121); runCurrent()
        vm.updateQuery("bat"); gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("bat"), vm.state.value.displayItems.map { it.title }); client.close()
    }

    @Test fun unknownClassificationCannotBeUpgradedByALaterCompletion() = runTest(dispatcher) {
        val client = http(); val gate = CompletableDeferred<Unit>(); val unknown = title(1, adult = null)
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) = PagedResult(listOf(unknown), 1, 1, 1)
            override fun titleSuggestions(query: String, type: String?) = flow { gate.await(); emit(listOf(unknown.copy(adult = false))) }
        }
        val vm = SearchViewModel(repo, prefs, policy()); vm.updateQuery("gua"); advanceTimeBy(121); runCurrent()
        assertTrue(vm.state.value.displayItems.isEmpty()); assertEquals(1, vm.state.value.committedUnresolvedHiddenCount)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.state.value.displayItems.isEmpty()); assertEquals(1, vm.state.value.committedUnresolvedHiddenCount); client.close()
    }

    @Test fun autocompleteDoesNotBypassFiltersOrTheSensitiveQueryGate() = runTest(dispatcher) {
        val client = http(); var calls = 0
        val repo = object : MetadataRepository by repository(client) {
            override fun titleSuggestions(query: String, type: String?) = flow { calls++; emit(listOf(title(1).copy(year = 2014))) }
        }
        val vm = SearchViewModel(repo, prefs, policy())
        vm.applyFilter(SearchFilter(yearFrom = 2020)); vm.updateQuery("gua"); advanceUntilIdle()
        assertTrue(vm.state.value.displayItems.isEmpty()); assertEquals(1, calls)
        vm.updateQuery("porn"); advanceUntilIdle()
        assertTrue(vm.state.value.displayItems.isEmpty()); assertEquals(1, calls); client.close()
    }

    @Test fun lateSuggestionsKeepPagesAppendedWithLoadMore() = runTest(dispatcher) {
        val client = http(); val gate = CompletableDeferred<Unit>()
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) = PagedResult(listOf(title(page, "Guardian $page")), page, 2, 2)
            override fun titleSuggestions(query: String, type: String?) = flow { gate.await(); emit(listOf(title(3))) }
        }
        val vm = SearchViewModel(repo, prefs, policy()); vm.updateQuery("guardian"); advanceTimeBy(121); runCurrent()
        vm.loadMore(); runCurrent(); assertEquals(2, vm.state.value.searchPage)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(setOf(1,2,3), vm.state.value.displayItems.map { it.tmdbId }.toSet())
        assertEquals(2, vm.state.value.searchPage); assertFalse(vm.state.value.searchHasMore); client.close()
    }

    @Test fun bestMatchUsesPrefixesAndPunctuationInsteadOfReviewScores() {
        assertEquals(SortOption.RELEVANCE, SearchFilter().sortBy)
        val movie = title(1)
        for (q in listOf("gua", "guardian", "guardians of the gal", "Galaxy")) assertTrue(movie.matchesTitlePrefix(q), q)
        assertFalse(movie.matchesTitlePrefix("bat"))
        assertTrue(title(2,"Spider-Man: Homecoming").matchesTitlePrefix("spider man home"))
        val unrelated = title(3, "Another title").copy(voteCount = 9999999)
        assertEquals(movie, listOf(unrelated,movie).bestTitleMatches("guardian").first())
        assertEquals(movie, listOf(title(4,"Guardian").copy(voteCount=3),movie).bestTitleMatches("guardian").first())
    }

    @Test fun languageInvalidationClearsTheInstantPrefixIndex() = runTest(dispatcher) {
        val client = http(); var first = true
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) = PagedResult(if (first) listOf(title(1)) else emptyList(), 1,1,0)
            override fun titleSuggestions(query: String, type: String?) = flowOf(emptyList<MediaItem>())
        }
        val vm = SearchViewModel(repo,prefs,policy()); vm.updateQuery("guardians"); advanceUntilIdle()
        first = false; vm.refreshLocalizedContent(); advanceUntilIdle(); vm.updateQuery("gua")
        assertTrue(vm.state.value.displayItems.isEmpty()); advanceUntilIdle(); client.close()
    }

    @Test fun cachedMatchesStayVisibleWhileThePrefixPageAndCompletionsRefresh() = runTest(dispatcher) {
        val client = http(); val gate = CompletableDeferred<Unit>(); val movie = title(1)
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) =
                PagedResult(if (query == "guardians") listOf(movie) else emptyList(), 1,1,0)
            override fun titleSuggestions(query: String, type: String?) = flow {
                if (query != "guardians") gate.await()
                emit(emptyList<MediaItem>())
            }
            override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        }
        val vm = SearchViewModel(repo,prefs,policy()); vm.updateQuery("guardians"); advanceUntilIdle()
        vm.updateQuery("gua"); advanceTimeBy(121); runCurrent()
        assertEquals(listOf(movie),vm.state.value.displayItems)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(movie),vm.state.value.displayItems); client.close()
    }

    @Test fun freshUnknownEvidenceRemovesACachedTitleFromLaterPrefixSuggestions() = runTest(dispatcher) {
        val client = http(); var known = true
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) =
                PagedResult(listOf(title(1,adult=if (known) false else null)),1,1,1)
            override fun titleSuggestions(query: String, type: String?) = flowOf(emptyList<MediaItem>())
        }
        val vm = SearchViewModel(repo,prefs,policy()); vm.updateQuery("guardians"); advanceUntilIdle()
        known=false; vm.updateQuery("gua"); advanceUntilIdle()
        assertTrue(vm.state.value.displayItems.isEmpty())
        vm.updateQuery("guard"); assertTrue(vm.state.value.displayItems.isEmpty())
        advanceUntilIdle(); client.close()
    }

    @Test fun paginationThatFinishesDuringSuggestionFilteringIsPreserved() = runTest(dispatcher) {
        val client = http(); val completion = CompletableDeferred<Unit>(); val filtering = CompletableDeferred<Unit>()
        var blockNextFilter = false
        val gatedPrefs = object : PreferencesRepository by prefs {
            override suspend fun getString(key: String): String? {
                if (blockNextFilter && key == "dedupe_results") { blockNextFilter=false; filtering.await() }
                return null
            }
        }
        val repo = object : MetadataRepository by repository(client) {
            override suspend fun searchMultiPaged(query: String, page: Int, type: String?) = PagedResult(listOf(title(page,"Guardian $page")),page,2,2)
            override fun titleSuggestions(query: String, type: String?) = flow { completion.await(); emit(listOf(title(3))) }
        }
        val vm=SearchViewModel(repo,gatedPrefs,policy());vm.updateQuery("guardian");advanceTimeBy(121);runCurrent()
        blockNextFilter=true;completion.complete(Unit);runCurrent()
        assertFalse(blockNextFilter, "Completion filtering must have reached the suspended preference lookup")
        assertFalse(filtering.isCompleted)
        vm.loadMore();runCurrent();assertEquals(2,vm.state.value.searchPage)
        filtering.complete(Unit);advanceUntilIdle()
        assertEquals(setOf(1,2,3),vm.state.value.displayItems.map { it.tmdbId }.toSet())
        client.close()
    }
}
