package com.torve.domain.repository

import com.torve.data.metadata.TmdbKeyword
import com.torve.data.metadata.TmdbPerson
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.PagedResult
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season

interface MetadataRepository {
    suspend fun getTrending(type: String, page: Int = 1): List<MediaItem>
    suspend fun getPopular(type: String, page: Int = 1): List<MediaItem>
    suspend fun getTopRated(type: String, page: Int = 1): List<MediaItem>
    suspend fun getUpcoming(page: Int = 1): List<MediaItem>
    suspend fun getNowPlaying(page: Int = 1): List<MediaItem>
    suspend fun getAiringToday(page: Int = 1): List<MediaItem>
    suspend fun searchMulti(query: String, page: Int = 1): List<MediaItem>
    suspend fun findByImdbId(imdbId: String, preferredType: String? = null): MediaItem?
    suspend fun getDetail(type: String, id: Int): MediaItem
    suspend fun getSimilar(type: String, id: Int, page: Int = 1): List<MediaItem>
    suspend fun getRecommendations(type: String, id: Int, page: Int = 1): List<MediaItem>
    suspend fun getHomeShelves(): List<CatalogShelf>
    suspend fun getPersonCredits(personId: Int): List<MediaItem>
    suspend fun getPersonDetail(personId: Int): TmdbPerson
    suspend fun getPersonImageUrls(personId: Int): List<String> = emptyList()
    suspend fun getMediaImageUrls(type: String, id: Int): List<String> = emptyList()
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
        originCountries: String? = null,
        originalLanguage: String? = null,
        certification: String? = null,
        certificationGte: String? = null,
        certificationLte: String? = null,
        certificationCountry: String? = null,
        withCast: String? = null,
        withCrew: String? = null,
        withWatchProviders: String? = null,
        watchRegion: String? = null,
        withKeywords: String? = null,
    ): PagedResult
    suspend fun searchKeywords(query: String): List<TmdbKeyword>
    suspend fun searchMultiPaged(query: String, page: Int = 1, type: String? = null): PagedResult
    suspend fun getPopularPeople(page: Int = 1): List<PersonSummary>
    suspend fun searchPerson(query: String, page: Int = 1): List<PersonSummary>
    suspend fun getWatchProviderLogos(type: String = "movie", region: String = "US"): Map<Int, String>
    suspend fun getLogoUrl(type: String, tmdbId: Int): String?
}
