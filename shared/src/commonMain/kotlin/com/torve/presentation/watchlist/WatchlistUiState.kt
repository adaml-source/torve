package com.torve.presentation.watchlist

import com.torve.domain.model.WatchlistItem

data class WatchlistUiState(
    val items: List<WatchlistItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val watchlistIds: Set<String> = emptySet(),
    val mutationState: WatchlistMutationState = WatchlistMutationState.Idle,
)

sealed interface WatchlistMutationState {
    val mediaId: String?

    data object Idle : WatchlistMutationState {
        override val mediaId: String? = null
    }

    data class Loading(override val mediaId: String) : WatchlistMutationState

    data class Success(
        override val mediaId: String,
        val isInWatchlist: Boolean,
    ) : WatchlistMutationState

    data class Error(
        override val mediaId: String,
        val userMessage: String,
    ) : WatchlistMutationState
}

fun WatchlistUiState.containsMedia(mediaId: String): Boolean =
    watchlistIds.contains(mediaId.normalizedWatchlistMediaId())

fun WatchlistUiState.isMutatingMedia(mediaId: String): Boolean =
    (mutationState as? WatchlistMutationState.Loading)?.mediaId == mediaId.normalizedWatchlistMediaId()

internal fun String.normalizedWatchlistMediaId(): String {
    val parts = split(":")
    if (parts.firstOrNull()?.lowercase() != "tmdb") return this
    return parts.lastOrNull()?.takeIf { part ->
        part.isNotBlank() && part.all { it in '0'..'9' }
    } ?: this
}
