package com.torve.presentation.seeall

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.CustomSection
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
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
import com.torve.util.ioDispatcher
import com.torve.util.mainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
    val sortMode: SeeAllSortMode = SeeAllSortMode.DEFAULT,
    val filterYearFrom: Int? = null,
    val filterYearTo: Int? = null,
    val filterGenreIds: Set<Int> = emptySet(),
    val filterStudioIds: Set<Int> = emptySet(),
) {
    /** Items after applying the user's sort + filter selections. */
    val displayedItems: List<MediaItem>
        get() = applySortAndFilter(items, sortMode, filterYearFrom, filterYearTo, filterGenreIds, filterStudioIds)

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
)

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
) {
    companion object {
        /** Temporary holder for shelf items that can't be paginated from an API. */
        val pendingItems: MutableMap<String, Pair<String, List<MediaItem>>> = mutableMapOf()
    }
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow(SeeAllUiState())
    val state: StateFlow<SeeAllUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events.collectLatest {
                    _state.value.sectionId.takeIf { it.isNotBlank() }?.let { loadSection(it, force = true) }
                }
            }
        }
    }

    fun setSortMode(mode: SeeAllSortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    fun setYearRange(from: Int?, to: Int?) {
        _state.update { it.copy(filterYearFrom = from, filterYearTo = to) }
    }

    fun toggleGenre(genreId: Int) {
        _state.update {
            val next = if (genreId in it.filterGenreIds) it.filterGenreIds - genreId
            else it.filterGenreIds + genreId
            it.copy(filterGenreIds = next)
        }
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
    }

    fun clearStudios() {
        _state.update { it.copy(filterStudioIds = emptySet()) }
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                filterYearFrom = null,
                filterYearTo = null,
                filterGenreIds = emptySet(),
                filterStudioIds = emptySet(),
            )
        }
    }

    fun loadSection(sectionId: String, force: Boolean = false) {
        val current = _state.value
        if (!force && current.sectionId == sectionId && current.items.isNotEmpty()) {
            return
        }
        _state.update { it.copy(sectionId = sectionId, isLoading = true) }
        scope.launch {
            try {
                val page = 1
                val result = fetchSection(sectionId, page)
                val filteredItems = applyContentPolicy(sectionId, result.items)
                _state.update {
                    it.copy(
                        title = result.title,
                        items = filteredItems,
                        isLoading = false,
                        page = 2,
                        hasMore = result.hasMore,
                        totalResults = result.totalResults.takeIf { total -> total > 0 } ?: filteredItems.size,
                    )
                }
                // Fetch rating pills in background so every see-all page shows
                // IMDb / TMDB / RT chips even if the section's fetchSection path
                // didn't call enrichRatings itself.
                enrichPillsAsync(filteredItems)
                hydrateTvMetadataAsync(filteredItems)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, hasMore = false) }
            }
        }
    }

    /**
     * Kick off rating-pill enrichment for a page of items. This uses the shared
     * RatingsEnricher (cache → OMDB → MDBList → Trakt) so every item gets pills,
     * independent of which section produced it. No-op if the enricher isn't wired.
     */
    private fun enrichPillsAsync(items: List<MediaItem>) {
        val enricher = ratingsEnricher ?: return
        if (items.isEmpty()) return
        scope.launch {
            val apiKey = try {
                integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            val enriched = enricher.enrichList(items, apiKey)
            // Merge enriched ratings back by id. Preserve user's chosen sort.
            val byId = enriched.associateBy { it.id }
            _state.update { s ->
                s.copy(items = s.items.map { byId[it.id] ?: it })
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
            .filter { it.type == MediaType.SERIES && it.tmdbId != null && (it.studios.isEmpty() || it.genres.isEmpty()) }
            .distinctBy { it.tmdbId }
        if (targets.isEmpty()) return
        scope.launch {
            val hydrated = coroutineScope {
                targets.map { item ->
                    async(ioDispatcher) {
                        val detail = runCatching { metadataRepo.getDetail("tv", item.tmdbId!!) }.getOrNull()
                            ?: return@async null
                        item.id to item.copy(
                            imdbId = item.imdbId ?: detail.imdbId,
                            genres = item.genres.ifEmpty { detail.genres },
                            studios = item.studios.ifEmpty { detail.studios },
                            logoUrl = item.logoUrl ?: detail.logoUrl,
                            backdropUrl = item.backdropUrl ?: detail.backdropUrl,
                            posterUrl = item.posterUrl ?: detail.posterUrl,
                            ratings = item.ratings ?: detail.ratings,
                        )
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            if (hydrated.isEmpty()) return@launch
            _state.update { state ->
                state.copy(items = state.items.map { hydrated[it.id] ?: it })
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.isLoading) return
        _state.update { it.copy(isLoading = true) }
        scope.launch {
            try {
                val result = fetchSection(s.sectionId, s.page)
                val filteredItems = applyContentPolicy(s.sectionId, result.items)
                val existingIds = _state.value.items.map { it.id }.toSet()
                val newItems = filteredItems.filter { it.id !in existingIds }
                _state.update {
                    it.copy(
                        items = it.items + newItems,
                        isLoading = false,
                        page = it.page + 1,
                        hasMore = result.hasMore,
                        totalResults = result.totalResults.takeIf { total -> total > 0 } ?: it.totalResults,
                    )
                }
                enrichPillsAsync(newItems)
                hydrateTvMetadataAsync(newItems)
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun fetchSection(sectionId: String, page: Int): SeeAllFetchPage {
        if (sectionId.startsWith("shelf:")) {
            val shelfId = sectionId.removePrefix("shelf:")
            // Peek rather than remove — on back-nav and re-open, the shelf
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
                return SeeAllFetchPage(label, result.items, result.page < result.totalPages, result.totalResults)
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
                SeeAllFetchPage("Trending Movies", result.items, result.page < result.totalPages, result.totalResults)
            }
            "TRENDING_TV" -> {
                val result = metadataRepo.getTrendingPaged("tv", page)
                SeeAllFetchPage("Trending TV Shows", result.items, result.page < result.totalPages, result.totalResults)
            }
            "POPULAR_MOVIES" -> {
                val result = metadataRepo.getPopularPaged("movie", page)
                SeeAllFetchPage("Popular Movies", result.items, result.page < result.totalPages, result.totalResults)
            }
            "POPULAR_TV" -> {
                val result = metadataRepo.getPopularPaged("tv", page)
                SeeAllFetchPage("Popular TV Shows", result.items, result.page < result.totalPages, result.totalResults)
            }
            "NOW_PLAYING" -> {
                val result = metadataRepo.discover(type = "movie", page = page)
                SeeAllFetchPage("Now Playing", result.items, result.page < result.totalPages, result.totalResults)
            }
            "TOP_RATED", "TOP_RATED_MOVIES" -> {
                val result = metadataRepo.getTopRatedPaged("movie", page)
                SeeAllFetchPage("Top Rated Movies", result.items, result.page < result.totalPages, result.totalResults)
            }
            "TOP_RATED_TV" -> {
                val result = metadataRepo.getTopRatedPaged("tv", page)
                SeeAllFetchPage("Top Rated TV Shows", result.items, result.page < result.totalPages, result.totalResults)
            }
            "NEW_RELEASES", "UPCOMING" -> {
                val items = metadataRepo.getUpcoming(page)
                SeeAllFetchPage("Upcoming", items, items.size >= 20 && page < 5, items.size)
            }
            "continue_watching" -> {
                // Use the same source as the home rail (in-progress, not history),
                // group series episodes under the show, navigate to show detail.
                val raw = watchProgressRepo.getInProgress(50).map { wp ->
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
                val raw = watchHistoryRepo.getRecent(80).map { entry ->
                    // DB stores "series"/"movie" lowercase; also tolerate "tv".
                    val mt = entry.mediaType.lowercase()
                    val isSeries = mt == "series" || mt == "tv" ||
                        entry.seasonNumber != null || entry.episodeNumber != null
                    val showTitle = entry.showTitle?.takeIf { it.isNotBlank() }
                    MediaItem(
                        id = entry.mediaId,
                        tmdbId = entry.mediaId.extractTmdbIdOrNull(),
                        imdbId = entry.mediaId.extractImdbIdOrNull(),
                        type = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
                        title = if (isSeries) (showTitle ?: entry.title) else entry.title,
                        posterUrl = entry.posterUrl,
                        backdropUrl = entry.backdropUrl,
                    )
                }
                val items = enrichRatings(resolveImdbToTmdb(dedupePerShow(raw)))
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
        return episodes
            .sortedBy { it.firstAired }
            .distinctBy { episode ->
                "${episode.showTmdbId ?: episode.showTitle}:${episode.season}:${episode.episode}:${episode.firstAired}"
            }
            .map { it.toUpcomingScheduleItem() }
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
        return if (f.specificTmdbIds.isNotEmpty()) {
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
     * the show id — never as one card per watched episode.
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
     * before TMDB resolution), resolve IMDB→TMDB via the TMDB /find endpoint so
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
     * pills render. Failures are non-fatal — items keep their original fields.
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
        if (!policy.enforcementEnabled) return items
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
