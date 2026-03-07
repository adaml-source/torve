package com.torve.domain.repository

import com.torve.domain.model.MediaItem

interface TraktRepository {
    suspend fun getAuthUrl(): String
    suspend fun isAuthenticated(): Boolean
    suspend fun getWatchlist(): List<MediaItem>
    suspend fun getWatchedHistory(limit: Int = 100): List<MediaItem>
    suspend fun addToHistory(mediaItem: MediaItem)
    suspend fun addToWatchlist(mediaItem: MediaItem)
    suspend fun removeFromWatchlist(mediaItem: MediaItem)
    suspend fun scrobbleStart(mediaItem: MediaItem, progressPercent: Double)
    suspend fun scrobblePause(mediaItem: MediaItem, progressPercent: Double)
    suspend fun scrobbleStop(mediaItem: MediaItem, progressPercent: Double)
}
