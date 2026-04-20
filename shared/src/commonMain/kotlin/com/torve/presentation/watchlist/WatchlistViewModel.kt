package com.torve.presentation.watchlist

import com.torve.presentation.error.defaultMessage
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchlistItem
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class WatchlistViewModel(
    private val watchlistRepo: WatchlistRepository,
    private val prefsRepo: PreferencesRepository,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    init {
        loadWatchlist()
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events.collectLatest {
                    loadWatchlist()
                }
            }
        }
    }

    fun loadWatchlist() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Pull from Trakt first if connected, then load local
                watchlistRepo.syncFromTrakt()
                val items = applyContentPolicy(watchlistRepo.getAll())
                _state.update {
                    it.copy(
                        items = items,
                        watchlistIds = items.map { item -> item.mediaId }.toSet(),
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.WATCHLIST_FAILED.messageKey) }
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
                _state.update { it.copy(snackbarMessage = com.torve.presentation.error.UserFacingError.WATCHLIST_FAILED.defaultMessage()) }
            }
        }
    }

    fun addToWatchlist(mediaItem: MediaItem, syncTrakt: Boolean, syncSimkl: Boolean) {
        val mediaId = mediaItem.id
        scope.launch {
            try {
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
                watchlistRepo.add(item, syncTrakt, syncSimkl)
                val targets = buildList {
                    add("Watchlist")
                    if (syncTrakt) add("Trakt")
                    if (syncSimkl) add("Simkl")
                }
                _state.update {
                    it.copy(
                        items = listOf(item) + it.items,
                        watchlistIds = it.watchlistIds + mediaId,
                        snackbarMessage = "Added to ${targets.joinToString(" + ")}",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(snackbarMessage = com.torve.presentation.error.UserFacingError.WATCHLIST_FAILED.defaultMessage()) }
            }
        }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    private fun applyContentPolicy(items: List<WatchlistItem>): List<WatchlistItem> {
        val policy = currentPolicy()
        if (!policy.enforcementEnabled) return items
        return items.mapNotNull { watchlistItem ->
            val mediaItem = MediaItem(
                id = watchlistItem.mediaId,
                tmdbId = watchlistItem.tmdbId,
                imdbId = watchlistItem.imdbId,
                title = watchlistItem.title,
                posterUrl = watchlistItem.posterUrl,
                backdropUrl = watchlistItem.backdropUrl,
                rating = watchlistItem.rating,
                year = watchlistItem.year,
                type = watchlistItem.mediaType,
            )
            val filtered = contentPolicyFilter.filterItems(
                policy = policy,
                context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
                items = listOf(mediaItem),
                sourceType = ContentSourceType.LOCAL_LIBRARY,
            ).items.firstOrNull()
            filtered?.let {
                watchlistItem.copy(
                    title = it.title,
                    posterUrl = it.posterUrl,
                    backdropUrl = it.backdropUrl,
                )
            }
        }
    }
}
