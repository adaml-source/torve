package com.streamvault.presentation.watchlist

import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchlistItem
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class WatchlistViewModel(
    private val watchlistRepo: WatchlistRepository,
    private val prefsRepo: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    init {
        loadWatchlist()
    }

    fun loadWatchlist() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Pull from Trakt first if connected, then load local
                watchlistRepo.syncFromTrakt()
                val items = watchlistRepo.getAll()
                _state.update {
                    it.copy(
                        items = items,
                        watchlistIds = items.map { item -> item.mediaId }.toSet(),
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun isInWatchlist(mediaId: String): Boolean {
        return _state.value.watchlistIds.contains(mediaId)
    }

    fun toggleWatchlist(mediaItem: MediaItem) {
        val mediaId = mediaItem.id
        scope.launch {
            try {
                if (isInWatchlist(mediaId)) {
                    watchlistRepo.remove(mediaId)
                    _state.update {
                        it.copy(
                            items = it.items.filter { item -> item.mediaId != mediaId },
                            watchlistIds = it.watchlistIds - mediaId,
                            snackbarMessage = "Removed from Watchlist",
                        )
                    }
                } else {
                    val item = WatchlistItem(
                        mediaId = mediaId,
                        mediaType = mediaItem.type,
                        tmdbId = mediaItem.tmdbId ?: 0,
                        imdbId = mediaItem.imdbId,
                        title = mediaItem.title,
                        posterUrl = mediaItem.posterUrl,
                        backdropUrl = mediaItem.backdropUrl,
                        rating = mediaItem.rating,
                        year = mediaItem.year,
                        genres = mediaItem.genres.joinToString(", ") { it.name },
                        addedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                    watchlistRepo.add(item)
                    _state.update {
                        it.copy(
                            items = listOf(item) + it.items,
                            watchlistIds = it.watchlistIds + mediaId,
                            snackbarMessage = "Added to Watchlist",
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(snackbarMessage = "Error: ${e.message}") }
            }
        }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }
}
