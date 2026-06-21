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
import com.torve.domain.model.SensitiveClassification
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.calculateTorveScore
import com.torve.domain.model.defaultTorveWeights
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.catalog.SortOption
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class SearchViewModel(
    private val metadataRepo: MetadataRepository,
    private val prefsRepo: PreferencesRepository,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
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
     * visible items atomically — no live `combine`-stage filterItems run over
     * a mixed raw list ever drives the grid.
     */
    private val _state = MutableStateFlow(SearchUiState())

    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /**
     * Monotonic search-generation counter. Every entry point that kicks off
     * a search (debounced query, invalidation coordinator, applyFilter,
     * clearSearch, discoverWithFilters) increments this. `_state.update`
     * writes are guarded by it so a late result from a superseded generation
     * can never overwrite the latest committed state.
     */
    private val searchGeneration = MutableStateFlow(0L)

    /**
     * Snapshot the policy used by the commit-time classification split and
     * the visible projection. When no repository is wired (tests), assume
     * enforcement is ON and bootstrap-locked — fail-closed so the UI never
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

    // Cancel any in-flight rating enrichment when a new query/filter arrives —
    // results will be replaced anyway, so the old enrichment is wasted work.
    private var resultsEnrichJob: Job? = null
    private var discoverEnrichJob: Job? = null

    init {
        observeQuery()
        observePolicy()
        if (invalidationCoordinator != null) {
            scope.launch {
                // Debounce: on app start (and after any addon install/remove)
                // the settings-refresh notifier fires in rapid bursts; without
                // debouncing each one would blank the search UI and re-run
                // the network call. 400ms collapses a burst into one reload.
                invalidationCoordinator.events
                    .debounce(400L)
                    .collectLatest { event ->
                        println("[SearchVM] policy invalidation event=$event — re-running search")
                        val query = _state.value.query
                        if (query.length >= 2) {
                            performSearch(query)
                        }
                    }
            }
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        scope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .filter { it.length >= 2 }
                .collectLatest { query ->
                    performSearch(query)
                }
        }
    }

    /**
     * Atomic policy-driven re-projection. When the sensitive-material
     * (or any other) policy toggles, recompute the visible search and
     * discover lists from the committed slices in a single `_state.update`.
     * No `combine` stage and no per-tick `filterItems` over a mixed raw
     * list — this is the only hand-off that converts buckets into the
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
        // Claim a generation id for this search. Every _state.update below
        // is guarded by it: if a later search has claimed a newer id,
        // nothing here commits.
        val generation = searchGeneration.updateAndGet { it + 1L }
        println("[SearchVM] performSearch start gen=$generation query='$query'")

        // Sensitive-query gate. Mirror of CatalogViewModel.observeSearch —
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
                        userLists = buildUserListPlaceholders(query),
                        isSearching = false,
                        hasActiveSearch = true,
                        generation = generation,
                    ),
                    gatePolicy,
                )
            }
            return
        }

        if (!updateIfLatest(generation) { it.copy(isSearching = true, error = null, generation = generation) }) {
            return
        }
        try {
            val filter = _state.value.filter
            val results = if (filter.mediaType != null) {
                metadataRepo.searchMultiPaged(query, 1, filter.mediaType).items
            } else {
                metadataRepo.searchMulti(query)
            }
            val people = try {
                metadataRepo.searchPerson(query, page = 1)
            } catch (_: Exception) {
                emptyList()
            }

            val filtered = results.filter { item ->
                val genreMatch = filter.genreIds.isEmpty() || filter.genreIds.any { it in item.genreIds }
                val ratingMatch = filter.minRating == null || (item.rating ?: 0.0) >= filter.minRating
                val imdbMatch = filter.minImdbScore == null || ((item.ratings?.imdbScore ?: 0f) >= filter.minImdbScore)
                val tmdbMatch = filter.minTmdbScore == null || ((item.ratings?.tmdbScore ?: 0f) >= filter.minTmdbScore)
                val torveMatch = filter.minTorveScore == null || (
                    (item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: 0f) >= filter.minTorveScore
                )
                val yearFromMatch = filter.yearFrom == null || (item.year ?: 0) >= filter.yearFrom
                val yearToMatch = filter.yearTo == null || (item.year ?: Int.MAX_VALUE) <= filter.yearTo
                genreMatch && ratingMatch && imdbMatch && tmdbMatch && torveMatch && yearFromMatch && yearToMatch
            }

            val deduped = if (shouldDedupe()) filtered.dedupeByStableKey() else filtered
            val sortedResults = when (filter.sortBy) {
                SortOption.TORVE_SCORE_DESC -> deduped.sortedByDescending { item ->
                    item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: Float.MIN_VALUE
                }
                else -> deduped
            }
            // Commit-time classification split with strict-unknown — UNKNOWN
            // items are dropped permanently (no later enrichment can flip
            // them visible) and SAFE/SENSITIVE land in the slice tagged
            // with their verdict. The visible projection below picks SAFE
            // (always) plus SENSITIVE (only when the live policy allows).
            val policy = currentPolicySnapshot()
            val slice = buildSlice(sortedResults, policy)
            println("[SearchVM] performSearch end gen=$generation query='$query' -> ${sortedResults.size} raw, ${slice.ordered.size} resolved, ${slice.unresolvedHiddenCount} unresolved")
            val committed = updateIfLatest(generation) { current ->
                // Re-read policy inside the update so the projection
                // reflects the most recent policy snapshot, even if it
                // changed between buildSlice and this commit.
                projectStateFor(
                    current.copy(
                        committedSearchSlice = slice,
                        peopleResults = people,
                        userLists = buildUserListPlaceholders(query),
                        isSearching = false,
                        hasActiveSearch = true,
                        generation = generation,
                    ),
                    currentPolicySnapshot(),
                )
            }
            if (committed) enrichResults(slice.ordered.map { it.item }, generation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            updateIfLatest(generation) {
                it.copy(
                    isSearching = false,
                    error = com.torve.presentation.error.UserFacingError.SEARCH_FAILED.messageKey,
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
        if (generation != searchGeneration.value) return false
        _state.update { current ->
            if (generation != searchGeneration.value) current else transform(current)
        }
        return generation == searchGeneration.value
    }

    private fun enrichResults(items: List<MediaItem>, generation: Long) {
        val enricher = ratingsEnricher ?: return
        if (items.isEmpty()) return
        resultsEnrichJob?.cancel()
        resultsEnrichJob = scope.launch {
            val apiKey = resolveMdbListApiKey()
            val enriched = runCatching { enricher.enrichList(items, apiKey) }.getOrNull() ?: return@launch
            if (generation != searchGeneration.value) return@launch
            val byKey = enriched.associateBy { it.id }
            updateIfLatest(generation) { state ->
                // Merge ONLY the ratings field from enrichment. Anything
                // the classify() function reads (adult, title, overview,
                // tagline, director, genres, genreIds, isContentPlaceholder,
                // isStubDetail) is deliberately preserved from the already-
                // committed item — enrichment is physically unable to flip
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
            val enriched = runCatching { enricher.enrichList(items, apiKey) }.getOrNull() ?: return@launch
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
        _state.update { it.copy(filter = filter, showFilterSheet = false) }
        if (_state.value.query.length >= 2) {
            scope.launch { performSearch(_state.value.query) }
        } else if (filter.isActive) {
            discoverWithFilters(filter)
        }
    }

    private fun discoverWithFilters(filter: SearchFilter) {
        scope.launch {
            val generation = searchGeneration.updateAndGet { it + 1L }
            if (!updateIfLatest(generation) {
                    it.copy(isDiscovering = true, error = null, generation = generation)
                }
            ) return@launch
            try {
                val type = filter.mediaType ?: "movie"
                val genresParam = filter.genreIds.takeIf { it.isNotEmpty() }
                    ?.joinToString(",")
                val result = metadataRepo.discover(
                    type = type,
                    sortBy = filter.sortBy.apiValue,
                    withGenres = genresParam,
                    minRating = filter.minRating,
                    year = filter.yearFrom,
                    yearTo = filter.yearTo,
                    runtimeGte = filter.runtimeFilter?.minMinutes,
                    runtimeLte = filter.runtimeFilter?.maxMinutes,
                )
                val preFiltered = result.items.filter { item ->
                    val imdbMatch = filter.minImdbScore == null || ((item.ratings?.imdbScore ?: 0f) >= filter.minImdbScore)
                    val tmdbMatch = filter.minTmdbScore == null || ((item.ratings?.tmdbScore ?: 0f) >= filter.minTmdbScore)
                    val torveMatch = filter.minTorveScore == null || (
                        (item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: 0f) >= filter.minTorveScore
                    )
                    imdbMatch && tmdbMatch && torveMatch
                }
                val deduped = if (shouldDedupe()) preFiltered.dedupeByStableKey() else preFiltered
                val sortedResults = when (filter.sortBy) {
                    SortOption.TORVE_SCORE_DESC -> deduped.sortedByDescending { item ->
                        item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: Float.MIN_VALUE
                    }
                    else -> deduped
                }
                val policy = currentPolicySnapshot()
                val slice = buildSlice(sortedResults, policy)
                val committed = updateIfLatest(generation) { current ->
                    projectStateFor(
                        current.copy(
                            committedDiscoverSlice = slice,
                            isDiscovering = false,
                            hasActiveSearch = true,
                            generation = generation,
                        ),
                        currentPolicySnapshot(),
                    )
                }
                if (committed) enrichDiscover(slice.ordered.map { it.item }, generation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateIfLatest(generation) {
                    it.copy(
                        isDiscovering = false,
                        error = com.torve.presentation.error.UserFacingError.SEARCH_FAILED.messageKey,
                        generation = generation,
                        renderPhase = SearchRenderPhase.ERROR,
                    )
                }
            }
        }
    }

    fun toggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
    }

    fun dismissFilterSheet() {
        _state.update { it.copy(showFilterSheet = false) }
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                filter = SearchFilter(),
                discoverResults = emptyList(),
                committedDiscoverSlice = CommittedSearchSlice(),
            )
        }
        if (_state.value.query.length >= 2) {
            scope.launch { performSearch(_state.value.query) }
        }
    }

    fun clearSearch() {
        // Advance generation so any in-flight search's late writes are
        // dropped before they can repopulate the cleared state.
        val generation = searchGeneration.updateAndGet { it + 1L }
        _state.update { SearchUiState(generation = generation) }
        queryFlow.value = ""
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    // ── Slice & projection helpers ──────────────────────────────────────

    /**
     * Partition resolved items into a [CommittedSearchSlice]. SAFE and
     * SENSITIVE items are kept in original commit order, each tagged with
     * their classification. UNKNOWN items are dropped and only counted —
     * they are never stored, so no later enrichment, policy toggle, or
     * combine stage can ever surface them.
     */
    private fun buildSlice(items: List<MediaItem>, policy: ContentPolicyState): CommittedSearchSlice {
        if (!policy.enforcementEnabled) {
            // Enforcement off (Amazon channel etc.) — every item is treated
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
            if (!tagged.isSensitive || allowSensitive) tagged.item else null
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
     * Pure function over the slices — used by both the policy collector
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
        val phase = derivePhase(
            queryLen = current.query.length,
            hasActiveSearch = current.hasActiveSearch,
            isSearching = current.isSearching,
            isDiscovering = current.isDiscovering,
            hasError = current.error != null,
            filterActive = current.filter.isActive,
            hasFilteredResults = if (current.query.length >= 2 || current.hasActiveSearch) {
                searchVisible.isNotEmpty()
            } else discoverVisible.isNotEmpty(),
        )
        return current.copy(
            results = searchVisible,
            discoverResults = discoverVisible,
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
     * Decision is uniform per (policy, context) for SENSITIVE items —
     * `decide` only consults item metadata for the SAFE/SENSITIVE/UNKNOWN
     * verdict, which the slice has already cached.
     */
    private fun isSensitiveAllowed(context: ContentAccessContext, policy: ContentPolicyState): Boolean {
        if (!policy.enforcementEnabled) return true
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

    private fun buildUserListPlaceholders(query: String): List<String> = listOf(
        "My Watchlist matches for \"$query\" (coming soon)",
        "Trakt lists for \"$query\" (coming soon)",
    )
}
