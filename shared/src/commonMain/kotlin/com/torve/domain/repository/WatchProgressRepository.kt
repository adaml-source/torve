package com.torve.domain.repository

import com.torve.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface WatchProgressRepository {
    /** Emits after durable local progress changes so visible catalog rails can refresh. */
    val progressChanges: Flow<Unit>
        get() = emptyFlow()

    suspend fun getInProgress(limit: Long = 20): List<WatchProgress>
    suspend fun getProgress(mediaId: String): WatchProgress?
    suspend fun saveProgress(progress: WatchProgress)
    suspend fun getAllProgress(): List<WatchProgress>
    suspend fun deleteProgress(mediaId: String)
    suspend fun clearAllProgress()
    suspend fun syncFromTrakt()
}
