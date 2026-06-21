package com.torve.domain.integrations

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress

interface LibraryOverlayService {
    suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean
    suspend fun getContinueWatching(limit: Int = 20): List<WatchProgress>
    suspend fun testConnection(serverUrl: String, apiKey: String): Boolean
}

