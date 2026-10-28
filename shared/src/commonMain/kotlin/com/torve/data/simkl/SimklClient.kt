package com.torve.data.simkl

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray

/**
 * SIMKL API client.
 * Supports device-code OAuth and basic sync operations.
 */
class SimklClient(
    private val httpClient: HttpClient,
    private val mutationJournal: SimklMutationJournal? = null,
) {
    companion object {
        const val BASE_URL = "https://api.simkl.com"
        // Public OAuth client identifier for SIMKL device-code auth. It is
        // not a Torve secret and cannot grant premium or resolver access.
        const val DEFAULT_PUBLIC_CLIENT_ID = "a1ed8e154e3f526ed28d3e5edcfde96f6bbe168f830faa5412563b996e5330aa"
    }

    var clientId: String = DEFAULT_PUBLIC_CLIENT_ID
        private set

    fun setClientId(id: String) {
        this.clientId = id.ifBlank { DEFAULT_PUBLIC_CLIENT_ID }
    }

    private fun HttpRequestBuilder.identify(accessToken: String? = null) {
        parameter("client_id", clientId)
        parameter("app-name", "Torve")
        parameter("app-version", "1.4.0")
        header("User-Agent", "Torve/1.4.0")
        header("simkl-api-key", clientId)
        if (accessToken != null) header("Authorization", "Bearer $accessToken")
    }

    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) throw SimklApiException(status.value,
            runCatching { Json.parseToJsonElement(bodyAsText()) as? JsonObject }.getOrNull()
                ?.get("error")?.jsonPrimitive?.contentOrNull)
        return this
    }

    // -------------------------------------------------------------------------
    // Device Code OAuth
    // -------------------------------------------------------------------------

    suspend fun getDeviceCode(): SimklDeviceCode {
        val response: HttpResponse = httpClient.get("$BASE_URL/oauth/pin") { identify() }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("SIMKL API error ${response.status.value}: $body")
        }
        val resp: SimklDeviceCodeResponse = response.body()
        if (resp.userCode.isBlank() || resp.verificationUrl.isBlank()) {
            throw Exception("SIMKL returned empty device code. Check Client ID.")
        }
        return SimklDeviceCode(
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            expiresIn = resp.expiresIn,
            interval = resp.interval,
        )
    }

    suspend fun pollDeviceToken(userCode: String): SimklTokens? {
        return try {
            val response: HttpResponse = httpClient.get(
                "$BASE_URL/oauth/pin/$userCode",
            ) { identify() }
            if (!response.status.isSuccess()) return null
            val resp: SimklPinStatusResponse = response.body()
            if (resp.result == "OK" && resp.accessToken != null) {
                SimklTokens(accessToken = resp.accessToken)
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // -------------------------------------------------------------------------
    // User
    // -------------------------------------------------------------------------

    suspend fun getUser(accessToken: String): SimklUser {
        val resp: SimklUserResponse = httpClient.post("$BASE_URL/users/settings") {
            identify(accessToken)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.requireSuccess().body()
        return SimklUser(
            username = resp.user?.name ?: "Unknown",
            avatar = resp.user?.avatar,
        )
    }

    /** SIMKL's read-only settings query uses POST; it never writes watched history. */
    suspend fun probeAccount(accessToken: String): com.torve.domain.integrations.ConnectionProbeResult =
        com.torve.data.integrations.authenticatedProbe {
            httpClient.post("$BASE_URL/users/settings") {
                identify(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status
        }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    suspend fun addToHistory(accessToken: String, body: SimklSyncBody) {
        mutate(accessToken, "/sync/history", body.copy(shows = body.shows?.map {
            if (it.seasons == null) it.copy(status = "completed") else it
        }))
    }

    private suspend fun postMutation(accessToken: String, path: String, body: SimklSyncBody) {
        // SIMKL has one removal endpoint. Only remove unwatched planned entries here;
        // a title watched since the request was queued must keep its history.
        val plannedRemoval = path == PLANNED_REMOVAL
        val payload = if (!plannedRemoval) body else {
            suspend fun planned(items: List<SimklSyncItem>?, types: List<String>): List<SimklSyncItem>? {
                if (items.isNullOrEmpty()) return null
                val remote = types.flatMap { type -> getLibrary(accessToken, type, status = "plantowatch").items(type) }
                return items.filter { item -> remote.any { entry ->
                    val ids = entry.media?.ids
                    val match = (item.ids.tmdb != null && ids?.get("tmdb")?.jsonPrimitive?.contentOrNull == item.ids.tmdb.toString()) ||
                        (item.ids.imdb != null && ids?.get("imdb")?.jsonPrimitive?.contentOrNull == item.ids.imdb)
                    match && entry.status == "plantowatch" && entry.lastWatchedAt == null && entry.watchedEpisodes().isEmpty()
                } }.takeIf { it.isNotEmpty() }
            }
            body.copy(movies = planned(body.movies, listOf("movies")), shows = planned(body.shows, listOf("shows", "anime")))
        }
        if (payload.movies.isNullOrEmpty() && payload.shows.isNullOrEmpty()) return
        val response = httpClient.post("$BASE_URL${if (plannedRemoval) "/sync/history/remove" else path}") {
            contentType(ContentType.Application.Json)
            identify(accessToken)
            setBody(payload)
        }.requireSuccess()
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val missing = result["not_found"] as? JsonObject
        check(missing?.values?.any { it is JsonArray && it.isNotEmpty() } != true) {
            "SIMKL could not match this title or episode. Its change is saved for retry."
        }
    }

    private suspend fun mutate(accessToken: String, path: String, body: SimklSyncBody) {
        if (mutationJournal == null) postMutation(accessToken, path, body)
        else mutationJournal.submit(accessToken, path, body) { endpoint, payload -> postMutation(accessToken, endpoint, payload) }
    }

    suspend fun flushPendingWrites(accessToken: String): Boolean = mutationJournal?.flush(accessToken) {
        path, body -> postMutation(accessToken, path, body)
    } ?: true

    suspend fun addToWatchlist(accessToken: String, body: SimklSyncBody) {
        mutate(accessToken, "/sync/add-to-list", body.copy(
                movies = body.movies?.map { it.copy(to = "plantowatch") },
                shows = body.shows?.map { it.copy(to = "plantowatch") },
            ))
    }

    suspend fun removeFromWatchlist(accessToken: String, body: SimklSyncBody) {
        mutate(accessToken, PLANNED_REMOVAL, body)
    }

    suspend fun removeFromHistory(accessToken: String, body: SimklSyncBody) {
        mutate(accessToken, "/sync/history/remove", body)
    }

    suspend fun getActivities(accessToken: String): JsonObject = httpClient.get("$BASE_URL/sync/activities") {
        identify(accessToken)
    }.requireSuccess().body()

    suspend fun getLibrary(accessToken: String, type: String, since: String? = null, status: String? = null,
        idsOnly: Boolean = false): SimklLibraryResponse {
        require(type in listOf("shows", "movies", "anime"))
        return httpClient.get("$BASE_URL/sync/all-items/$type${status?.let { "/$it" }.orEmpty()}") {
            identify(accessToken)
            parameter("extended", if (idsOnly) "simkl_ids_only" else if (type == "anime") "full_anime_seasons" else "full")
            if (since != null && !idsOnly) parameter("date_from", since)
            if (!idsOnly) {
                parameter("episode_watched_at", "yes")
                parameter("episode_runtime", "yes")
                parameter("include_all_episodes", "yes")
                parameter("next_watch_info", "yes")
            }
        }.requireSuccess().body()
    }
    private val PLANNED_REMOVAL = "/torve/remove-planned"
}

class SimklApiException(val statusCode: Int, val code: String? = null) :
    IllegalStateException(if (statusCode == 401) "Reconnect SIMKL in Connections" else
        if (statusCode == 429) "SIMKL is busy. Try again shortly." else "SIMKL request failed ($statusCode)")

// --- Data Classes ---

data class SimklDeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int,
)

data class SimklTokens(
    val accessToken: String,
)

data class SimklUser(
    val username: String,
    val avatar: String? = null,
)

// --- API Response Models ---

@Serializable
data class SimklDeviceCodeResponse(
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

@Serializable
data class SimklPinStatusResponse(
    val result: String = "",
    @SerialName("access_token") val accessToken: String? = null,
)

@Serializable
data class SimklUserResponse(
    val user: SimklUserInfo? = null,
)

@Serializable
data class SimklUserInfo(
    val name: String? = null,
    val avatar: String? = null,
)

@Serializable
data class SimklSyncBody(
    val movies: List<SimklSyncItem>? = null,
    val shows: List<SimklSyncItem>? = null,
)

@Serializable
data class SimklSyncItem(
    val ids: SimklIds,
    val to: String? = null,
    val status: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("use_tvdb_anime_seasons") val useTvdbAnimeSeasons: Boolean = true,
    val seasons: List<SimklSyncSeason>? = null,
)

@Serializable
data class SimklSyncSeason(val number: Int, val episodes: List<SimklSyncEpisode>)

@Serializable
data class SimklSyncEpisode(val number: Int, @SerialName("watched_at") val watchedAt: String? = null)

fun simklEpisodeBody(ids: SimklIds, season: Int, episode: Int, watchedAt: String? = null) =
    SimklSyncBody(shows = listOf(SimklSyncItem(ids, seasons = listOf(
        SimklSyncSeason(season, listOf(SimklSyncEpisode(episode, watchedAt))),
    ))))

@Serializable
data class SimklIds(
    val simkl: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)
