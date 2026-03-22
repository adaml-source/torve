package com.torve.domain.repository

import com.torve.domain.model.WatchProgress

interface WatchProgressRepository {
    suspend fun getInProgress(limit: Long = 20): List<WatchProgress>
    suspend fun getProgress(mediaId: String): WatchProgress?
    suspend fun saveProgress(progress: WatchProgress)
    suspend fun getAllProgress(): List<WatchProgress>
    suspend fun deleteProgress(mediaId: String)
    suspend fun clearAllProgress()
    suspend fun syncFromTrakt()
}
