package com.streamvault.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

class TmdbApiClient(private val httpClient: HttpClient) {

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        internal const val API_KEY = "2dca580c2a14b55200e784d157207b4d"
    }

    suspend fun getTrending(type: String = "all", page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/trending/$type/week") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getPopular(type: String, page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/$type/popular") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getTopRated(type: String, page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/$type/top_rated") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getUpcoming(page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/movie/upcoming") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getNowPlaying(page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/movie/now_playing") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getAiringToday(page: Int = 1): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/tv/airing_today") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun searchMulti(query: String, page: Int = 1): TmdbResponse<TmdbMultiResult> {
        return httpClient.get("$BASE_URL/search/multi") {
            parameter("api_key", API_KEY)
            parameter("query", query)
            parameter("page", page)
        }.body()
    }

    suspend fun getMovieDetail(id: Int): TmdbMovie {
        return httpClient.get("$BASE_URL/movie/$id") {
            parameter("api_key", API_KEY)
            parameter("append_to_response", "credits,videos,similar,external_ids")
        }.body()
    }

    suspend fun getTvDetail(id: Int): TmdbTv {
        return httpClient.get("$BASE_URL/tv/$id") {
            parameter("api_key", API_KEY)
            parameter("append_to_response", "credits,videos,similar,external_ids")
        }.body()
    }

    suspend fun getSimilar(type: String, id: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/$type/$id/similar") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getTrendingTv(page: Int = 1): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/trending/tv/week") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getPopularTv(page: Int = 1): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/tv/popular") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getTopRatedTv(page: Int = 1): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/tv/top_rated") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getSimilarTv(id: Int, page: Int = 1): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/tv/$id/similar") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getRecommendations(type: String, id: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/$type/$id/recommendations") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getRecommendationsTv(id: Int, page: Int = 1): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/tv/$id/recommendations") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun getPopularPeople(page: Int = 1): TmdbResponse<TmdbPersonSummary> {
        return httpClient.get("$BASE_URL/person/popular") {
            parameter("api_key", API_KEY)
            parameter("page", page)
        }.body()
    }

    suspend fun searchPerson(query: String, page: Int = 1): TmdbResponse<TmdbPersonSummary> {
        return httpClient.get("$BASE_URL/search/person") {
            parameter("api_key", API_KEY)
            parameter("query", query)
            parameter("page", page)
        }.body()
    }

    suspend fun getPersonCredits(personId: Int): TmdbPersonCredits {
        return httpClient.get("$BASE_URL/person/$personId/combined_credits") {
            parameter("api_key", API_KEY)
        }.body()
    }

    suspend fun getPersonDetail(personId: Int): TmdbPerson {
        return httpClient.get("$BASE_URL/person/$personId") {
            parameter("api_key", API_KEY)
        }.body()
    }

    suspend fun discoverByGenre(type: String, genreId: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/discover/$type") {
            parameter("api_key", API_KEY)
            parameter("with_genres", genreId)
            parameter("sort_by", "popularity.desc")
            parameter("page", page)
        }.body()
    }

    suspend fun discoverMovies(
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
        withWatchProviders: String? = null,
        watchRegion: String? = null,
        withKeywords: String? = null,
    ): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/discover/movie") {
            parameter("api_key", API_KEY)
            parameter("page", page)
            parameter("sort_by", sortBy)
            withGenres?.let { parameter("with_genres", it) }
            minRating?.let { parameter("vote_average.gte", it) }
            year?.let { parameter("primary_release_date.gte", "$it-01-01") }
            yearTo?.let { parameter("primary_release_date.lte", "$it-12-31") }
            runtimeGte?.let { parameter("with_runtime.gte", it) }
            runtimeLte?.let { parameter("with_runtime.lte", it) }
            withCast?.let { parameter("with_cast", it) }
            withCrew?.let { parameter("with_crew", it) }
            withWatchProviders?.let { parameter("with_watch_providers", it) }
            watchRegion?.let { parameter("watch_region", it) }
            withKeywords?.let { parameter("with_keywords", it) }
            if (minRating != null) parameter("vote_count.gte", 50)
        }.body()
    }

    suspend fun discoverTv(
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
        withWatchProviders: String? = null,
        watchRegion: String? = null,
        withKeywords: String? = null,
    ): TmdbResponse<TmdbTv> {
        return httpClient.get("$BASE_URL/discover/tv") {
            parameter("api_key", API_KEY)
            parameter("page", page)
            parameter("sort_by", sortBy)
            withGenres?.let { parameter("with_genres", it) }
            minRating?.let { parameter("vote_average.gte", it) }
            year?.let { parameter("first_air_date.gte", "$it-01-01") }
            yearTo?.let { parameter("first_air_date.lte", "$it-12-31") }
            runtimeGte?.let { parameter("with_runtime.gte", it) }
            runtimeLte?.let { parameter("with_runtime.lte", it) }
            withCast?.let { parameter("with_cast", it) }
            withCrew?.let { parameter("with_crew", it) }
            withWatchProviders?.let { parameter("with_watch_providers", it) }
            watchRegion?.let { parameter("watch_region", it) }
            withKeywords?.let { parameter("with_keywords", it) }
            if (minRating != null) parameter("vote_count.gte", 50)
        }.body()
    }

    suspend fun getTvSeasonDetail(tvId: Int, seasonNumber: Int): TmdbSeasonDetail {
        return httpClient.get("$BASE_URL/tv/$tvId/season/$seasonNumber") {
            parameter("api_key", API_KEY)
        }.body()
    }

    suspend fun searchKeywords(query: String): TmdbResponse<TmdbKeyword> {
        return httpClient.get("$BASE_URL/search/keyword") {
            parameter("api_key", API_KEY)
            parameter("query", query)
        }.body()
    }

    suspend fun getWatchProviders(type: String = "movie", region: String = "US"): TmdbWatchProvidersResponse {
        return httpClient.get("$BASE_URL/watch/providers/$type") {
            parameter("api_key", API_KEY)
            parameter("watch_region", region)
        }.body()
    }
}
