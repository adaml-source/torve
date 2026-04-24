package com.torve.presentation.search

import com.torve.domain.model.MediaItem
import com.torve.domain.model.PersonSummary
import com.torve.presentation.catalog.RuntimeFilter
import com.torve.presentation.catalog.SortOption

data class SearchFilter(
    val mediaType: String? = null, // "movie", "tv", or null for both
    val providersAvailabilityOnly: Boolean = false, // placeholder; provider wiring lands later
    val genreIds: List<Int> = emptyList(),
    val minRating: Float? = null,
    val minImdbScore: Float? = null,
    val minTmdbScore: Float? = null,
    val minTorveScore: Float? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val runtimeFilter: RuntimeFilter? = null,
    val sortBy: SortOption = SortOption.POPULARITY_DESC,
) {
    // Keep backward compat for single-genre access
    val genreId: Int? get() = genreIds.firstOrNull()

    val isActive: Boolean
        get() = mediaType != null || providersAvailabilityOnly || genreIds.isNotEmpty() || minRating != null ||
            minImdbScore != null || minTmdbScore != null || minTorveScore != null ||
            yearFrom != null || yearTo != null || runtimeFilter != null ||
            sortBy != SortOption.POPULARITY_DESC

    val activeCount: Int
        get() {
            var count = 0
            if (mediaType != null) count++
            if (providersAvailabilityOnly) count++
            if (genreIds.isNotEmpty()) count++
            if (minRating != null) count++
            if (minImdbScore != null) count++
            if (minTmdbScore != null) count++
            if (minTorveScore != null) count++
            if (yearFrom != null || yearTo != null) count++
            if (runtimeFilter != null) count++
            if (sortBy != SortOption.POPULARITY_DESC) count++
            return count
        }
}

/**
 * Atomic render-phase enumeration. The UI picks one branch to render based
 * on this single value instead of composing decisions from several booleans
 * inline (`isLoading && items.isEmpty()` etc.). This makes the search
 * surface a state machine — at any frame, exactly one of these phases is
 * active, and the rest of [SearchUiState] is consistent with it.
 *
 * The phase is derived in the shared module before [SearchUiState] is
 * exposed to the UI, so the composable cannot combine inconsistent
 * snapshots during typing.
 */
enum class SearchRenderPhase {
    /** Search has never been run and no filters are active — show prompt. */
    IDLE,
    /** A query is in flight. `displayItems` may still show the previous
     *  generation's results while the new one loads, to avoid blanking. */
    SEARCHING,
    /** Final filtered results for the latest completed search. */
    RESULTS,
    /** Search completed but every item was filtered out or the response was
     *  empty. `hiddenResultsCount` may be non-zero if policy hid items. */
    EMPTY,
    /** A discover-with-filters run is in flight (no text query). */
    DISCOVERING,
    /** Most recent search ended with an error. */
    ERROR,
}

/**
 * One item in a committed search/discover slice, carrying the verdict the
 * commit-time classifier produced. Used to project a visible list from
 * SAFE+SENSITIVE buckets without re-running classification or policy
 * filtering over the mixed raw list at every UI tick.
 */
data class ClassifiedMediaItem(
    val item: MediaItem,
    val isSensitive: Boolean,
)

/**
 * Atomic snapshot of one committed search (or discover) result set.
 *
 * The slice carries:
 *  - [ordered]: SAFE+SENSITIVE items in their original commit order, each
 *    tagged with whether the commit-time classifier flagged them as
 *    SENSITIVE. This is the only authoritative source for re-projecting
 *    the visible list when the sensitive-material policy toggles.
 *  - [unresolvedHiddenCount]: number of items whose classification was
 *    UNKNOWN at commit. They were intentionally never stored — committing
 *    only their count means no enrichment step can later reclassify them
 *    into the visible grid.
 *
 * The grid never reads from this slice directly; it reads from the
 * already-projected [SearchUiState.results] / [SearchUiState.discoverResults].
 * The slice exists so the ViewModel can re-project visible items
 * atomically when policy changes, without doing live combine-stage
 * filtering over a mixed raw list.
 */
