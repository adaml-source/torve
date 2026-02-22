package com.streamvault.presentation.home

import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val metadataRepo: MetadataRepository,
    private val watchProgressRepo: WatchProgressRepository,
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
                val shelves = metadataRepo.getHomeShelves()
                val continueWatching = watchProgressRepo.getInProgress(20)
                _state.update {
                    it.copy(
                        shelves = shelves,
                        heroItem = shelves.firstOrNull()?.items?.firstOrNull(),
                        continueWatching = continueWatching,
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
}
