package com.torve.domain.repository

import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface WatchHistoryRepository {
    suspend fun getRecent(limit: Int = 50): List<WatchHistoryEntry>
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry>
    suspend fun getAll(): List<WatchHistoryEntry>
    suspend fun getForMedia(mediaId: String): List<WatchHistoryEntry>
    fun observeForMedia(mediaId: String): Flow<List<WatchHistoryEntry>> = flow {
        emit(getForMedia(mediaId))
    }
    suspend fun refreshSeries(item: MediaItem) = Unit
    suspend fun record(entry: WatchHistoryEntry)
    suspend fun delete(id: String)
    suspend fun deleteEpisode(mediaId: String, season: Int, episode: Int) {
        getForMedia(mediaId).filter { it.seasonNumber == season && it.episodeNumber == episode }
            .forEach { delete(it.id) }
    }
    suspend fun clearAll()
    suspend fun getCount(): Long
    suspend fun syncFromTrakt()
}
