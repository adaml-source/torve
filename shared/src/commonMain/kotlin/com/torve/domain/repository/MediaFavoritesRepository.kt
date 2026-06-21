package com.torve.domain.repository

import com.torve.domain.model.MediaFavorite
import com.torve.domain.model.MediaItem
import kotlinx.coroutines.flow.StateFlow

data class MediaFavoritesState(
    val items: List<MediaFavorite> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val lastError: String? = null,
    val version: String? = null,
    val updatedAt: String? = null,
)

interface MediaFavoritesRepository {
    val state: StateFlow<MediaFavoritesState>

    fun refresh(force: Boolean = true)
    fun toggleFavorite(item: MediaItem)
    fun addFavorite(item: MediaItem)
    fun removeFavorite(mediaKey: String)
    suspend fun clearSessionState()
}
