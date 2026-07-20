package com.torve.data.mdblist

import com.torve.domain.model.MdbListInfo
import com.torve.domain.model.MdbListItem
import com.torve.domain.model.MdbListRatings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

class MdbListApi(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://api.mdblist.com"

        /**
         * Default MDBList API key used for rating lookups when the user hasn't
         * configured their own key. Free tier — 1 000 requests / day.
         * Get your own key at https://mdblist.com/preferences/
         */
        const val DEFAULT_API_KEY = "INSERT_YOUR_MDBLIST_API_KEY_HERE"
    }

    suspend fun getListItems(
        listId: Int,
        apiKey: String,
        limit: Int = 100,
        page: Int = 1,
    ): List<MdbListItem> {
        val response = httpClient.get("$BASE_URL/lists/$listId/items") {
            parameter("apikey", apiKey)
            parameter("limit", limit)
            parameter("page", page)
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("MDBList API error ${response.status.value}: $body")
        }
        return response.body()
    }

    suspend fun getListInfo(listId: Int, apiKey: String): MdbListInfo {
        val response = httpClient.get("$BASE_URL/lists/$listId") {
            parameter("apikey", apiKey)
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("MDBList API error ${response.status.value}: $body")
        }
        return response.body()
    }

    suspend fun searchLists(query: String, apiKey: String): List<MdbListInfo> {
        val response = httpClient.get("$BASE_URL/lists/search") {
            parameter("apikey", apiKey)
            parameter("query", query)
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("MDBList API error ${response.status.value}: $body")
        }
        return response.body()
    }

    suspend fun getUserLists(apiKey: String): List<MdbListInfo> {
        val response = httpClient.get("$BASE_URL/lists/user") {
            parameter("apikey", apiKey)
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("MDBList API error ${response.status.value}: $body")
        }
        return response.body()
    }

    suspend fun getTopLists(apiKey: String, limit: Int = 25): List<MdbListInfo> {
        val response = httpClient.get("$BASE_URL/lists/top") {
            parameter("apikey", apiKey)
            parameter("limit", limit)
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("MDBList API error ${response.status.value}: $body")
        }
        return response.body()
    }

    suspend fun getRatings(imdbId: String, apiKey: String): MdbListRatings? {
        val response = httpClient.get("$BASE_URL/") {
            parameter("apikey", apiKey)
            parameter("i", imdbId)
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 429) throw RateLimitException()
            return null
        }
        return try { response.body() } catch (_: Exception) { null }
    }

    suspend fun getRatingsByTmdbMovie(tmdbId: Int, apiKey: String): MdbListRatings? {
        val response = httpClient.get("$BASE_URL/tmdb/movie/$tmdbId") {
            parameter("apikey", apiKey)
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 429) throw RateLimitException()
            return null
        }
        return try { response.body() } catch (_: Exception) { null }
    }

    suspend fun getRatingsByTmdbShow(tmdbId: Int, apiKey: String): MdbListRatings? {
        val response = httpClient.get("$BASE_URL/tmdb/show/$tmdbId") {
            parameter("apikey", apiKey)
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 429) throw RateLimitException()
            return null
        }
        return try { response.body() } catch (_: Exception) { null }
    }

    class RateLimitException : Exception("MDBList daily API limit exceeded")
}
