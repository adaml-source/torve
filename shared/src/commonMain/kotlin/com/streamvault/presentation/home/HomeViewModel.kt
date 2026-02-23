package com.streamvault.presentation.home

import com.streamvault.domain.model.ParentalFilter
import com.streamvault.domain.model.ShelfConfig
import com.streamvault.domain.recommendation.GetRecommendationsUseCase
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.ProfileRepository
import com.streamvault.domain.repository.ShelfConfigRepository
import com.streamvault.domain.repository.WatchProgressRepository
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

                val shelves = shelvesDeferred.await()
                val continueWatching = continueWatchingDeferred.await()
                val recommendations = recommendationsDeferred.await()

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
