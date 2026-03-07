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
}
