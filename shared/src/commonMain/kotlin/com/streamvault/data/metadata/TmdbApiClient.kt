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

    suspend fun discoverByGenre(type: String, genreId: Int, page: Int = 1): TmdbResponse<TmdbMovie> {
        return httpClient.get("$BASE_URL/discover/$type") {
            parameter("api_key", API_KEY)
            parameter("with_genres", genreId)
            parameter("sort_by", "popularity.desc")
            parameter("page", page)
        }.body()
    }
}
