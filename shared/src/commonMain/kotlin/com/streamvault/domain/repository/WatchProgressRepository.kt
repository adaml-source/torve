package com.streamvault.domain.repository

import com.streamvault.domain.model.WatchProgress

interface WatchProgressRepository {
    suspend fun getInProgress(limit: Long = 20): List<WatchProgress>
    suspend fun getProgress(mediaId: String): WatchProgress?
    suspend fun saveProgress(progress: WatchProgress)
    suspend fun getAllProgress(): List<WatchProgress>
    suspend fun clearAllProgress()
    suspend fun syncFromTrakt()
}
