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
import kotlinx.serialization.json.*

data class MdbListPage(val items: List<MdbListItem>, val hasMore: Boolean)

class MdbListApi(private val httpClient: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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
    ): List<MdbListItem> = getListPage(listId, apiKey, limit, page).items

    suspend fun getListPage(listId: Int, apiKey: String, limit: Int = 100, page: Int = 1): MdbListPage {
        require(listId > 0 && limit in 1..1000 && page > 0)
        val response = httpClient.get("$BASE_URL/lists/$listId/items") {
            parameter("apikey", apiKey)
            parameter("limit", limit)
            // The service supports offset for numbered pages; `page` is ignored by MDBList.
            parameter("offset", (page.toLong() - 1) * limit)
            parameter("unified", true)
            parameter("append_to_response", "poster,description,ratings")
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("MDBList API error ${response.status.value}: $body")
        }
        val root = json.parseToJsonElement(response.bodyAsText())
        val entries: List<Pair<JsonElement, String?>> = when (root) {
            is JsonArray -> root.map { it to null }
            is JsonObject -> listOf("movies" to "movie", "shows" to "show").flatMap { (bucket, type) ->
                (root[bucket] as? JsonArray).orEmpty().map { it to type }
            }
            else -> error("Invalid MDBList items response")
        }
        val items = entries.mapNotNull { (entry, bucketType) ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val ids = obj["ids"] as? JsonObject
            val normalized = obj.toMutableMap().apply {
                if (get("mediatype") == null && bucketType != null) put("mediatype", JsonPrimitive(bucketType))
                put("tmdb_id", ids?.get("tmdb") ?: obj["tmdb_id"] ?: obj["id"] ?: JsonNull)
                if (get("year") == null) put("year", obj["release_year"] ?: JsonNull)
            }
            json.decodeFromJsonElement<MdbListItem>(JsonObject(normalized))
        }.filter { it.mediaType in setOf("movie", "show", "tv", "series") }
        val more = response.headers["X-Has-More"]?.lowercase()?.let { it == "true" || it == "1" }
            ?: (entries.size >= limit)
        return MdbListPage(items, more)
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
