package com.torve.presentation.seeall

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.CustomSection
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class SeeAllUiState(
    val title: String = "",
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val sectionId: String = "",
)

class SeeAllViewModel(
    private val metadataRepo: MetadataRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val watchlistRepo: WatchlistRepository,
    private val prefsRepo: PreferencesRepository,
) {
    companion object {
        /** Temporary holder for shelf items that can't be paginated from an API. */
        val pendingItems: MutableMap<String, Pair<String, List<MediaItem>>> = mutableMapOf()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SeeAllUiState())
    val state: StateFlow<SeeAllUiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    fun loadSection(sectionId: String) {
        _state.update { it.copy(sectionId = sectionId, isLoading = true) }
        scope.launch {
            try {
                val page = 1
                val (title, items, hasMore) = fetchSection(sectionId, page)
                _state.update {
                    it.copy(
                        title = title,
                        items = items,
                        isLoading = false,
                        page = 2,
                        hasMore = hasMore,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, hasMore = false) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.isLoading) return
        _state.update { it.copy(isLoading = true) }
        scope.launch {
            try {
                val (_, items, hasMore) = fetchSection(s.sectionId, s.page)
                val existingIds = _state.value.items.map { it.id }.toSet()
                val newItems = items.filter { it.id !in existingIds }
                _state.update {
                    it.copy(
                        items = it.items + newItems,
                        isLoading = false,
                        page = it.page + 1,
                        hasMore = hasMore,
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun fetchSection(sectionId: String, page: Int): Triple<String, List<MediaItem>, Boolean> {
        if (sectionId.startsWith("shelf:")) {
            val shelfId = sectionId.removePrefix("shelf:")
            val (title, items) = pendingItems.remove(shelfId)
                ?: return Triple("", emptyList(), false)
            return Triple(title, items, false)
        }

        if (sectionId.startsWith("custom:")) {
            val customId = sectionId.removePrefix("custom:")
            val section = loadCustomSections().firstOrNull { it.id == customId }
                ?: return Triple("Custom Section", emptyList(), false)
            val (items, hasMore) = fetchCustomSection(section, page)
            return Triple(section.title, items, hasMore)
        }

        return when (sectionId) {
            "TRENDING_MOVIES" -> {
                val result = metadataRepo.getTrendingPaged("movie", page)
                Triple("Trending Movies", result.items, result.page < result.totalPages)
            }
            "TRENDING_TV" -> {
                val result = metadataRepo.getTrendingPaged("tv", page)
                Triple("Trending TV Shows", result.items, result.page < result.totalPages)
            }
            "POPULAR_MOVIES" -> {
                val result = metadataRepo.getPopularPaged("movie", page)
                Triple("Popular Movies", result.items, result.page < result.totalPages)
            }
            "NOW_PLAYING" -> {
                val result = metadataRepo.discover(type = "movie", page = page)
                Triple("Now Playing", result.items, result.page < result.totalPages)
            }
            "TOP_RATED" -> {
                val result = metadataRepo.getTopRatedPaged("movie", page)
                Triple("Top Rated", result.items, result.page < result.totalPages)
            }
            "NEW_RELEASES" -> {
                val result = metadataRepo.discover(type = "movie", page = page, sortBy = "primary_release_date.desc")
                Triple("Upcoming", result.items, result.page < result.totalPages)
            }
            "continue_watching" -> {
                val items = watchHistoryRepo.getRecent(50).map { entry ->
                    MediaItem(
                        id = entry.mediaId,
                        type = if (entry.mediaType == MediaType.SERIES.name || entry.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
                        title = entry.title,
                        posterUrl = entry.posterUrl,
                        backdropUrl = entry.backdropUrl,
                    )
                }
                Triple("Continue Watching", items, false)
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
                Triple("My Watchlist", items, false)
            }
            "recommended" -> {
                // Recommendations don't paginate from TMDB directly; return what we have
                Triple("Recommended For You", emptyList(), false)
            }
            "recently_watched" -> {
                val items = watchHistoryRepo.getRecent(50).map { entry ->
                    MediaItem(
                        id = entry.mediaId,
                        type = if (entry.mediaType == MediaType.SERIES.name || entry.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
                        title = entry.title,
                        posterUrl = entry.posterUrl,
                        backdropUrl = entry.backdropUrl,
                    )
                }
                Triple("Recently Watched", items, false)
            }
            else -> Triple(sectionId.replace("_", " "), emptyList(), false)
        }
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
}
