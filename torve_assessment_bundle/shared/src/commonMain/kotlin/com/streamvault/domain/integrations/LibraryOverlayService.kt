package com.streamvault.domain.integrations

import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchProgress

interface LibraryOverlayService {
    suspend fun isInLibrary(tmdbId: Int, mediaType: MediaType): Boolean
    suspend fun getContinueWatching(limit: Int = 20): List<WatchProgress>
    suspend fun testConnection(serverUrl: String, apiKey: String): Boolean
}

