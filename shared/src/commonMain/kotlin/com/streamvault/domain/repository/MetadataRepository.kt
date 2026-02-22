package com.streamvault.domain.repository

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.MediaItem

interface MetadataRepository {
    suspend fun getTrending(type: String, page: Int = 1): List<MediaItem>
    suspend fun getPopular(type: String, page: Int = 1): List<MediaItem>
    suspend fun getTopRated(type: String, page: Int = 1): List<MediaItem>
    suspend fun getUpcoming(page: Int = 1): List<MediaItem>
    suspend fun getNowPlaying(page: Int = 1): List<MediaItem>
    suspend fun getAiringToday(page: Int = 1): List<MediaItem>
    suspend fun searchMulti(query: String, page: Int = 1): List<MediaItem>
    suspend fun getDetail(type: String, id: Int): MediaItem
    suspend fun getSimilar(type: String, id: Int, page: Int = 1): List<MediaItem>
    suspend fun getHomeShelves(): List<CatalogShelf>
}
