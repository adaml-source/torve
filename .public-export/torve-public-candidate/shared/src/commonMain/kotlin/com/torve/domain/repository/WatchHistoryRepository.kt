package com.torve.domain.repository

import com.torve.domain.model.WatchHistoryEntry

interface WatchHistoryRepository {
    suspend fun getRecent(limit: Int = 50): List<WatchHistoryEntry>
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry>
    suspend fun getAll(): List<WatchHistoryEntry>
    suspend fun getForMedia(mediaId: String): List<WatchHistoryEntry>
    suspend fun record(entry: WatchHistoryEntry)
    suspend fun delete(id: String)
    suspend fun clearAll()
    suspend fun getCount(): Long
    suspend fun syncFromTrakt()
}
