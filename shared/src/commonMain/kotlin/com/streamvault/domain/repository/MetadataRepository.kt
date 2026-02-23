package com.streamvault.domain.repository

import com.streamvault.data.metadata.TmdbPerson
import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Season

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
    suspend fun getRecommendations(type: String, id: Int, page: Int = 1): List<MediaItem>
    suspend fun getHomeShelves(): List<CatalogShelf>
    suspend fun getPersonCredits(personId: Int): List<MediaItem>
    suspend fun getPersonDetail(personId: Int): TmdbPerson
    suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season

    suspend fun getTrendingPaged(type: String, page: Int = 1): PagedResult
    suspend fun getPopularPaged(type: String, page: Int = 1): PagedResult
    suspend fun getTopRatedPaged(type: String, page: Int = 1): PagedResult
    suspend fun discover(
        type: String,
        page: Int = 1,
        sortBy: String = "popularity.desc",
        withGenres: String? = null,
        minRating: Float? = null,
        year: Int? = null,
        yearTo: Int? = null,
        runtimeGte: Int? = null,
        runtimeLte: Int? = null,
        withCast: String? = null,
        withCrew: String? = null,
    ): PagedResult
    suspend fun searchMultiPaged(query: String, page: Int = 1, type: String? = null): PagedResult
}
