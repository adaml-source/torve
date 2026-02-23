package com.streamvault.presentation.home

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.ParentalFilter
import com.streamvault.domain.model.ShelfConfig
import com.streamvault.domain.recommendation.GetRecommendationsUseCase
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.ProfileRepository
import com.streamvault.domain.repository.ShelfConfigRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val metadataRepo: MetadataRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val recommendationsUseCase: GetRecommendationsUseCase,
    private val profileRepo: ProfileRepository,
    private val shelfConfigRepo: ShelfConfigRepository,
    private val watchlistRepo: WatchlistRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadHomeScreen()
    }

    fun loadHomeScreen() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val shelvesDeferred = async { metadataRepo.getHomeShelves() }
                val continueWatchingDeferred = async { watchProgressRepo.getInProgress(20) }
                val recommendationsDeferred = async {
                    try {
                        recommendationsUseCase.execute()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val watchlistDeferred = async {
                    try {
                        watchlistRepo.getAll().take(20)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val historyDeferred = async {
                    try {
                        watchHistoryRepo.getRecent(3)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val hiddenGemsDeferred = async {
                    try {
                        metadataRepo.discover(
                            type = "movie",
                            sortBy = "vote_average.desc",
                            minRating = 7.5f,
                            page = 1,
                        ).items.take(20)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val shelves = shelvesDeferred.await()
                val continueWatching = continueWatchingDeferred.await()
                val recommendations = recommendationsDeferred.await()
                val watchlistItems = watchlistDeferred.await()
                val recentHistory = historyDeferred.await()
                val hiddenGems = hiddenGemsDeferred.await()

                // Build watchlist shelf
                val watchlistShelf = if (watchlistItems.isNotEmpty()) {
                    CatalogShelf(
                        id = "your_watchlist",
                        title = "Your Watchlist",
                        items = watchlistItems.map { wl ->
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
                        },
                    )
                } else null

                // Build "Because You Watched" shelves from recent history
                val becauseYouWatched = recentHistory.mapNotNull { entry ->
                    try {
                        val type = if (entry.mediaType == MediaType.SERIES.name || entry.mediaType == "tv") "tv" else "movie"
                        val tmdbId = entry.mediaId.substringAfterLast("_", entry.mediaId).toIntOrNull() ?: return@mapNotNull null
                        val similar = metadataRepo.getSimilar(type, tmdbId).take(15)
                        if (similar.isNotEmpty()) {
                            CatalogShelf(
                                id = "because_${entry.mediaId}",
                                title = "Because You Watched ${entry.title}",
                                items = similar,
                            )
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }

                // Build hidden gems shelf
                val hiddenGemsShelf = if (hiddenGems.isNotEmpty()) {
                    CatalogShelf(
                        id = "hidden_gems",
                        title = "Hidden Gems",
                        items = hiddenGems,
                    )
                } else null

                // Apply shelf visibility and ordering
                val shelfConfigs = try { shelfConfigRepo.getAllConfigs() } catch (_: Exception) { emptyList() }
                val configMap = shelfConfigs.associateBy { it.shelfId }
                val orderedShelves = shelves
                    .filter { shelf -> configMap[shelf.id]?.isVisible != false }
                    .sortedBy { shelf -> configMap[shelf.id]?.sortOrder ?: Int.MAX_VALUE }

                // Apply parental content filtering
                val activeProfile = try { profileRepo.getActiveProfile() } catch (_: Exception) { null }
                val maxRating = activeProfile?.maxContentRating
                val filteredShelves = orderedShelves.map { shelf ->
                    shelf.copy(items = ParentalFilter.filter(shelf.items, maxRating))
                }
                val filteredRecommendations = if (maxRating != null) {
                    recommendations.filter { scored ->
                        ParentalFilter.filter(listOf(scored.item), maxRating).isNotEmpty()
                    }
                } else recommendations

                _state.update {
                    it.copy(
                        shelves = filteredShelves,
                        heroItem = filteredShelves.firstOrNull()?.items?.firstOrNull(),
                        continueWatching = continueWatching,
                        recommendedItems = filteredRecommendations,
                        watchlistShelf = watchlistShelf,
                        becauseYouWatched = becauseYouWatched,
                        hiddenGemsShelf = hiddenGemsShelf,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun refresh() {
        loadHomeScreen()
    }

    fun toggleShelfVisibility(shelfId: String) {
        scope.launch {
            val existing = shelfConfigRepo.getConfig(shelfId)
            val config = existing?.copy(isVisible = !existing.isVisible)
                ?: ShelfConfig(shelfId = shelfId, isVisible = false, sortOrder = 0)
            shelfConfigRepo.upsertConfig(config)
            loadHomeScreen()
        }
    }

    fun reorderShelf(shelfId: String, newOrder: Int) {
        scope.launch {
            val existing = shelfConfigRepo.getConfig(shelfId)
            val config = existing?.copy(sortOrder = newOrder)
                ?: ShelfConfig(shelfId = shelfId, isVisible = true, sortOrder = newOrder)
            shelfConfigRepo.upsertConfig(config)
            loadHomeScreen()
        }
    }
}