data class CommittedSearchSlice(
    val ordered: List<ClassifiedMediaItem> = emptyList(),
    val unresolvedHiddenCount: Int = 0,
) {
    val safeItems: List<MediaItem>
        get() = ordered.mapNotNull { if (!it.isSensitive) it.item else null }

    val sensitiveItems: List<MediaItem>
        get() = ordered.mapNotNull { if (it.isSensitive) it.item else null }

    val isEmpty: Boolean get() = ordered.isEmpty() && unresolvedHiddenCount == 0
}

data class SearchUiState(
    val query: String = "",
    /**
     * Committed visible search-result list for the current policy. The UI
     * grid renders only from this. SENSITIVE items are NOT mixed in while
     * the live policy hides them — they live in [committedSearchSlice]
     * and are re-projected here only when [SearchViewModel] performs an
     * atomic recompute (e.g. after a policy toggle).
     */
    val results: List<MediaItem> = emptyList(),
    /**
     * Total number of items hidden from [results] / [discoverResults]:
     * unresolved-at-commit items plus SENSITIVE items the live policy is
     * gating away. Computed atomically alongside [results]; never
     * derived by re-filtering a mixed raw list in a combine stage.
     */
    val hiddenResultsCount: Int = 0,
    val isSearching: Boolean = false,
    val error: String? = null,
    val filter: SearchFilter = SearchFilter(),
    val showFilterSheet: Boolean = false,
    /**
     * Committed visible discover-result list for the current policy.
     * Same projection contract as [results].
     */
    val discoverResults: List<MediaItem> = emptyList(),
    val peopleResults: List<PersonSummary> = emptyList(),
    val userLists: List<String> = emptyList(),
    val isDiscovering: Boolean = false,
    val hasActiveSearch: Boolean = false,
    /**
     * Monotonic id of the search that produced [results] / [discoverResults].
     * Used by the ViewModel to drop late emissions from superseded queries
     * (defense-in-depth beyond `collectLatest` — cancellation may race with
     * an in-progress `_state.update`). Exposed so tests can assert that
     * late writes from old generations are in fact dropped.
     */
    val generation: Long = 0L,
    /**
     * Single-decision phase for the render surface. Computed atomically
     * from the other fields so the UI reads one value instead of composing
     * `isSearching && items.isEmpty()`-style expressions on every frame.
     */
    val renderPhase: SearchRenderPhase = SearchRenderPhase.IDLE,
    /**
     * Authoritative committed slice for the active text-search query.
     * The grid never reads from this; it backs the atomic re-projection
     * the ViewModel performs when the sensitive-material policy toggles.
     */
    val committedSearchSlice: CommittedSearchSlice = CommittedSearchSlice(),
    /**
     * Authoritative committed slice for the active discover-with-filters
     * run. Same role as [committedSearchSlice].
     */
    val committedDiscoverSlice: CommittedSearchSlice = CommittedSearchSlice(),
) {
    /** List the UI should render. Already a committed visible projection. */
    val displayItems: List<MediaItem>
        get() = if (query.length >= 2 || hasActiveSearch) results else discoverResults

    /**
     * SENSITIVE items committed for the active text-search slice that are
     * being hidden by the current policy. Empty when the policy is open
     * enough to render them. Exposed for tests and inspection; the grid
     * does not consult it.
     */
    val committedSensitiveHiddenResults: List<MediaItem>
        get() {
            val visible = results
            return committedSearchSlice.sensitiveItems.filter { it !in visible }
        }

    /**
     * Number of items whose sensitivity could not be confirmed at commit
     * time for the active text-search slice. Permanently excluded from
     * [results] regardless of policy.
     */
    val committedUnresolvedHiddenCount: Int
        get() = committedSearchSlice.unresolvedHiddenCount
}
