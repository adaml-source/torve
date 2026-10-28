package com.torve.presentation.seeall

import com.torve.presentation.catalog.CatalogFilter
import com.torve.presentation.catalog.RuntimeFilter
import com.torve.presentation.catalog.SortOption
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.CustomSection
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.isCatalogPlaybackIdentity
import com.torve.domain.model.toCatalogHistoryItemOrNull
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.api.TraktAuthorizedApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.util.hydrateUpcomingScheduleArtwork
import com.torve.util.ioDispatcher
import com.torve.util.mainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class SeeAllSortMode { DEFAULT, A_Z, Z_A, IMDB_DESC, TMDB_DESC, YEAR_DESC, YEAR_ASC }

data class SeeAllUiState(
    val title: String = "",
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val totalResults: Int = 0,
    val sectionId: String = "",
    val sortMode: SeeAllSortMode = SeeAllSortMode.IMDB_DESC,
    val filterMinImdb: Float? = null,
    val filterRuntime: RuntimeFilter? = null,
    val catalogSort: SortOption? = null,
    val runtimeFilteredBySource: Boolean = false,
    val filterYearFrom: Int? = null,
    val filterYearTo: Int? = null,
    val filterGenreIds: Set<Int> = emptySet(),
    val filterStudioIds: Set<Int> = emptySet(),
) {
    /** Items after applying the user's sort + filter selections. */
    val displayedItems: List<MediaItem>
        get() {
            val filtered = applySortAndFilter(items, sortMode, filterYearFrom, filterYearTo, filterGenreIds, filterStudioIds)
                .filter { filterMinImdb == null || (it.ratings?.imdbScore ?: -1f) >= filterMinImdb }
                .filter { item -> filterRuntime?.let { range ->
                    item.runtime?.let { minutes ->
                        (range.minMinutes == null || minutes >= range.minMinutes) &&
                            (range.maxMinutes == null || minutes <= range.maxMinutes)
                    } ?: runtimeFilteredBySource
                } ?: true }
            return when (catalogSort) {
                SortOption.POPULARITY_DESC -> filtered.sortedByDescending { it.popularity ?: -1.0 }
                SortOption.VOTE_COUNT_DESC -> filtered.sortedByDescending { it.voteCount ?: -1 }
                else -> filtered
            }
        }

    val catalogFilter: CatalogFilter get() = CatalogFilter(
        minRating = filterMinImdb, year = filterYearFrom, yearTo = filterYearTo, runtimeFilter = filterRuntime,
        sortBy = catalogSort ?: when (sortMode) {
            SeeAllSortMode.TMDB_DESC -> SortOption.VOTE_AVERAGE_DESC
            SeeAllSortMode.YEAR_DESC -> SortOption.RELEASE_DATE_DESC
            SeeAllSortMode.YEAR_ASC -> SortOption.RELEASE_DATE_ASC
            SeeAllSortMode.DEFAULT -> SortOption.POPULARITY_DESC
            else -> SortOption.IMDB_SCORE_DESC
        },
    )
    val activeFilterCount: Int get() = catalogFilter.activeCount +
        (if (filterGenreIds.isEmpty()) 0 else 1) + (if (filterStudioIds.isEmpty()) 0 else 1)

    /** Distinct genre ids present in the current items, sorted by frequency (most common first). */
    val availableGenres: List<Pair<Int, String>>
        get() = items
            .flatMap { item ->
                val explicit = item.genres.map { g -> g.id to g.name }
                if (explicit.isNotEmpty()) explicit
                else item.genreIds.mapNotNull { id -> tmdbGenreLabel(id)?.let { id to it } }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

    /** TV network / studio chips, hydrated from TMDB detail responses when needed. */
    val availableStudios: List<Pair<Int, String>>
        get() = items
            .filter { it.type == MediaType.SERIES }
            .flatMap { it.studios.map { studio -> studio.id to studio.name } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

    val availableStudioBrands: List<com.torve.domain.model.MediaCompany>
        get() = items
            .filter { it.type == MediaType.SERIES }
            .flatMap { it.studios }
            .filter { it.id > 0 && it.name.isNotBlank() }
            .groupingBy { it.id }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .mapNotNull { (id, _) ->
                items.asSequence()
                    .filter { it.type == MediaType.SERIES }
                    .flatMap { it.studios.asSequence() }
                    .firstOrNull { it.id == id }
            }

    val availableYearRange: IntRange?
        get() {
            val years = items.mapNotNull { it.year }.filter { it in 1900..2100 }
            return if (years.isEmpty()) null else years.min()..years.max()
    }
}

private data class SeeAllFetchPage(
    val title: String,
    val items: List<MediaItem>,
    val hasMore: Boolean,
    val totalResults: Int,
    val nextPage: Int? = null,
    val runtimeFilteredBySource: Boolean = false,
)

internal data class MoreLikeSource(
    val mediaType: String,
    val tmdbId: Int,
)

internal fun parseMoreLikeSectionId(sectionId: String): MoreLikeSource? {
    val parts = sectionId.split('_')
    if (parts.size != 4 || parts[0] != "more" || parts[1] != "like") return null
    val mediaType = parts[2].takeIf { it == "movie" || it == "tv" } ?: return null
    val tmdbId = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return MoreLikeSource(mediaType, tmdbId)
}

fun applySortAndFilter(
    items: List<MediaItem>,
    sortMode: SeeAllSortMode,
    yearFrom: Int?,
    yearTo: Int?,
    genreIds: Set<Int>,
    studioIds: Set<Int> = emptySet(),
): List<MediaItem> {
    val filtered = items.asSequence()
        .filter { item ->
            yearFrom == null || (item.year != null && item.year >= yearFrom)
        }
        .filter { item ->
            yearTo == null || (item.year != null && item.year <= yearTo)
        }
        .filter { item ->
            genreIds.isEmpty() || item.genres.any { it.id in genreIds } ||
                item.genreIds.any { it in genreIds }
        }
        .filter { item ->
            studioIds.isEmpty() || item.studios.any { it.id in studioIds }
        }
        .toList()
    return when (sortMode) {
        SeeAllSortMode.DEFAULT -> filtered
        SeeAllSortMode.A_Z -> filtered.sortedBy { it.title.lowercase() }
        SeeAllSortMode.Z_A -> filtered.sortedByDescending { it.title.lowercase() }
        SeeAllSortMode.IMDB_DESC -> filtered.sortedWith(
            compareByDescending<MediaItem> { it.ratings?.imdbScore != null }
                .thenByDescending { it.ratings?.imdbScore ?: -1f }
                .thenBy { it.title.lowercase() },
        )
        SeeAllSortMode.TMDB_DESC -> filtered.sortedByDescending {
            it.ratings?.tmdbScore?.toDouble() ?: it.rating ?: -1.0
        }
        SeeAllSortMode.YEAR_DESC -> filtered.sortedWith(
            compareByDescending<MediaItem> { it.releaseSortKey().orEmpty() }
                .thenBy { it.title.lowercase() },
        )
        SeeAllSortMode.YEAR_ASC -> filtered.sortedWith(
            compareBy<MediaItem> { it.releaseSortKey() ?: "9999-99-99T99:99:99Z" }
                .thenBy { it.title.lowercase() },
        )
    }
}

private fun MediaItem.releaseSortKey(): String? =
    releaseDate?.trim()?.takeIf { it.isNotEmpty() }
        ?: year?.toString()?.padStart(4, '0')

private fun tmdbGenreLabel(id: Int): String? = when (id) {
    28 -> "Action"
    12 -> "Adventure"
    16 -> "Animation"
    35 -> "Comedy"
    80 -> "Crime"
    99 -> "Documentary"
    18 -> "Drama"
    10751 -> "Family"
    14 -> "Fantasy"
    36 -> "History"
    27 -> "Horror"
    10402 -> "Music"
    9648 -> "Mystery"
    10749 -> "Romance"
    878 -> "Science Fiction"
    10770 -> "TV Movie"
    53 -> "Thriller"
    10752 -> "War"
    37 -> "Western"
    10759 -> "Action & Adventure"
    10762 -> "Kids"
    10763 -> "News"
    10764 -> "Reality"
    10765 -> "Sci-Fi & Fantasy"
    10766 -> "Soap"
    10767 -> "Talk"
    10768 -> "War & Politics"
    else -> null
}

class SeeAllViewModel(
    private val metadataRepo: MetadataRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val watchlistRepo: WatchlistRepository,
    private val prefsRepo: PreferencesRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
    private val traktApi: TraktAuthorizedApi? = null,
    private val searchLists: com.torve.data.trakt.SearchListsRepository? = null,
) {
    companion object {
        private const val TMDB_PAGE_SIZE = 20
        /** Temporary route context: titles and any non-paginated shelf items. */
        val pendingItems: MutableMap<String, Pair<String, List<MediaItem>>> = mutableMapOf()
    }
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = contentPolicyFilter.profileState(SeeAllUiState())
    val state: StateFlow<SeeAllUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var filterBackfillJob: Job? = null
    private var sectionJob: Job? = null
    private var sectionGeneration = 0L
    private val metadataPermits = Semaphore(3)

    init {
        scope.launch {
            contentPolicyFilter.discoveryChanges().collectLatest {
                contentPolicyFilter.awaitProfileBoundary()
                _state.value.sectionId.takeIf { it.isNotBlank() }?.let { loadSection(it, force = true) }
            }
        }
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events.collectLatest {
                    _state.value.sectionId.takeIf { it.isNotBlank() }?.let { loadSection(it, force = true) }
                }
            }
        }
    }

    fun catalogGenres(type: String): List<com.torve.domain.model.Genre> =
        (if (type == "tv") com.torve.data.metadata.TmdbGenres.TV_GENRES else com.torve.data.metadata.TmdbGenres.MOVIE_GENRES)
            .map { (id, name) -> com.torve.domain.model.Genre(id, name) }

    /** One atomic Apply shared with the Movies/Shows filter sheet. */
    fun applyCatalogFilters(filter: CatalogFilter, genreId: Int?) {
        val section = _state.value.sectionId
        val discovery = discoveryType(section) != null
        val sort = when (filter.sortBy) {
            SortOption.VOTE_AVERAGE_DESC -> SeeAllSortMode.TMDB_DESC
            SortOption.RELEASE_DATE_DESC -> SeeAllSortMode.YEAR_DESC
            SortOption.RELEASE_DATE_ASC -> SeeAllSortMode.YEAR_ASC
            SortOption.POPULARITY_DESC, SortOption.VOTE_COUNT_DESC -> SeeAllSortMode.DEFAULT
            else -> SeeAllSortMode.IMDB_DESC
        }
        _state.update { it.copy(filterMinImdb = filter.minRating?.coerceIn(0f, 10f),
            filterYearFrom = filter.year, filterYearTo = filter.yearTo, filterRuntime = filter.runtimeFilter,
            filterGenreIds = setOfNotNull(genreId), sortMode = sort, catalogSort = filter.sortBy,
            runtimeFilteredBySource = false, items = if (discovery) emptyList() else it.items) }
        if (discovery) loadSection(section, force = true)
        else { hydrateTvMetadataAsync(_state.value.items); requestFilterBackfill() }
    }

    private fun discoveryType(section: String): String? = when {
        section in setOf("TRENDING_MOVIES", "POPULAR_MOVIES", "TOP_RATED", "TOP_RATED_MOVIES") || section.startsWith("MOVIE_GENRE_") -> "movie"
        section in setOf("TRENDING_TV", "POPULAR_TV", "TOP_RATED_TV") || section.startsWith("TV_GENRE_") -> "tv"
        else -> null
    }

    fun setSortMode(mode: SeeAllSortMode) {
        _state.update { it.copy(sortMode = mode, catalogSort = null) }
        if (mode != SeeAllSortMode.DEFAULT) requestFilterBackfill()
    }

    fun setMinimumImdb(value: Float?) {
        _state.update { it.copy(filterMinImdb = value?.coerceIn(0f, 10f)) }
        requestFilterBackfill()
    }

    fun setYearRange(from: Int?, to: Int?) {
        _state.update { it.copy(filterYearFrom = from, filterYearTo = to) }
        requestFilterBackfill()
    }

    fun toggleGenre(genreId: Int) {
        _state.update {
            val next = if (genreId in it.filterGenreIds) it.filterGenreIds - genreId
            else it.filterGenreIds + genreId
            it.copy(filterGenreIds = next)
        }
        requestFilterBackfill()
    }

    fun clearGenres() {
        _state.update { it.copy(filterGenreIds = emptySet()) }
    }

    fun toggleStudio(studioId: Int) {
        _state.update {
            val next = if (studioId in it.filterStudioIds) it.filterStudioIds - studioId
            else it.filterStudioIds + studioId
            it.copy(filterStudioIds = next)
        }
        requestFilterBackfill()
    }

    fun clearStudios() {
        _state.update { it.copy(filterStudioIds = emptySet()) }
    }

    fun clearFilters() {
        val previous = _state.value
        _state.update {
            it.copy(
                filterYearFrom = null,
                filterYearTo = null,
                filterGenreIds = emptySet(),
                filterStudioIds = emptySet(),
                filterMinImdb = null,
                filterRuntime = null, catalogSort = null, sortMode = SeeAllSortMode.IMDB_DESC,
                runtimeFilteredBySource = false,
                items = if (discoveryType(it.sectionId) != null) emptyList() else it.items,
            )
        }
        if (discoveryType(previous.sectionId) != null) loadSection(previous.sectionId, force = true)
    }

    fun loadSection(sectionId: String, force: Boolean = false) {
        val current = _state.value
        if (!force && current.sectionId == sectionId && current.items.isNotEmpty()) {
            return
        }
        val generation = ++sectionGeneration
        sectionJob?.cancel()
        filterBackfillJob?.cancel()
        _state.update {
            if (it.sectionId == sectionId) it.copy(isLoading = true)
            else SeeAllUiState(sectionId = sectionId, isLoading = true)
        }
        sectionJob = scope.launch {
            try {
                val page = 1
                val result = fetchSection(sectionId, page)
                val filteredItems = applyContentPolicy(sectionId, result.items)
                if (generation != sectionGeneration) return@launch
                _state.update {
                    it.copy(
                        title = result.title,
                        items = filteredItems,
                        runtimeFilteredBySource = result.runtimeFilteredBySource,
                        isLoading = false,
                        page = result.nextPage ?: 2,
                        hasMore = result.hasMore,
                        totalResults = result.totalResults.takeIf { total -> total > 0 } ?: filteredItems.size,
                    )
                }
                // Fetch rating pills in background so every see-all page shows
                // IMDb / TMDB / RT chips even if the section's fetchSection path
                // didn't call enrichRatings itself.
                enrichPillsAsync(filteredItems)
                hydrateTvMetadataAsync(filteredItems)
                if (filteredItems.size < TMDB_PAGE_SIZE && result.hasMore) requestFilterBackfill(target = 20, maxPages = if (contentPolicyFilter.familyKidsProfile) 2 else 19)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == sectionGeneration) _state.update { it.copy(isLoading = false, hasMore = false) }
            }
        }
    }

    /**
     * Kick off rating-pill enrichment for a page of items. This uses the shared
     * RatingsEnricher (cache â†’ OMDB â†’ MDBList â†’ Trakt) so every item gets pills,
     * independent of which section produced it. No-op if the enricher isn't wired.
     */
    private fun enrichPillsAsync(items: List<MediaItem>) {
        val enricher = ratingsEnricher ?: return
        if (items.isEmpty()) return
        val generation = sectionGeneration
        scope.launch {
            val apiKey = try {
                integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            // The backend accepts twelve titles per request. Process the complete
            // page in bounded batches and publish each batch as it arrives.
            for (batch in items.filter { it.catalogueNotice == null }.chunked(12)) {
                val enriched = enricher.enrichList(batch, apiKey)
                if (generation != sectionGeneration) return@launch
                val byId = enriched.associateBy { "${it.type}:${it.id}" }
                _state.update { s ->
                    s.copy(items = s.items.map { current ->
                        current.copy(ratings = byId["${current.type}:${current.id}"]?.ratings ?: current.ratings)
                    })
                }
            }
        }
    }

    /**
     * TV list/discover endpoints usually carry only genre ids and no
     * network/studio data. Detail hydration fills those fields in the
     * background so See All can offer real TV network + genre filters without
     * blocking first paint.
     */
    private fun hydrateTvMetadataAsync(items: List<MediaItem>) {
        val targets = items
            .filter { it.tmdbId != null && ((it.type == MediaType.SERIES && (it.studios.isEmpty() || it.genres.isEmpty())) ||
                (_state.value.filterRuntime != null && !_state.value.runtimeFilteredBySource && it.runtime == null) ||
                (_state.value.filterGenreIds.isNotEmpty() && it.genres.isEmpty() && it.genreIds.isEmpty()) ||
                ((_state.value.filterYearFrom != null || _state.value.filterYearTo != null) && it.year == null)) }
            .distinctBy { it.tmdbId }
        if (targets.isEmpty()) return
        val generation = sectionGeneration
        scope.launch {
            val hydrated = coroutineScope {
                targets.map { item ->
                    async(ioDispatcher) {
                        metadataPermits.withPermit {
                            if (generation != sectionGeneration) return@withPermit null
                            val detail = try { metadataRepo.getDetail(if (item.type == MediaType.SERIES) "tv" else "movie", item.tmdbId!!) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { return@withPermit null }
                            "${item.type}:${item.id}" to detail
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            if (hydrated.isEmpty() || generation != sectionGeneration) return@launch
            _state.update { state ->
                state.copy(items = state.items.map { current ->
                    val detail = hydrated["${current.type}:${current.id}"] ?: return@map current
                    current.copy(
                        imdbId = current.imdbId ?: detail.imdbId,
                        genres = current.genres.ifEmpty { detail.genres },
                        genreIds = current.genreIds.ifEmpty { detail.genreIds },
                        year = current.year ?: detail.year,
                        runtime = current.runtime ?: detail.runtime,
                        studios = current.studios.ifEmpty { detail.studios },
                        logoUrl = current.logoUrl ?: detail.logoUrl,
                        backdropUrl = current.backdropUrl ?: detail.backdropUrl,
                        posterUrl = current.posterUrl ?: detail.posterUrl,
                        ratings = current.ratings ?: detail.ratings,
                    )
                })
            }
        }
    }

    fun loadMore() {
        scope.launch {
            appendNextPage()
        }
    }

    fun close() { scope.coroutineContext[Job]?.cancel() }

    /**
     * A filter must explore the backing catalogue, not only hide cards from
     * the first page already in memory. Load a bounded number of subsequent
     * API pages until the filtered grid is useful or the source is exhausted.
     */
    private fun requestFilterBackfill(target: Int = 30, maxPages: Int = 4) {
        filterBackfillJob?.cancel()
        filterBackfillJob = scope.launch {
            delay(150L)
            var fetchedPages = 0
            while (
                fetchedPages < maxPages &&
                _state.value.hasMore &&
                _state.value.displayedItems.size < target
            ) {
                if (!appendNextPage()) break
                fetchedPages += 1
            }
        }
    }

    private suspend fun appendNextPage(): Boolean {
        val snapshot = _state.value
        val generation = sectionGeneration
        if (!snapshot.hasMore || snapshot.isLoading || snapshot.sectionId.isBlank()) return false
        _state.update { it.copy(isLoading = true) }
        return try {
            val result = fetchSection(snapshot.sectionId, snapshot.page)
            val filteredItems = applyContentPolicy(snapshot.sectionId, result.items)
            if (generation != sectionGeneration) return false
            val existingKeys = _state.value.items.map { "${it.type}:${it.tmdbId ?: it.id}" }.toSet()
            val newItems = filteredItems.distinctBy { "${it.type}:${it.tmdbId ?: it.id}" }
                .filter { "${it.type}:${it.tmdbId ?: it.id}" !in existingKeys }
            _state.update {
                it.copy(
                    items = it.items + newItems,
                    runtimeFilteredBySource = result.runtimeFilteredBySource,
                    isLoading = false,
                    page = result.nextPage ?: (it.page + 1),
                    hasMore = result.hasMore,
                    totalResults = result.totalResults.takeIf { total -> total > 0 } ?: it.totalResults,
                )
            }
            enrichPillsAsync(newItems)
            hydrateTvMetadataAsync(newItems)
            result.hasMore || newItems.isNotEmpty()
        } catch (e: CancellationException) {
            if (generation == sectionGeneration) _state.update { it.copy(isLoading = false) }
            throw e
        } catch (_: Exception) {
            if (generation == sectionGeneration) _state.update { it.copy(isLoading = false) }
            false
        }
    }

    private suspend fun fetchSection(sectionId: String, page: Int): SeeAllFetchPage {
        val selection = _state.value
        val type = discoveryType(sectionId)
        if (type != null && selection.catalogSort != null &&
            (selection.catalogFilter.isActive || selection.filterGenreIds.isNotEmpty())) {
            val filter = selection.catalogFilter
            val baseGenre = sectionId.takeIf { it.startsWith("MOVIE_GENRE_") || it.startsWith("TV_GENRE_") }
                ?.substringAfterLast('_')?.toIntOrNull()
            val result = metadataRepo.discover(type = type, page = page, sortBy = filter.sortBy.apiValue,
                withGenres = selection.filterGenreIds.takeIf { it.isNotEmpty() }?.joinToString("|") ?: baseGenre?.toString(),
                year = filter.year, yearTo = filter.yearTo, runtimeGte = filter.runtimeFilter?.minMinutes,
                runtimeLte = filter.runtimeFilter?.maxMinutes)
            return SeeAllFetchPage(selection.title, result.items, result.page < result.totalPages, result.totalResults,
                result.page + 1, runtimeFilteredBySource = filter.runtimeFilter != null)
        }
        parseMoreLikeSectionId(sectionId)?.let { source ->
            val (recommendations, similar) = coroutineScope {
                val recommendationsDeferred = async {
                    runCatching {
                        metadataRepo.getRecommendations(source.mediaType, source.tmdbId, page)
                    }.getOrDefault(emptyList())
                }
                val similarDeferred = async {
                    runCatching {
                        metadataRepo.getSimilar(source.mediaType, source.tmdbId, page)
                    }.getOrDefault(emptyList())
                }
                recommendationsDeferred.await() to similarDeferred.await()
            }
            val items = (recommendations + similar)
                .asSequence()
                .filterNot { candidate -> candidate.tmdbId == source.tmdbId }
                .distinctBy { candidate -> "${candidate.type}:${candidate.tmdbId ?: candidate.id}" }
                .toList()
            return SeeAllFetchPage(
                title = pendingItems[sectionId]?.first ?: "Because You Watched",
                items = items,
                hasMore = recommendations.size >= TMDB_PAGE_SIZE || similar.size >= TMDB_PAGE_SIZE,
                totalResults = 0,
            )
        }

        if (sectionId.startsWith("shelf:")) {
            val shelfId = sectionId.removePrefix("shelf:")
            // Peek rather than remove â€” on back-nav and re-open, the shelf
            // would otherwise be gone because the previous visit consumed it,
            // producing a blank "See all" page the second time around.
            val (title, items) = pendingItems[shelfId]
                ?: return SeeAllFetchPage("", emptyList(), false, 0)
            return SeeAllFetchPage(title, items, false, items.size)
        }

        if (sectionId.startsWith("MOVIE_GENRE_") || sectionId.startsWith("TV_GENRE_")) {
            val isMovie = sectionId.startsWith("MOVIE_GENRE_")
            val prefix = if (isMovie) "MOVIE_GENRE_" else "TV_GENRE_"
            val genreId = sectionId.removePrefix(prefix).toIntOrNull()
            val type = if (isMovie) "movie" else "tv"
            val titleForGenre = genreIdToLabel(genreId) ?: sectionId.replace("_", " ")
            if (genreId != null) {
                val result = metadataRepo.discover(
                    type = type,
                    withGenres = genreId.toString(),
                    page = page,
                    sortBy = "popularity.desc",
                )
                val label = if (isMovie) "$titleForGenre Movies" else "$titleForGenre TV Shows"
                return SeeAllFetchPage(label, result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
        }

        if (sectionId.startsWith("custom:")) {
            val customId = sectionId.removePrefix("custom:")
            val section = loadCustomSections().firstOrNull { it.id == customId }
                ?: return SeeAllFetchPage("Custom Section", emptyList(), false, 0)
            val (items, hasMore) = fetchCustomSection(section, page)
            return SeeAllFetchPage(section.title, items, hasMore, items.size)
        }

        return when (sectionId) {
            "upcoming_schedule" -> {
                pendingItems[sectionId]?.let { (title, items) ->
                    return SeeAllFetchPage(title, items, false, items.size)
                }
                val items = loadUpcomingScheduleItems()
                SeeAllFetchPage("Upcoming Schedule", items, false, items.size)
            }
            "TRENDING_MOVIES" -> {
                val result = metadataRepo.getTrendingPaged("movie", page)
                SeeAllFetchPage("Trending Movies", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "TRENDING_TV" -> {
                val result = metadataRepo.getTrendingPaged("tv", page)
                SeeAllFetchPage("Trending TV Shows", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "POPULAR_MOVIES" -> {
                val result = metadataRepo.getPopularPaged("movie", page)
                SeeAllFetchPage("Popular Movies", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "POPULAR_TV" -> {
                val result = metadataRepo.getPopularPaged("tv", page)
                SeeAllFetchPage("Popular TV Shows", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "NOW_PLAYING" -> {
                val result = metadataRepo.discover(type = "movie", page = page)
                SeeAllFetchPage("Now Playing", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "TOP_RATED", "TOP_RATED_MOVIES" -> {
                val result = metadataRepo.getTopRatedPaged("movie", page)
                SeeAllFetchPage("Top Rated Movies", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "TOP_RATED_TV" -> {
                val result = metadataRepo.getTopRatedPaged("tv", page)
                SeeAllFetchPage("Top Rated TV Shows", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "NEW_RELEASES", "UPCOMING" -> {
                val result = metadataRepo.getUpcomingPaged(page)
                SeeAllFetchPage("Upcoming", result.items, result.page < result.totalPages, result.totalResults, result.page + 1)
            }
            "continue_watching" -> {
                // Use the same source as the home rail (in-progress, not history),
                // group series episodes under the show, navigate to show detail.
                val raw = watchProgressRepo.getInProgress(50)
                    .filter { it.mediaId.isCatalogPlaybackIdentity() }.map { wp ->
                    val isSeries = wp.mediaType == MediaType.SERIES
                    MediaItem(
                        id = wp.mediaId,
                        tmdbId = wp.mediaId.extractTmdbIdOrNull(),
                        imdbId = wp.mediaId.extractImdbIdOrNull(),
                        type = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
                        title = if (isSeries) (wp.showTitle ?: wp.title) else wp.title,
                        posterUrl = wp.posterUrl,
                        backdropUrl = wp.backdropUrl,
                    )
                }
                val items = enrichRatings(resolveImdbToTmdb(dedupePerShow(raw)))
                SeeAllFetchPage("Continue Watching", items, false, items.size)
            }
            "watchlist" -> {
                val items = watchlistRepo.getAll().map { wl ->
                    MediaItem(
                        id = wl.mediaId,
                        tmdbId = wl.tmdbId,
                        title = wl.title,
                        posterUrl = wl.posterUrl,
                        backdropUrl = wl.backdropUrl,
                        rating = wl.rating,
                        year = wl.year,
                        type = wl.mediaType,
                    )
                }
                SeeAllFetchPage("My Watchlist", items, false, items.size)
            }
            "recommended" -> {
                // Recommendations don't paginate from TMDB directly; return what we have
                SeeAllFetchPage("Recommended For You", emptyList(), false, 0)
            }
            "recently_watched" -> {
                // Apply the card limit after excluding files/events and grouping
                // episodes, so they cannot crowd legitimate watched titles out.
                val raw = watchHistoryRepo.getAll().mapNotNull { it.toCatalogHistoryItemOrNull() }
                val items = enrichRatings(resolveImdbToTmdb(dedupePerShow(raw).take(80)))
                SeeAllFetchPage("Recently Watched", items, false, items.size)
            }
            else -> SeeAllFetchPage(sectionId.replace("_", " "), emptyList(), false, 0)
        }
    }

    private suspend fun loadUpcomingScheduleItems(): List<MediaItem> {
        val api = traktApi ?: return emptyList()
        val connected = try {
            api.hasConnection()
        } catch (_: Exception) {
            false
        }
        if (!connected) return emptyList()
        val episodes = try {
            api.getCalendar(days = 33)
        } catch (_: Exception) {
            emptyList()
        }
        if (episodes.isEmpty()) return emptyList()
        val distinctEpisodes = episodes
            .sortedBy { it.firstAired }
            .distinctBy { episode ->
                "${episode.showTmdbId ?: episode.showTitle}:${episode.season}:${episode.episode}:${episode.firstAired}"
            }
        return hydrateUpcomingScheduleArtwork(
            entries = distinctEpisodes.map { episode -> episode to episode.toUpcomingScheduleItem() },
            metadataRepo = metadataRepo,
            detailLookupLimit = 40,
        )
    }

    private fun TraktCalendarEpisode.toUpcomingScheduleItem(): MediaItem {
        val airDateTime = firstAired.takeIf { it.isNotBlank() }
        val episodeCode = "S${season}E${episode}"
        val displayTitle = buildString {
            append(showTitle)
            append(" - ")
            append(episodeCode)
            if (episodeTitle.isNotBlank()) {
                append(" - ")
                append(episodeTitle)
            }
        }
        val fallbackId = buildString {
            append("trakt-calendar:")
            append(showTmdbId ?: showTitle)
            append(':')
            append(season)
            append(':')
            append(episode)
            append(':')
            append(firstAired)
        }
        return MediaItem(
            id = fallbackId,
            tmdbId = showTmdbId,
            type = MediaType.SERIES,
            title = displayTitle,
            year = null,
            releaseDate = airDateTime,
        )
    }

    private fun genreIdToLabel(id: Int?): String? = when (id) {
        28 -> "Action"
        12 -> "Adventure"
        16 -> "Animation"
        35 -> "Comedy"
        80 -> "Crime"
        99 -> "Documentary"
        18 -> "Drama"
        10751 -> "Family"
        14 -> "Fantasy"
        36 -> "History"
        27 -> "Horror"
        10402 -> "Music"
        9648 -> "Mystery"
        10749 -> "Romance"
        878 -> "Science Fiction"
        10770 -> "TV Movie"
        53 -> "Thriller"
        10752 -> "War"
        37 -> "Western"
        10759 -> "Action & Adventure"
        10762 -> "Kids"
        10763 -> "News"
        10764 -> "Reality"
        10765 -> "Sci-Fi & Fantasy"
        10766 -> "Soap"
        10767 -> "Talk"
        10768 -> "War & Politics"
        else -> null
    }

    private suspend fun loadCustomSections(): List<CustomSection> {
        val saved = try { prefsRepo.getString("custom_sections") } catch (_: Exception) { null }
        return if (saved != null) {
            try {
                json.decodeFromString<List<CustomSection>>(saved)
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
    }

    private suspend fun fetchCustomSection(section: CustomSection, page: Int): Pair<List<MediaItem>, Boolean> {
        val f = section.filters
        return if (f.traktListId != null) {
            val result = searchLists?.items(f.traktListId, page, f.traktListOwned)
            result?.items.orEmpty() to (result?.hasMore == true)
        } else if (f.specificTmdbIds.isNotEmpty()) {
            val items = f.specificTmdbIds.mapNotNull { spec ->
                try {
                    metadataRepo.getDetail(spec.mediaType, spec.tmdbId)
                } catch (_: Exception) { null }
            }
            items to false
        } else {
            val castIds = f.withCast.takeIf { it.isNotEmpty() }?.joinToString(",") { it.id.toString() }
            val crewIds = f.withCrew.takeIf { it.isNotEmpty() }?.joinToString(",") { it.id.toString() }
            val genres = f.genreIds.takeIf { it.isNotEmpty() }?.joinToString(",")
            val providers = f.withWatchProviders.takeIf { it.isNotEmpty() }?.joinToString(",")
            val keywords = f.withKeywords.takeIf { it.isNotEmpty() }?.joinToString("|")
            val types = if (section.mediaType == "both") listOf("movie", "tv") else listOf(section.mediaType)
            val results = types.mapNotNull { type ->
                try {
                    metadataRepo.discover(
                        type = type,
                        sortBy = f.sortBy,
                        withGenres = genres,
                        minRating = f.minRating,
                        year = f.yearFrom,
                        yearTo = f.yearTo,
                        runtimeGte = f.runtimeGte,
                        runtimeLte = f.runtimeLte,
                        originCountries = f.originCountries.takeIf { it.isNotEmpty() }?.joinToString("|"),
                        originalLanguage = f.originalLanguage,
                        certification = f.certification,
                        certificationGte = f.certificationGte,
                        certificationLte = f.certificationLte,
                        certificationCountry = f.certificationCountry,
                        withCast = castIds,
                        withCrew = crewIds,
                        withWatchProviders = providers,
                        watchRegion = f.watchRegion,
                        withKeywords = keywords,
                        page = page,
                    )
                } catch (_: Exception) {
                    null
                }
            }
            val items = results.flatMap { it.items }.distinctBy { it.id }
            val hasMore = results.any { it.page < it.totalPages }
            items to hasMore
        }
    }

    /**
     * For series, multiple rows (one per episode) collapse to a single card
     * keyed by the show's stable id (TMDB if known, otherwise IMDB, otherwise
     * the raw id). This guarantees "The Boys" shows as a single card keyed by
     * the show id â€” never as one card per watched episode.
     */
    private fun dedupePerShow(items: List<MediaItem>): List<MediaItem> {
        val seenSeriesIds = mutableSetOf<String>()
        val out = mutableListOf<MediaItem>()
        for (item in items) {
            if (item.type == MediaType.SERIES) {
                val key = item.tmdbId?.toString()
                    ?: item.imdbId
                    ?: item.id
                if (key in seenSeriesIds) continue
                seenSeriesIds += key
                // Force the card id to the show id so card-click resolves the show, not an episode.
                out += item.copy(id = key)
            } else {
                out += item
            }
        }
        return out
    }

    /**
     * For items that only have an IMDB id (e.g. addon playback history stored
     * before TMDB resolution), resolve IMDBâ†’TMDB via the TMDB /find endpoint so
     * navigation and rating enrichment both work. Failed lookups leave the
     * original item untouched.
     */
    private suspend fun resolveImdbToTmdb(items: List<MediaItem>): List<MediaItem> {
        val needsResolve = items.filter { it.tmdbId == null && !it.imdbId.isNullOrBlank() }
        if (needsResolve.isEmpty()) return items
        val resolved = coroutineScope {
            needsResolve.map { item ->
                async {
                    val imdb = item.imdbId ?: return@async item
                    runCatching { metadataRepo.findByImdbId(imdb) }.getOrNull()?.let { found ->
                        item.copy(
                            tmdbId = found.tmdbId ?: item.tmdbId,
                            posterUrl = item.posterUrl ?: found.posterUrl,
                            backdropUrl = item.backdropUrl ?: found.backdropUrl,
                            year = item.year ?: found.year,
                            rating = item.rating ?: found.rating,
                        )
                    } ?: item
                }
            }.awaitAll()
        }
        val byId = resolved.associateBy { it.id }
        return items.map { byId[it.id] ?: it }
    }

    /**
     * Locally-derived MediaItems (continue watching, recently watched) lack
     * TMDB rating/year metadata. Hydrate in parallel from TMDB so rating
     * pills render. Failures are non-fatal â€” items keep their original fields.
     */
    private suspend fun enrichRatings(items: List<MediaItem>): List<MediaItem> {
        val needsEnrichment = items.filter { it.rating == null && it.tmdbId != null }
        if (needsEnrichment.isEmpty()) return items
        val enriched = coroutineScope {
            needsEnrichment.map { item ->
                async {
                    runCatching {
                        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                        metadataRepo.getDetail(type, item.tmdbId!!)
                    }.getOrNull()?.let { detail ->
                        item.copy(
                            rating = detail.rating ?: item.rating,
                            ratings = detail.ratings ?: item.ratings,
                            year = detail.year ?: item.year,
                            posterUrl = item.posterUrl ?: detail.posterUrl,
                            backdropUrl = item.backdropUrl ?: detail.backdropUrl,
                        )
                    } ?: item
                }
            }.awaitAll()
        }
        val byId = enriched.associateBy { it.id }
        return items.map { byId[it.id] ?: it }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    private fun applyContentPolicy(sectionId: String, items: List<MediaItem>): List<MediaItem> {
        val policy = currentPolicy()
        val context = when {
            sectionId == "watchlist" -> ContentAccessContext.LIBRARY_OR_WATCHLIST
            sectionId == "continue_watching" || sectionId == "recently_watched" || sectionId.startsWith("because_") ->
                ContentAccessContext.HISTORY_DERIVED
            else -> ContentAccessContext.DEFAULT_DISCOVERY
        }
        val sourceType = when {
            sectionId == "watchlist" || sectionId == "continue_watching" || sectionId == "recently_watched" ->
                ContentSourceType.LOCAL_LIBRARY
            else -> ContentSourceType.TMDB
        }
        return contentPolicyFilter.filterItems(
            policy = policy,
            context = context,
            items = items,
            sourceType = sourceType,
        ).items
    }
}
