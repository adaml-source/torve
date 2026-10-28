package com.torve.presentation.search

import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.ParentalFilter
import com.torve.domain.model.PagedResult
import com.torve.domain.model.SensitiveClassification
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.calculateTorveScore
import com.torve.domain.model.defaultTorveWeights
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.ProfileRepository
import com.torve.presentation.catalog.SortOption
import com.torve.presentation.catalog.highestImdbFirst
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SearchViewModel(
    private val metadataRepo: MetadataRepository,
    private val prefsRepo: PreferencesRepository,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
    private val profileRepository: ProfileRepository? = null,
    private val aiResolver: SearchAiResolver? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Single source of truth. The grid-driving fields ([SearchUiState.results],
     * [SearchUiState.discoverResults], [SearchUiState.hiddenResultsCount],
     * [SearchUiState.renderPhase]) are the *committed visible projection* for
     * the policy in effect at the moment the projection was computed.
     *
     * The committed slices ([SearchUiState.committedSearchSlice] etc.) carry
     * the SAFE+SENSITIVE classified buckets so a policy toggle can re-project
     * visible items atomically â€” no live `combine`-stage filterItems run over
     * a mixed raw list ever drives the grid.
     */
    private val _state = contentPolicyFilter.profileState(SearchUiState())

    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /**
     * Monotonic search-generation counter. Every entry point that kicks off
     * a search (debounced query, invalidation coordinator, applyFilter,
     * clearSearch, discoverWithFilters) increments this. `_state.update`
     * writes are guarded by it so a late result from a superseded generation
     * can never overwrite the latest committed state.
     */
    private val searchGeneration = MutableStateFlow(0L)
    private val generationOwners = mutableMapOf<Long, String>()
    private fun nextGeneration(): Long {
        val next = searchGeneration.updateAndGet { it + 1L }
        generationOwners.clear(); generationOwners[next] = contentPolicyFilter.profileOwnerKey()
        return next
    }
    private fun generationIsCurrent(value: Long) = value == searchGeneration.value &&
        generationOwners[value] == contentPolicyFilter.profileOwnerKey()

    /**
     * Snapshot the policy used by the commit-time classification split and
     * the visible projection. When no repository is wired (tests), assume
     * enforcement is ON and bootstrap-locked â€” fail-closed so the UI never
     * sees an unrestricted policy window.
     */
    private fun currentPolicySnapshot(): ContentPolicyState {
        return contentPolicyRepository?.state?.value
            ?: ContentPolicyState.lockedBootstrap(enforcementEnabled = true)
    }

    private val policyFlow: Flow<ContentPolicyState> =
        contentPolicyRepository?.state
            ?: flowOf(ContentPolicyState.lockedBootstrap(enforcementEnabled = true))

    private val queryFlow = MutableStateFlow("")
    private var submittedSearchJob: Job? = null
    private var aiPlan: Pair<String, com.torve.data.ai.KeywordSearchResult>? = null

    // Cancel any in-flight rating enrichment when a new query/filter arrives â€”
    // results will be replaced anyway, so the old enrichment is wasted work.
    private var resultsEnrichJob: Job? = null
    private var discoverEnrichJob: Job? = null
    private var paginationJob: Job? = null
    // Resolved catalogue titles only; bounded and scoped to the same owner and
    // language/invalidation epoch as retained pages. Never store UNKNOWN items.
    private val knownTitles = linkedMapOf<String, ClassifiedMediaItem>()
    private var knownTitlesOwner = contentPolicyFilter.profileOwnerKey()
    private var knownTitlesEnforced = currentPolicySnapshot().enforcementEnabled

    private fun rememberTitles(slice: CommittedSearchSlice) {
        val owner = contentPolicyFilter.profileOwnerKey()
        val enforced = currentPolicySnapshot().enforcementEnabled
        if (owner != knownTitlesOwner || enforced != knownTitlesEnforced) {
            knownTitles.clear(); knownTitlesOwner = owner; knownTitlesEnforced = enforced
        }
        slice.ordered.forEach { tagged ->
            val key = "${tagged.item.type}:${tagged.item.tmdbId ?: tagged.item.id}"
            knownTitles.remove(key); knownTitles[key] = tagged
        }
        while (knownTitles.size > 400) knownTitles.remove(knownTitles.keys.first())
    }

    private fun instantTitles(query: String, filter: SearchFilter): CommittedSearchSlice {
        if (knownTitlesOwner != contentPolicyFilter.profileOwnerKey() ||
            knownTitlesEnforced != currentPolicySnapshot().enforcementEnabled) return CommittedSearchSlice()
        val policy = currentPolicySnapshot()
        if (policy.enforcementEnabled && policy.isLocked && contentPolicyFilter.isSensitiveQuery(query)) return CommittedSearchSlice()
        val matches = knownTitles.values.filter { it.item.matchesTitlePrefix(query) && filter.matches(it.item) }
        val ordered = if (filter.sortBy == SortOption.RELEVANCE) matches.map { it.item }.bestTitleMatches(query)
            else filter.order(matches.map { it.item })
        val tags = matches.associateBy { it.item.id }
        return CommittedSearchSlice(ordered.mapNotNull { tags[it.id] })
    }

    private data class PageKey(val owner: String, val epoch: Long, val enforced: Boolean, val locked: Boolean,
        val query: String, val category: String, val filter: SearchFilter, val ai: Boolean)
    private data class RetainedPage(val state: SearchUiState,
        val plan: Pair<String, com.torve.data.ai.KeywordSearchResult>?,
        val savedAt: TimeMark = TimeSource.Monotonic.markNow())
    private data class DiscoveryPage(val result: PagedResult, val hasMoreByType: Map<String, Boolean>)
    private val retainedPages = linkedMapOf<PageKey, RetainedPage>()
    private var retainedEpoch = 0L
    private var retainedOwner = contentPolicyFilter.profileOwnerKey()
    private var popularPeopleWarmup: Job? = null
    private var popularPeopleKey: PageKey? = null
    private var committedPageKey: PageKey? = null

    private fun pageKey(value: SearchUiState) = PageKey(contentPolicyFilter.profileOwnerKey(), retainedEpoch,
        currentPolicySnapshot().enforcementEnabled, currentPolicySnapshot().isLocked,
        value.query, value.category, value.filter, value.aiSearchEnabled)

    private fun retainPage(value: SearchUiState = _state.value, fresh: Boolean = false) {
        if (value.error != null || value.isSearching || value.isDiscovering || value.isLoadingMore || value.awaitingAiSubmission) return
        val loaded = when (value.category) {
            "people" -> value.peopleLoaded
            "all", "movie", "tv" -> if (value.query.length >= 2) value.hasActiveSearch else value.discoveryLoaded
            else -> false
        }
        if (!loaded) return
        val key = pageKey(value)
        if (!fresh && committedPageKey != key) return
        val savedAt = if (fresh) TimeSource.Monotonic.markNow() else retainedPages[key]?.savedAt ?: TimeSource.Monotonic.markNow()
        retainedPages.remove(key)
        retainedPages[key] = RetainedPage(value, aiPlan, savedAt)
        if (fresh && value == _state.value) committedPageKey = key
        while (retainedPages.size > 8) retainedPages.remove(retainedPages.keys.first())
    }

    private fun invalidateRetainedPages() {
        retainedEpoch++
        retainedPages.clear()
        knownTitles.clear(); knownTitlesOwner = contentPolicyFilter.profileOwnerKey()
        committedPageKey = null
        popularPeopleWarmup?.cancel(); popularPeopleWarmup = null; popularPeopleKey = null
    }

    private fun showRetainedPage(next: SearchUiState): Boolean {
        val saved = retainedPages[pageKey(next)] ?: return false
        aiPlan = saved.plan
        committedPageKey = pageKey(next)
        _state.value = projectStateFor(saved.state.copy(generation = next.generation, showFilterSheet = false), currentPolicySnapshot())
        if (saved.savedAt.elapsedNow().inWholeMilliseconds >= 5 * 60_000) retry()
        return true
    }

    /** All discovery already fetched a complete API page for each title type. */
    private fun retainDiscoveryPages(items: List<MediaItem>, page: DiscoveryPage) {
        rememberTitles(_state.value.committedDiscoverSlice)
        retainPage(fresh = true)
        val current = _state.value
        if (current.category != "all") return
        for (type in listOf("movie", "tv")) {
            val typed = items.filter { (it.type == com.torve.domain.model.MediaType.SERIES) == (type == "tv") }
            retainPage(projectStateFor(current.copy(category = type, filter = current.filter.copy(mediaType = type),
                committedDiscoverSlice = buildSlice(typed, currentPolicySnapshot()),
                discoverHasMore = page.hasMoreByType[type] == true), currentPolicySnapshot()), fresh = true)
        }
    }

    private fun warmPeople() {
        val current = _state.value
        if (current.query.isNotBlank() || current.aiSearchEnabled) return
        val target = SearchUiState(category = "people", filter = current.filter.copy(mediaType = null))
        val key = pageKey(target)
        if (retainedPages[key]?.savedAt?.elapsedNow()?.inWholeMilliseconds?.let { it < 5 * 60_000 } == true ||
            popularPeopleKey == key && popularPeopleWarmup?.isActive == true) return
        popularPeopleKey = key
        popularPeopleWarmup = scope.launch {
            try {
                val people = metadataRepo.getPopularPeople(1)
                if (key != pageKey(target)) return@launch
                retainPage(target.copy(peopleResults = people, peopleLoaded = true,
                    peopleHasMore = people.size >= 20, hasActiveSearch = true), fresh = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* A first visit can retry. */ }
        }
    }

    init {
        observeQuery()
        observePolicy()
        scope.launch {
            contentPolicyFilter.profileOwners().distinctUntilChanged().collect { owner ->
                if (owner != retainedOwner) {
                    retainedOwner = owner; invalidateRetainedPages(); aiPlan = null
                    if (generationOwners[searchGeneration.value] != owner) cancelSubmittedSearch()
                }
            }
        }
        scope.launch {
            contentPolicyFilter.awaitProfileBoundary()
            contentPolicyFilter.discoveryChanges().collect {
                _state.update { projectStateFor(it, currentPolicySnapshot()) }
            }
        }
        if (invalidationCoordinator != null) {
            scope.launch {
                // Debounce: on app start (and after any addon install/remove)
                // the settings-refresh notifier fires in rapid bursts; without
                // debouncing each one would blank the search UI and re-run
                // the network call. 400ms collapses a burst into one reload.
                invalidationCoordinator.events
                    .debounce(400L)
                    .collectLatest { event ->
                        invalidateRetainedPages()
                        println("[SearchVM] policy invalidation event=$event â€” re-running search")
                        val query = _state.value.query
                        if (query.length >= 2 && !_state.value.awaitingAiSubmission) {
                            performSearch(query)
                        } else if (!_state.value.aiSearchEnabled) retry()
                    }
            }
        }
    }

    fun selectCategory(category: String) {
        require(category in setOf("all", "movie", "tv", "people", "lists", "sources"))
        if (_state.value.category == category) return
        retainPage()
        val previous = _state.value
        val generation = cancelSubmittedSearch()
        val next = SearchUiState(query = previous.query, category = category,
            filter = previous.filter.copy(mediaType = category.takeIf { it == "movie" || it == "tv" }),
            aiSearchEnabled = previous.aiSearchEnabled && category in setOf("all", "movie", "tv"), generation = generation)
        if (showRetainedPage(next)) return
        // A mixed text-search page is only a preview of a type-specific API
        // page. Keep its matching posters visible while fetching that page.
        val mixed = retainedPages[pageKey(next.copy(category = "all", filter = next.filter.copy(mediaType = null)))]?.state
        val seed = if (next.query.length >= 2 && category in setOf("movie", "tv") && mixed != null) {
            next.copy(committedSearchSlice = CommittedSearchSlice(ordered = mixed.committedSearchSlice.ordered.filter {
                (it.item.type == com.torve.domain.model.MediaType.SERIES) == (category == "tv")
            }))
        } else next
        _state.value = projectStateFor(seed, currentPolicySnapshot())
        retry()
    }

    fun setAiSearchEnabled(enabled: Boolean) {
        if (_state.value.aiSearchEnabled == enabled) return
        val generation = cancelSubmittedSearch()
        aiPlan = null
        _state.update { SearchUiState(query = it.query, filter = it.filter, category = it.category,
            aiSearchEnabled = enabled, awaitingAiSubmission = enabled, generation = generation) }
        if (!enabled) retry()
    }

    private fun cancelSubmittedSearch(): Long {
        submittedSearchJob?.cancel()
        resultsEnrichJob?.cancel()
        discoverEnrichJob?.cancel()
        paginationJob?.cancel()
        return nextGeneration()
    }

    fun updateQuery(query: String) {
        if (query == _state.value.query) return
        retainPage()
        val generation = cancelSubmittedSearch()
        aiPlan = null
        val wasTextSearch = _state.value.query.length >= 2
        if (_state.value.aiSearchEnabled) {
            _state.update { SearchUiState(query = query, filter = it.filter, category = it.category,
                aiSearchEnabled = true, awaitingAiSubmission = true, generation = generation) }
        } else {
            val next = _state.value.copy(query = query, isSearching = false, isDiscovering = false,
                isLoadingMore = false, error = null, generation = generation)
            if (query.length >= 2 && next.category in setOf("all", "movie", "tv") && showRetainedPage(next)) {
                queryFlow.value = query; return
            }
            _state.update { projectStateFor(next.copy(
                committedSearchSlice = if (query.length >= 2) instantTitles(query, next.filter) else CommittedSearchSlice(),
                peopleResults = emptyList(), searchPage = 1, searchHasMore = false, hasActiveSearch = false,
                renderPhase = if (query.length >= 2) SearchRenderPhase.SEARCHING else next.renderPhase), currentPolicySnapshot()) }
            if (wasTextSearch && query.length < 2) {
                _state.update { projectStateFor(it.copy(committedSearchSlice = CommittedSearchSlice(),
                    peopleResults = emptyList(), searchHasMore = false, hasActiveSearch = false), currentPolicySnapshot()) }
                discoverWithFilters(_state.value.filter)
            }
        }
        queryFlow.value = query
    }

    /** AI requests run only on explicit submission, not on every keystroke. */
    fun submitSearch() {
        if (!_state.value.aiSearchEnabled || _state.value.query.trim().length < 2) return
        cancelSubmittedSearch()
        _state.update { it.copy(awaitingAiSubmission = false) }
        val query = _state.value.query
        submittedSearchJob = scope.launch { performSearch(query) }
    }

    fun retry() {
        if (_state.value.category in setOf("lists", "sources")) return
        if (_state.value.category == "people") { loadPeople(); return }
        val query = _state.value.query
        if (_state.value.aiSearchEnabled) submitSearch()
        else if (query.length >= 2) {
            submittedSearchJob?.cancel()
            submittedSearchJob = scope.launch { performSearch(query) }
        } else discoverWithFilters(_state.value.filter)
    }

    fun refreshLocalizedContent() {
        invalidateRetainedPages()
        if (!_state.value.awaitingAiSubmission) retry()
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        scope.launch {
            queryFlow.collectLatest { query ->
                // Cancelling collectLatest immediately on a keystroke also
                // cancels its title completions, people and metadata requests.
                kotlinx.coroutines.delay(120)
                val current = _state.value
                if (query.length >= 2 && !current.aiSearchEnabled && query == current.query && !current.isSearching &&
                    current.error == null && committedPageKey != pageKey(current)) {
                    if (current.category == "people") loadPeople()
                    else if (current.category !in setOf("lists", "sources")) performSearch(query)
                }
            }
        }
    }

    /**
     * Atomic policy-driven re-projection. When the sensitive-material
     * (or any other) policy toggles, recompute the visible search and
     * discover lists from the committed slices in a single `_state.update`.
     * No `combine` stage and no per-tick `filterItems` over a mixed raw
     * list â€” this is the only hand-off that converts buckets into the
     * grid-driving lists.
     */
    private fun observePolicy() {
        scope.launch {
            policyFlow
                .distinctUntilChanged()
                .collect { policy ->
                    _state.update { current -> projectStateFor(current, policy) }
                }
        }
    }

    private suspend fun performSearch(query: String) {
        if (_state.value.category !in setOf("all", "movie", "tv")) return
        if (query != _state.value.query || _state.value.awaitingAiSubmission) return
        val aiSearch = _state.value.aiSearchEnabled
        // Claim a generation id for this search. Every _state.update below
        // is guarded by it: if a later search has claimed a newer id,
        // nothing here commits.
        val generation = nextGeneration()
        println("[SearchVM] performSearch start gen=$generation query='$query'")

        // Sensitive-query gate. Mirror of CatalogViewModel.observeSearch â€”
        // when policy is locked AND the typed query itself contains an
        // explicit keyword (e.g. "porn"), commit an empty slice and skip
        // the network call. TMDB returns adult==false documentaries with
        // explicit titles/posters for those queries that classify() can't
        // catch (no title/overview keyword + no adult flag), so per-item
        // filtering leaks them. Pre-empting at the query level closes the
        // leak and avoids the visible result-then-empty flicker the user
        // sees while the per-item filter races enrichment.
        val gatePolicy = currentPolicySnapshot()
        if (gatePolicy.enforcementEnabled && gatePolicy.isLocked &&
            contentPolicyFilter.isSensitiveQuery(query)
        ) {
            println("[SearchVM] performSearch gen=$generation gated query='$query' (sensitive query, policy locked)")
            updateIfLatest(generation) { current ->
                projectStateFor(
                    current.copy(
                        committedSearchSlice = CommittedSearchSlice(),
                        peopleResults = emptyList(),
                        userLists = emptyList(),
                        isSearching = false,
                        hasActiveSearch = true,
                        generation = generation,
                    ),
                    gatePolicy,
                )
            }
            return
        }

        if (!updateIfLatest(generation) { it.copy(isSearching = true, isDiscovering = false, isLoadingMore = false,
            searchPage = 1, searchHasMore = false, hasActiveSearch = false,
            error = null, generation = generation, renderPhase = SearchRenderPhase.SEARCHING) }) {
            return
        }
        try {
            val filter = _state.value.filter
            val interpretation = if (aiSearch) {
                aiPlan?.takeIf { it.first == query }?.second ?: run {
                    val resolved = (aiResolver ?: throw SearchAiSetupRequired()).resolve(query)
                    if (generation != searchGeneration.value) return
                    aiPlan = query to resolved
                    resolved
                }
            } else null
            coroutineScope {
                var firstPage: PagedResult? = null
                var suggestions = emptyList<MediaItem>()
                val cachedMatches = if (aiSearch) emptyList() else instantTitles(query, filter).ordered.map { it.item }
                val publishMutex = kotlinx.coroutines.sync.Mutex()
                val verdicts = mutableMapOf<String, ClassifiedMediaItem>()
                val unresolved = mutableSetOf<String>()
                suspend fun publishTitles() = publishMutex.withLock {
                    if (!generationIsCurrent(generation)) return@withLock
                    // A late completion must preserve already appended pages.
                    val current = _state.value
                    val appended = if (current.hasActiveSearch && current.searchPage > 1)
                        current.committedSearchSlice.ordered.map { it.item } else emptyList()
                    val fresh = suggestions + firstPage?.items.orEmpty() + appended
                    val freshIds = fresh.map { it.id }.toSet()
                    // Cache fills missing titles, never missing safety fields
                    // in a newer record for the same title.
                    val candidates = fresh + cachedMatches.filterNot { it.id in freshIds }
                    val sorted = filterAndSort(candidates, filter,
                        aiSearch, if (aiSearch) null else query)
                    // UNKNOWN never becomes visible later in this request.
                    // New catalogue evidence may tighten a cached verdict.
                    sorted.forEach { item ->
                        if (item.id !in unresolved) {
                            val resolved = buildSlice(listOf(item), currentPolicySnapshot())
                            val tagged = resolved.ordered.firstOrNull()
                            if (tagged == null) {
                                verdicts.remove(item.id); unresolved += item.id
                                knownTitles.entries.removeAll { it.value.item.id == item.id }
                            } else if (item.id !in verdicts || tagged.isSensitive) verdicts[item.id] = tagged
                        }
                    }
                    val slice = CommittedSearchSlice(sorted.mapNotNull { verdicts[it.id] }.distinctBy { it.item.id }, unresolved.size)
                    val page = firstPage
                    val committed = updateIfLatest(generation) { state ->
                        val finalSlice = if (state.searchPage > current.searchPage) {
                            val combined = (slice.ordered + state.committedSearchSlice.ordered)
                                .filterNot { it.item.id in unresolved }.distinctBy { it.item.id }
                            val byId = combined.associateBy { it.item.id }
                            val ordered = if (!aiSearch && filter.sortBy == SortOption.RELEVANCE)
                                combined.map { it.item }.bestTitleMatches(query) else filter.order(combined.map { it.item })
                            slice.copy(ordered = ordered.mapNotNull { byId[it.id] })
                        } else slice
                        projectStateFor(state.copy(committedSearchSlice = finalSlice,
                            userLists = emptyList(), isSearching = page == null,
                            searchPage = maxOf(page?.page ?: 1, state.searchPage),
                            searchHasMore = if (state.searchPage > 1) state.searchHasMore else page?.let { it.page < it.totalPages } ?: false,
                            hasActiveSearch = page != null, aiSearchTitle = interpretation?.title,
                            generation = generation), currentPolicySnapshot())
                    }
                    if (committed) {
                        rememberTitles(slice)
                        if (page != null) retainPage(fresh = true)
                    }
                }
                val completions = if (aiSearch) null else launch {
                    try {
                        metadataRepo.titleSuggestions(query, filter.mediaType).collect { values ->
                            suggestions = values
                            publishTitles()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                    } catch (_: Exception) { /* Optional autocomplete never blocks ordinary search. */ }
                }
                if (!aiSearch && _state.value.category == "all") launch {
                    // Let title requests acquire the catalogue slots first.
                    kotlinx.coroutines.delay(300)
                    try {
                        val people = metadataRepo.searchPerson(query, page = 1)
                        updateIfLatest(generation) { it.copy(peopleResults = people) }
                        if (committedPageKey == pageKey(_state.value)) retainPage()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                }
                firstPage = if (interpretation != null) metadataRepo.searchAiPage(interpretation, filter, 1)
                    else metadataRepo.searchMultiPaged(query, 1, filter.mediaType)
                publishTitles()
                completions?.join()
                if (generationIsCurrent(generation)) enrichResults(_state.value.committedSearchSlice.ordered.map { it.item }, generation)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            updateIfLatest(generation) {
                it.copy(
                    isSearching = false,
                    error = if (e is SearchAiSetupRequired || e is SearchRatingUnavailable) e.message
                        else if (aiSearch) "AI search could not be completed. Check your AI provider connection in Settings, then try again."
                        else com.torve.presentation.error.UserFacingError.SEARCH_FAILED.messageKey,
                    generation = generation,
                    renderPhase = SearchRenderPhase.ERROR,
                )
            }
        }
    }

    /**
     * Atomically update `_state` iff the given generation is still the
     * latest claimed by [searchGeneration]. Returns `true` when the update
     * was applied, `false` when the generation was superseded (callers then
     * know their work was wasted and can skip follow-up writes like
     * enrichment).
     */
    private fun updateIfLatest(generation: Long, transform: (SearchUiState) -> SearchUiState): Boolean {
        if (!generationIsCurrent(generation)) return false
        _state.update { current -> if (!generationIsCurrent(generation)) current else transform(current) }
        return generationIsCurrent(generation)
    }

    private fun enrichResults(items: List<MediaItem>, generation: Long) {
        val enricher = ratingsEnricher ?: return
        if (items.isEmpty()) return
        resultsEnrichJob?.cancel()
        resultsEnrichJob = scope.launch {
            val apiKey = resolveMdbListApiKey()
            val enriched = runCatching { enricher.enrichList(items, apiKey, requirePrimaryProviders = true) }.getOrNull() ?: return@launch
            if (generation != searchGeneration.value) return@launch
            val byKey = enriched.associateBy { it.id }
            updateIfLatest(generation) { state ->
                // Merge ONLY the ratings field from enrichment. Anything
                // the classify() function reads (adult, title, overview,
                // tagline, director, genres, genreIds, isContentPlaceholder,
                // isStubDetail) is deliberately preserved from the already-
                // committed item â€” enrichment is physically unable to flip
                // an item from SAFE to SENSITIVE or vice versa.
                val mergedSlice = state.committedSearchSlice.copy(
                    ordered = state.committedSearchSlice.ordered.map { tagged ->
                        val enrichedItem = byKey[tagged.item.id] ?: return@map tagged
                        tagged.copy(item = tagged.item.copy(ratings = enrichedItem.ratings))
                    },
                )
                projectStateFor(
                    state.copy(committedSearchSlice = mergedSlice),
                    currentPolicySnapshot(),
                )
            }
        }
    }

    private fun enrichDiscover(items: List<MediaItem>, generation: Long) {
        val enricher = ratingsEnricher ?: return
        if (items.isEmpty()) return
        discoverEnrichJob?.cancel()
        discoverEnrichJob = scope.launch {
            val apiKey = resolveMdbListApiKey()
            val enriched = runCatching { enricher.enrichList(items, apiKey, requirePrimaryProviders = true) }.getOrNull() ?: return@launch
            if (generation != searchGeneration.value) return@launch
            val byKey = enriched.associateBy { it.id }
            updateIfLatest(generation) { state ->
                val mergedSlice = state.committedDiscoverSlice.copy(
                    ordered = state.committedDiscoverSlice.ordered.map { tagged ->
                        val enrichedItem = byKey[tagged.item.id] ?: return@map tagged
                        tagged.copy(item = tagged.item.copy(ratings = enrichedItem.ratings))
                    },
                )
                projectStateFor(
                    state.copy(committedDiscoverSlice = mergedSlice),
                    currentPolicySnapshot(),
                )
            }
        }
    }

    private suspend fun resolveMdbListApiKey(): String {
        return try {
            integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                ?: MdbListApi.DEFAULT_API_KEY
        } catch (_: Exception) {
            MdbListApi.DEFAULT_API_KEY
        }
    }

    fun applyFilter(filter: SearchFilter) {
        if (filter == _state.value.filter) { dismissFilterSheet(); return }
        retainPage()
        _state.update { it.copy(filter = filter, category = if (it.category in setOf("lists", "sources")) it.category else filter.mediaType ?: "all", showFilterSheet = false) }
        if (_state.value.awaitingAiSubmission) return
        if (_state.value.query.length >= 2) retry()
        else if (!_state.value.aiSearchEnabled) discoverWithFilters(filter)
    }

    /** Populate the dedicated Search tab even before the user types. */
    fun ensureDiscovery() {
        if (_state.value.category == "people") { if (!_state.value.peopleLoaded) loadPeople(); return }
        if (_state.value.category in setOf("lists", "sources")) return
        val current = _state.value
        warmPeople()
        if (!current.aiSearchEnabled && current.query.length < 2 && !current.discoveryLoaded && !current.isDiscovering) {
            discoverWithFilters(current.filter)
        }
    }

    private fun discoverWithFilters(filter: SearchFilter) {
        if (_state.value.category !in setOf("all", "movie", "tv") || _state.value.aiSearchEnabled) return
        val generation = nextGeneration()
        submittedSearchJob?.cancel()
        submittedSearchJob = scope.launch {
            if (!generationIsCurrent(generation) || _state.value.aiSearchEnabled) return@launch
            if (!updateIfLatest(generation) {
                    it.copy(
                        isDiscovering = true,
                        isLoadingMore = false,
                        error = null,
                        discoverPage = 1,
                        discoverHasMore = false,
                        generation = generation,
                    )
                }
            ) return@launch
            try {
                val discovery = fetchDiscoverPage(filter, page = 1)
                val result = discovery.result
                val sortedResults = filterAndSort(result.items, filter)
                val policy = currentPolicySnapshot()
                val slice = buildSlice(sortedResults, policy)
                val committed = updateIfLatest(generation) { current ->
                    projectStateFor(
                        current.copy(
                            committedDiscoverSlice = slice,
                            isDiscovering = false,
                            discoveryLoaded = true,
                            discoverPage = result.page,
                            discoverHasMore = result.page < result.totalPages,
                            hasActiveSearch = false,
                            generation = generation,
                        ),
                        currentPolicySnapshot(),
                    )
                }
                if (committed) {
                    retainDiscoveryPages(sortedResults, discovery)
                    enrichDiscover(slice.ordered.map { it.item }, generation)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateIfLatest(generation) {
                    it.copy(
                        isDiscovering = false,
                        error = if (e is SearchRatingUnavailable) e.message else com.torve.presentation.error.UserFacingError.SEARCH_FAILED.messageKey,
                        generation = generation,
                        renderPhase = SearchRenderPhase.ERROR,
                    )
                }
            }
        }
    }

    /** Load the next real API page for the visible Search/discovery grid. */
    fun loadMore() {
        if (_state.value.category == "people") { if (_state.value.peopleHasMore) loadPeople(more = true); return }
        if (_state.value.category in setOf("lists", "sources")) return
        val snapshot = _state.value
        if (snapshot.awaitingAiSubmission || snapshot.isLoadingMore || snapshot.isSearching || snapshot.isDiscovering ||
            snapshot.query.length >= 2 && !snapshot.hasActiveSearch) return
        val isTextSearch = snapshot.query.length >= 2
        if (isTextSearch && !snapshot.searchHasMore) return
        if (!isTextSearch && !snapshot.discoverHasMore) return

        val generation = searchGeneration.value
        val query = snapshot.query
        val filter = snapshot.filter
        val nextPage = if (isTextSearch) snapshot.searchPage + 1 else snapshot.discoverPage + 1
        _state.update { it.copy(isLoadingMore = true) }
        paginationJob = scope.launch {
            try {
                val discovery = if (!isTextSearch) fetchDiscoverPage(filter, nextPage) else null
                val page = if (isTextSearch && snapshot.aiSearchEnabled) {
                    val plan = aiPlan?.takeIf { it.first == query }?.second ?: return@launch
                    metadataRepo.searchAiPage(plan, filter, nextPage)
                } else if (isTextSearch) {
                    metadataRepo.searchMultiPaged(query, nextPage, filter.mediaType)
                } else {
                    discovery!!.result
                }
                if (generation != searchGeneration.value || query != _state.value.query) return@launch
                val newItems = filterAndSort(page.items, filter, aiSearch = snapshot.aiSearchEnabled, titleQuery = query.takeIf { isTextSearch && !snapshot.aiSearchEnabled })
                val currentSlice = if (isTextSearch) {
                    _state.value.committedSearchSlice
                } else {
                    _state.value.committedDiscoverSlice
                }
                val combined = filterAndSort(
                    currentSlice.ordered.map { it.item } + newItems,
                    filter,
                    aiSearch = snapshot.aiSearchEnabled,
                    titleQuery = query.takeIf { isTextSearch && !snapshot.aiSearchEnabled },
                )
                val slice = buildSlice(combined, currentPolicySnapshot())
                val committed = updateIfLatest(generation) { current ->
                    projectStateFor(
                        if (isTextSearch) {
                            current.copy(
                                committedSearchSlice = slice,
                                searchPage = page.page,
                                searchHasMore = page.page < page.totalPages,
                                isLoadingMore = false,
                            )
                        } else {
                            current.copy(
                                committedDiscoverSlice = slice,
                                discoverPage = page.page,
                                discoverHasMore = page.page < page.totalPages,
                                isLoadingMore = false,
                            )
                        },
                        currentPolicySnapshot(),
                    )
                }
                if (committed) {
                    rememberTitles(slice)
                    if (discovery != null) retainDiscoveryPages(combined, discovery) else retainPage(fresh = true)
                    if (isTextSearch) enrichResults(slice.ordered.map { it.item }, generation)
                    else enrichDiscover(slice.ordered.map { it.item }, generation)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                updateIfLatest(generation) { it.copy(isLoadingMore = false) }
            }
        }
    }

    private fun loadPeople(more: Boolean = false) {
        if (_state.value.isSearching) return
        val alreadyLoaded = _state.value.peopleLoaded
        val generation = if (more) searchGeneration.value else cancelSubmittedSearch()
        val query = _state.value.query.trim()
        val page = if (more) _state.value.peoplePage + 1 else 1
        submittedSearchJob = scope.launch {
            updateIfLatest(generation) { it.copy(isSearching = true, error = null) }
            try {
                val warmKey = pageKey(_state.value)
                if (!more && !alreadyLoaded && query.isEmpty() && popularPeopleKey == warmKey) popularPeopleWarmup?.join()
                val warmed = if (!more && !alreadyLoaded && query.isEmpty()) retainedPages[warmKey]?.state?.peopleResults else null
                val people = warmed ?: if (query.length >= 2) metadataRepo.searchPerson(query, page) else metadataRepo.getPopularPeople(page)
                updateIfLatest(generation) { it.copy(peopleResults = ((if (more) it.peopleResults else emptyList()) + people).distinctBy { person -> person.id },
                    peoplePage = page, peopleHasMore = people.size >= 20, peopleLoaded = true, isSearching = false, hasActiveSearch = true) }
                if (generationIsCurrent(generation)) retainPage(fresh = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                updateIfLatest(generation) { it.copy(isSearching = false, error = "People could not be loaded. Try again.") }
            }
        }
    }

    private suspend fun fetchDiscoverPage(filter: SearchFilter, page: Int): DiscoveryPage {
        suspend fun fetch(type: String): PagedResult = metadataRepo.discover(
            type = type,
            page = page,
            sortBy = filter.sortBy.apiValue,
            withGenres = filter.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            minRating = filter.minRating,
            year = filter.yearFrom,
            yearTo = filter.yearTo,
            runtimeGte = filter.runtimeFilter?.minMinutes,
            runtimeLte = filter.runtimeFilter?.maxMinutes,
            certification = filter.certification,
            certificationCountry = filter.certificationCountry.takeIf { filter.certification != null },
        )
        filter.mediaType?.let { type ->
            val result = fetch(type)
            return DiscoveryPage(result, mapOf(type to (page < result.totalPages)))
        }

        val (movies, shows) = coroutineScope {
            listOf(
                async { fetch("movie") },
                async { fetch("tv") },
            ).awaitAll()
        }
        val mixed = buildList {
            val count = maxOf(movies.items.size, shows.items.size)
            repeat(count) { index ->
                movies.items.getOrNull(index)?.let(::add)
                shows.items.getOrNull(index)?.let(::add)
            }
        }
        return DiscoveryPage(PagedResult(
            items = mixed,
            page = page,
            totalPages = maxOf(movies.totalPages, shows.totalPages),
            totalResults = movies.totalResults + shows.totalResults,
        ), mapOf("movie" to (page < movies.totalPages), "tv" to (page < shows.totalPages)))
    }

    private suspend fun filterAndSort(items: List<MediaItem>, filter: SearchFilter, aiSearch: Boolean = false, titleQuery: String? = null): List<MediaItem> {
        val prepared = prepareRatingItems(items, filter)
        val detailed = if (filter.runtimeFilter != null || filter.certification != null || filter.genreIds.isNotEmpty()) {
            val limit = kotlinx.coroutines.sync.Semaphore(4)
            coroutineScope { prepared.map { item -> async {
                val needsDetail = filter.runtimeFilter != null && item.runtime == null ||
                    filter.certification != null && item.ageCertifications.none { it.country.equals(filter.certificationCountry, true) } ||
                    filter.genreIds.isNotEmpty() && item.genreIds.isEmpty()
                if (!needsDetail) item else limit.withPermit {
                    try {
                        val type = if (item.type == com.torve.domain.model.MediaType.SERIES) "tv" else "movie"
                        val id = item.tmdbId ?: item.id.removePrefix("tmdb:").toIntOrNull()
                        if (id != null) metadataRepo.getDetail(type, id) else item.imdbId?.let { metadataRepo.findByImdbId(it, type) } ?: item
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { item }
                }
            } }.awaitAll() }
        } else prepared
        if (filter.minImdbScore != null && detailed.isNotEmpty() && detailed.none { (it.ratings?.imdbScore ?: 0f) > 0 }) {
            throw SearchRatingUnavailable()
        }
        val filtered = detailed.filter { filter.matches(it, aiSearch) }
        val deduped = if (shouldDedupe()) filtered.dedupeByStableKey() else filtered
        val sorted = if (titleQuery != null && filter.sortBy == SortOption.RELEVANCE) deduped.bestTitleMatches(titleQuery) else filter.order(deduped)
        return applyActiveProfileLimit(sorted)
    }

    private suspend fun prepareRatingItems(items: List<MediaItem>, filter: SearchFilter): List<MediaItem> {
        if (filter.sortBy != SortOption.IMDB_SCORE_DESC && filter.minImdbScore == null) return items
        val enricher = ratingsEnricher ?: return items
        val key = integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
            ?: prefsRepo?.getString(SettingsViewModel.KEY_MDBLIST_API_KEY) ?: MdbListApi.DEFAULT_API_KEY
        return enricher.enrichList(items, key, requirePrimaryProviders = true)
    }

    private suspend fun applyActiveProfileLimit(items: List<MediaItem>): List<MediaItem> {
        val repository = profileRepository ?: return items
        val maxRating = runCatching { repository.getActiveProfile()?.maxContentRating }.getOrNull()
        return contentPolicyFilter.filterProfileLimit(items, maxRating)
    }

    fun toggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
    }

    fun dismissFilterSheet() {
        _state.update { it.copy(showFilterSheet = false) }
    }

    fun clearFilters() { applyFilter(SearchFilter()) }

    fun clearSearch() {
        val generation = cancelSubmittedSearch()
        aiPlan = null
        val current = _state.value
        _state.update { SearchUiState(filter = current.filter, category = current.category, generation = generation,
            aiSearchEnabled = current.aiSearchEnabled, awaitingAiSubmission = current.aiSearchEnabled) }
        queryFlow.value = ""
        if (!current.aiSearchEnabled) discoverWithFilters(current.filter)
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    // â”€â”€ Slice & projection helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Partition resolved items into a [CommittedSearchSlice]. SAFE and
     * SENSITIVE items are kept in original commit order, each tagged with
     * their classification. UNKNOWN items are dropped and only counted â€”
     * they are never stored, so no later enrichment, policy toggle, or
     * combine stage can ever surface them.
     */
    private fun buildSlice(items: List<MediaItem>, policy: ContentPolicyState): CommittedSearchSlice {
        if (!policy.enforcementEnabled) {
            // Enforcement off (Amazon channel etc.) â€” every item is treated
            // as a resolved SAFE so it always renders.
            return CommittedSearchSlice(
                ordered = items.map { ClassifiedMediaItem(it, isSensitive = false) },
                unresolvedHiddenCount = 0,
            )
        }
        val ordered = mutableListOf<ClassifiedMediaItem>()
        var unresolved = 0
        for (item in items) {
            when (contentPolicyFilter.classify(item, ContentSourceType.TMDB, strictUnknown = true)) {
                SensitiveClassification.SAFE -> ordered.add(ClassifiedMediaItem(item, isSensitive = false))
                SensitiveClassification.SENSITIVE -> ordered.add(ClassifiedMediaItem(item, isSensitive = true))
                SensitiveClassification.UNKNOWN -> unresolved += 1
            }
        }
        return CommittedSearchSlice(ordered = ordered, unresolvedHiddenCount = unresolved)
    }

    /**
     * Project the visible list from a committed slice for a given context
     * and policy. Returns SAFE items always; includes SENSITIVE items only
     * when the live policy allows them in [context]. Original commit order
     * is preserved across both buckets so item keys stay stable across
     * policy toggles.
     */
    private fun projectVisible(
        slice: CommittedSearchSlice,
        context: ContentAccessContext,
        policy: ContentPolicyState,
    ): List<MediaItem> {
        val allowSensitive = isSensitiveAllowed(context, policy)
        return slice.ordered.mapNotNull { tagged ->
            if ((!tagged.isSensitive || allowSensitive) && contentPolicyFilter.familyAllows(tagged.item) && contentPolicyFilter.discoveryAllows(tagged.item, context)) tagged.item else null
        }
    }

    /**
     * Total hidden count for the *active* surface (text-search vs discover).
     * Equal to that slice's unresolved count plus any committed SENSITIVE
     * items the live policy is gating away. Computed atomically; never
     * derived by re-running [ContentPolicyFilter.filterItems] over a mixed
     * raw list at UI tick time.
     */
    private fun totalHiddenFor(
        activeSlice: CommittedSearchSlice,
        activeContext: ContentAccessContext,
        policy: ContentPolicyState,
    ): Int {
        val sensitiveHidden = if (isSensitiveAllowed(activeContext, policy)) 0
        else activeSlice.sensitiveItems.size
        return activeSlice.unresolvedHiddenCount + sensitiveHidden
    }

    /**
     * Return a fresh [SearchUiState] with the visible projections,
     * `hiddenResultsCount` and `renderPhase` recomputed for [policy].
     * Pure function over the slices â€” used by both the policy collector
     * and any other path that needs to re-project without committing new
     * data.
     */
    private fun projectStateFor(current: SearchUiState, policy: ContentPolicyState): SearchUiState {
        val searchVisible = projectVisible(
            current.committedSearchSlice,
            ContentAccessContext.DIRECT_SEARCH,
            policy,
        )
        val discoverVisible = projectVisible(
            current.committedDiscoverSlice,
            ContentAccessContext.DEFAULT_DISCOVERY,
            policy,
        )
        val isSearchActive = current.query.length >= 2 || current.committedSearchSlice.ordered.isNotEmpty()
        val activeSlice = if (isSearchActive) current.committedSearchSlice else current.committedDiscoverSlice
        val activeContext = if (isSearchActive) ContentAccessContext.DIRECT_SEARCH else ContentAccessContext.DEFAULT_DISCOVERY
        val totalHidden = totalHiddenFor(activeSlice, activeContext, policy)
        val phase = if (current.awaitingAiSubmission) SearchRenderPhase.IDLE else derivePhase(
            queryLen = current.query.length,
            hasActiveSearch = current.hasActiveSearch,
            isSearching = current.isSearching,
            isDiscovering = current.isDiscovering,
            hasError = current.error != null,
            filterActive = current.filter.isActive,
            hasFilteredResults = if (current.query.length >= 2) {
                searchVisible.isNotEmpty()
            } else discoverVisible.isNotEmpty(),
        )
        return current.copy(
            results = if (current.filter.sortBy == SortOption.IMDB_SCORE_DESC) searchVisible.highestImdbFirst() else searchVisible,
            discoverResults = if (current.filter.sortBy == SortOption.IMDB_SCORE_DESC) discoverVisible.highestImdbFirst() else discoverVisible,
            hiddenResultsCount = totalHidden,
            renderPhase = phase,
        )
    }

    /**
     * Whether SENSITIVE items may be shown in [context] under [policy].
     * Mirrors the SENSITIVE branch of [ContentPolicyFilter.decide] so the
     * visible projection matches what a per-item filterItems pass would
     * produce, but without ever running filterItems over the mixed raw
     * list at UI tick time.
     *
     * Decision is uniform per (policy, context) for SENSITIVE items â€”
     * `decide` only consults item metadata for the SAFE/SENSITIVE/UNKNOWN
     * verdict, which the slice has already cached.
     */
    private fun isSensitiveAllowed(context: ContentAccessContext, policy: ContentPolicyState): Boolean {
        if (!policy.enforcementEnabled && !contentPolicyFilter.hasFamilyBoundary) return true
        if (policy.isLocked) return false
        val promotionalSurface = when (context) {
            ContentAccessContext.DEFAULT_DISCOVERY,
            ContentAccessContext.GLOBAL_RECOMMENDATION,
            ContentAccessContext.SEARCH_SUGGESTION,
            -> true
            else -> false
        }
        if (promotionalSurface) return false
        val allowedForAdult = when (context) {
            ContentAccessContext.DIRECT_SEARCH,
            ContentAccessContext.DETAIL_PAGE,
            ContentAccessContext.HISTORY_DERIVED,
            ContentAccessContext.LIBRARY_OR_WATCHLIST,
            ContentAccessContext.ADDON_SHELF,
            ContentAccessContext.ACCELERATION_OR_INVENTORY,
            -> true
            else -> false
        }
        return policy.adultEnabled && allowedForAdult
    }

    private fun derivePhase(
        queryLen: Int,
        hasActiveSearch: Boolean,
        isSearching: Boolean,
        isDiscovering: Boolean,
        hasError: Boolean,
        filterActive: Boolean,
        hasFilteredResults: Boolean,
    ): SearchRenderPhase {
        return when {
            hasError -> SearchRenderPhase.ERROR
            isSearching -> SearchRenderPhase.SEARCHING
            isDiscovering -> SearchRenderPhase.DISCOVERING
            hasFilteredResults -> SearchRenderPhase.RESULTS
            (queryLen >= 2 || hasActiveSearch || filterActive) -> SearchRenderPhase.EMPTY
            else -> SearchRenderPhase.IDLE
        }
    }

}

private class SearchRatingUnavailable : IllegalStateException("IMDb ratings are unavailable for these results. Connect a rating provider in Settings, or use the TMDB rating filter.")
