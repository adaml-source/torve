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
import com.torve.domain.model.stableTmdbIdString
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.repository.WatchlistMutationResult
import com.torve.platform.torveVerboseLog
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
    companion object {
        const val MESSAGE_ADDED = "Added to watchlist."
        const val MESSAGE_REMOVED = "Removed from watchlist."
        const val MESSAGE_CONNECT_TRAKT = "Connect Trakt to use Watchlist."
        const val MESSAGE_UPDATE_FAILED = "Could not update watchlist. Please try again."
    }

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
            _state.update { it.copy(isLoading = it.items.isEmpty()) }
            try {
                val localItems = applyContentPolicy(watchlistRepo.getAll())
                if (localItems.isNotEmpty()) {
                    torveVerboseLog { "watchlist_local_first_hit items=${localItems.size}" }
                    _state.update {
                        it.copy(
                            items = localItems,
                            watchlistIds = localItems.map { item -> item.mediaId.normalizedWatchlistMediaId() }.toSet(),
                            isLoading = false,
                            error = null,
                        )
                    }
                } else {
                    torveVerboseLog { "watchlist_local_first_empty" }
                }

                torveVerboseLog { "watchlist_sync_started" }
                val syncResult = runCatching { watchlistRepo.syncFromTrakt() }
                syncResult
                    .onSuccess { torveVerboseLog { "watchlist_sync_completed" } }
                    .onFailure { torveVerboseLog { "watchlist_sync_failed_showing_cache type=${it::class.simpleName}" } }

                val items = applyContentPolicy(watchlistRepo.getAll())
                _state.update {
                    it.copy(
                        items = items,
                        watchlistIds = items.map { item -> item.mediaId.normalizedWatchlistMediaId() }.toSet(),
                        isLoading = false,
                        error = if (syncResult.isFailure && localItems.isEmpty()) {
                            com.torve.presentation.error.UserFacingError.WATCHLIST_FAILED.messageKey
                        } else {
                            null
                        },
                    )
                }
            } catch (e: Exception) {
                val hasCachedItems = _state.value.items.isNotEmpty()
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = if (hasCachedItems) null else com.torve.presentation.error.UserFacingError.WATCHLIST_FAILED.messageKey,
                    )
                }
            }
        }
    }

    fun isInWatchlist(mediaId: String): Boolean {
        return _state.value.containsMedia(mediaId)
    }

    fun toggleWatchlist(mediaItem: MediaItem) {
        val mediaId = mediaItem.watchlistMediaId()
        if (_state.value.isMutatingMedia(mediaId)) return
        val wasInWatchlist = isInWatchlist(mediaId)
        val item = mediaItem.toWatchlistItem()
        val normalizedMediaId = mediaId.normalizedWatchlistMediaId()

        if (mediaItem.isContentPlaceholder || item.title.isBlank() || (item.tmdbId <= 0 && item.imdbId.isNullOrBlank())) {
            publishMutationError(normalizedMediaId, MESSAGE_UPDATE_FAILED)
            return
        }

        _state.update {
            it.copy(
                mutationState = WatchlistMutationState.Loading(normalizedMediaId),
                snackbarMessage = null,
            )
        }
        scope.launch {
            val result = if (wasInWatchlist) {
                watchlistRepo.removeFromTraktWatchlist(normalizedMediaId)
            } else {
                watchlistRepo.addToTraktWatchlist(item)
            }
            applyMutationResult(result)
        }
    }

    fun addToWatchlist(mediaItem: MediaItem, syncTrakt: Boolean, syncSimkl: Boolean) {
        val mediaId = mediaItem.watchlistMediaId()
        val normalizedMediaId = mediaId.normalizedWatchlistMediaId()
        if (_state.value.isMutatingMedia(normalizedMediaId)) return
        val item = mediaItem.toWatchlistItem()
        if (mediaItem.isContentPlaceholder || item.title.isBlank() || (item.tmdbId <= 0 && item.imdbId.isNullOrBlank())) {
            publishMutationError(normalizedMediaId, MESSAGE_UPDATE_FAILED)
            return
        }
        scope.launch {
            try {
                if (syncTrakt) {
                    _state.update {
                        it.copy(
                            mutationState = WatchlistMutationState.Loading(normalizedMediaId),
                            snackbarMessage = null,
                        )
                    }
                    val result = watchlistRepo.addToTraktWatchlist(item)
                    applyMutationResult(result)
                    if (syncSimkl && result is WatchlistMutationResult.Success) {
                        watchlistRepo.add(item, syncTrakt = false, syncSimkl = true)
                    }
                } else {
                    watchlistRepo.add(item, syncTrakt = false, syncSimkl = syncSimkl)
                    _state.update {
                        it.copy(
                            items = listOf(item) + it.items.filter { existing -> existing.mediaId != mediaId },
                            watchlistIds = it.watchlistIds + normalizedMediaId,
                            snackbarMessage = MESSAGE_ADDED,
                            mutationState = WatchlistMutationState.Success(normalizedMediaId, true),
                        )
                    }
                }
            } catch (e: Exception) {
                publishMutationError(normalizedMediaId, MESSAGE_UPDATE_FAILED)
            }
        }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbarMessage = null, mutationState = WatchlistMutationState.Idle) }
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

    private fun MediaItem.toWatchlistItem(): WatchlistItem {
        val stableTmdbId = stableTmdbIdString()?.toIntOrNull()
        return WatchlistItem(
            mediaId = watchlistMediaId().normalizedWatchlistMediaId(),
            mediaType = type,
            tmdbId = tmdbId ?: stableTmdbId ?: 0,
            imdbId = imdbId,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            rating = rating,
            year = year,
            genres = genres.joinToString(", ") { it.name },
            addedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun applyMutationResult(result: WatchlistMutationResult) {
        when (result) {
            is WatchlistMutationResult.Success -> {
                if (result.isInWatchlist) {
                    val item = result.item ?: return publishMutationError(result.mediaId, MESSAGE_UPDATE_FAILED)
                    _state.update {
                        it.copy(
                            items = listOf(item) + it.items.filter { existing ->
                                existing.mediaId.normalizedWatchlistMediaId() != result.mediaId
                            },
                            watchlistIds = it.watchlistIds + result.mediaId,
                            snackbarMessage = MESSAGE_ADDED,
                            mutationState = WatchlistMutationState.Success(result.mediaId, true),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            items = it.items.filter { existing ->
                                existing.mediaId.normalizedWatchlistMediaId() != result.mediaId
                            },
                            watchlistIds = it.watchlistIds - result.mediaId,
                            snackbarMessage = MESSAGE_REMOVED,
                            mutationState = WatchlistMutationState.Success(result.mediaId, false),
                        )
                    }
                }
            }
            is WatchlistMutationResult.MissingTraktConnection ->
                publishMutationError(result.mediaId, MESSAGE_CONNECT_TRAKT)
            is WatchlistMutationResult.InsufficientMetadata ->
                publishMutationError(result.mediaId, MESSAGE_UPDATE_FAILED)
            is WatchlistMutationResult.Failed ->
                publishMutationError(result.mediaId, MESSAGE_UPDATE_FAILED)
        }
    }

    private fun publishMutationError(mediaId: String, message: String) {
        _state.update {
            it.copy(
                snackbarMessage = message,
                mutationState = WatchlistMutationState.Error(mediaId, message),
            )
        }
    }
}

internal fun MediaItem.watchlistMediaId(): String = stableTmdbIdString() ?: id
