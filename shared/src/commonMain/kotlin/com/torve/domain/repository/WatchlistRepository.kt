package com.torve.domain.repository

import com.torve.domain.model.WatchlistItem

interface WatchlistRepository {
    suspend fun getAll(): List<WatchlistItem>
    suspend fun getByType(mediaType: String): List<WatchlistItem>
    suspend fun isInWatchlist(mediaId: String): Boolean
    suspend fun add(item: WatchlistItem)
    suspend fun add(item: WatchlistItem, syncTrakt: Boolean, syncSimkl: Boolean)
    suspend fun remove(mediaId: String)
    suspend fun clear()
    suspend fun syncFromTrakt()
    suspend fun addToTraktWatchlist(item: WatchlistItem): WatchlistMutationResult
    suspend fun removeFromTraktWatchlist(mediaId: String): WatchlistMutationResult
    suspend fun toggleTraktWatchlist(item: WatchlistItem): WatchlistMutationResult
}

sealed interface WatchlistMutationResult {
    val mediaId: String

    data class Success(
        override val mediaId: String,
        val isInWatchlist: Boolean,
        val item: WatchlistItem? = null,
    ) : WatchlistMutationResult

    data class MissingTraktConnection(
        override val mediaId: String,
    ) : WatchlistMutationResult

    data class InsufficientMetadata(
        override val mediaId: String,
    ) : WatchlistMutationResult

    data class Failed(
        override val mediaId: String,
    ) : WatchlistMutationResult
}
