package com.streamvault.domain.repository

import com.streamvault.domain.model.WatchlistItem

interface WatchlistRepository {
    suspend fun getAll(): List<WatchlistItem>
    suspend fun getByType(mediaType: String): List<WatchlistItem>
    suspend fun isInWatchlist(mediaId: String): Boolean
    suspend fun add(item: WatchlistItem)
    suspend fun remove(mediaId: String)
    suspend fun clear()
}
