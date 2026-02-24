package com.streamvault.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

class TraktClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val TRAKT_BASE = "https://api.trakt.tv"
        const val DEFAULT_CLIENT_ID = "1e8d7696fb3bae585a4036fd03569e68426aa4b540b2911180ec9f540688ac6a"
        const val DEFAULT_CLIENT_SECRET = "4fd2e66df137b876575ff390a1c8d46f27c9b4a4db08f40d4c9867ce6d65e6a3"
    }

    var clientId: String = DEFAULT_CLIENT_ID
        private set
    var clientSecret: String = DEFAULT_CLIENT_SECRET
        private set

    fun setCredentials(clientId: String, clientSecret: String) {
        this.clientId = clientId.ifBlank { DEFAULT_CLIENT_ID }
        this.clientSecret = clientSecret.ifBlank { DEFAULT_CLIENT_SECRET }
    }

    private fun traktHeaders(accessToken: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "trakt-api-version" to "2",
            "trakt-api-key" to clientId,
        )
        if (accessToken != null) {
            headers["Authorization"] = "Bearer $accessToken"
        }
        return headers
    }

    // -------------------------------------------------------------------------
    // Device Code Flow
    // -------------------------------------------------------------------------

    suspend fun getDeviceCode(): TraktDeviceCode {
        if (clientId.isBlank()) {
            throw Exception("Trakt Client ID not configured. Set it in Settings.")
        }
        val response: HttpResponse = httpClient.post("$TRAKT_BASE/oauth/device/code") {
            contentType(ContentType.Application.Json)
            traktHeaders().forEach { (k, v) -> header(k, v) }
            setBody(
                json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    mapOf("client_id" to clientId),
                ),
            )
        }
        if (!response.status.isSuccess()) {
            val body = try { response.bodyAsText().take(200) } catch (_: Exception) { "" }
            throw Exception("Trakt API error ${response.status.value}: $body")
        }
        val resp: TraktDeviceCodeResponse = response.body()
        if (resp.userCode.isBlank() || resp.verificationUrl.isBlank()) {
            throw Exception("Trakt returned empty device code fields.")
        }
        return TraktDeviceCode(
            deviceCode = resp.deviceCode,
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            interval = resp.interval,
            expiresIn = resp.expiresIn,
        )
    }

    suspend fun pollDeviceToken(deviceCode: String): TraktPollResult {
        return try {
            val response: HttpResponse = httpClient.post("$TRAKT_BASE/oauth/device/token") {
                contentType(ContentType.Application.Json)
                traktHeaders().forEach { (k, v) -> header(k, v) }
                setBody(
                    json.encodeToString(
                        kotlinx.serialization.serializer<Map<String, String>>(),
                        mapOf(
                            "code" to deviceCode,
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                        ),
                    ),
                )
            }
            when (response.status.value) {
                200 -> {
                    val resp: TraktTokenResponse = response.body()
                    TraktPollResult.Success(
                        TraktTokens(
                            accessToken = resp.accessToken,
                            refreshToken = resp.refreshToken,
                            expiresIn = resp.expiresIn,
                            createdAt = resp.createdAt,
                        ),
                    )
                }
                400 -> TraktPollResult.Pending
                403 -> TraktPollResult.SlowDown
                404, 410 -> TraktPollResult.Expired
                409 -> TraktPollResult.AlreadyUsed
                418 -> TraktPollResult.Denied
                429 -> TraktPollResult.SlowDown
                in 500..599 -> TraktPollResult.Error("Server error: ${response.status.value}")
                else -> TraktPollResult.Error("HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            TraktPollResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun refreshToken(refreshToken: String): TraktTokens {
        val resp: TraktTokenResponse = httpClient.post("$TRAKT_BASE/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    mapOf(
                        "refresh_token" to refreshToken,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                        "grant_type" to "refresh_token",
                    ),
                ),
            )
        }.body()
        return TraktTokens(
            accessToken = resp.accessToken,
            refreshToken = resp.refreshToken,
            expiresIn = resp.expiresIn,
            createdAt = resp.createdAt,
        )
    }

    // -------------------------------------------------------------------------
    // User Info
    // -------------------------------------------------------------------------

    suspend fun getUser(accessToken: String): TraktUser {
        val resp: TraktUserResponse = httpClient.get("$TRAKT_BASE/users/me") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            parameter("extended", "full")
        }.body()
        return TraktUser(
            username = resp.username,
            name = resp.name,
            vip = resp.vip,
            joined = resp.joinedAt,
            avatar = resp.images?.avatar?.full,
        )
    }

    suspend fun revokeToken(accessToken: String) {
        try {
            httpClient.post("$TRAKT_BASE/oauth/revoke") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        kotlinx.serialization.serializer<Map<String, String>>(),
                        mapOf(
                            "token" to accessToken,
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                        ),
                    ),
                )
            }
        } catch (_: Exception) {
            // Best-effort revocation
        }
    }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    suspend fun syncWatched(accessToken: String, mediaType: String): String {
        return httpClient.get("$TRAKT_BASE/sync/watched/$mediaType") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }.bodyAsText()
    }

    suspend fun addToHistory(accessToken: String, body: TraktHistoryBody) {
        httpClient.post("$TRAKT_BASE/sync/history") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    suspend fun getStats(accessToken: String): TraktStats {
        val resp: TraktStatsResponse = httpClient.get("$TRAKT_BASE/users/me/stats") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }.body()
        return TraktStats(
            moviesWatched = resp.movies?.watched ?: 0,
            episodesWatched = resp.episodes?.watched ?: 0,
            showsWatched = resp.shows?.watched ?: 0,
            minutesWatched = (resp.movies?.minutes ?: 0) + (resp.episodes?.minutes ?: 0),
        )
    }

    suspend fun removeFromHistory(accessToken: String, body: TraktRemoveHistoryBody) {
        httpClient.post("$TRAKT_BASE/sync/history/remove") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    // -------------------------------------------------------------------------
    // Watchlist
    // -------------------------------------------------------------------------

    suspend fun getWatchlist(accessToken: String): List<TraktWatchlistItemResponse> {
        return httpClient.get("$TRAKT_BASE/sync/watchlist") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }.body()
    }

    suspend fun addToWatchlist(accessToken: String, body: TraktWatchlistBody) {
        httpClient.post("$TRAKT_BASE/sync/watchlist") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    suspend fun removeFromWatchlist(accessToken: String, body: TraktWatchlistBody) {
        httpClient.post("$TRAKT_BASE/sync/watchlist/remove") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    // -------------------------------------------------------------------------
    // Watch History
    // -------------------------------------------------------------------------

    suspend fun getHistory(accessToken: String, limit: Int = 50): List<TraktHistoryResponse> {
        return try {
            httpClient.get("$TRAKT_BASE/sync/history") {
                traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
                parameter("page", 1)
                parameter("limit", limit)
            }.body()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Playback Progress (in-progress / paused items)
    // -------------------------------------------------------------------------

    suspend fun getPlaybackProgress(accessToken: String): List<TraktPlaybackResponse> {
        return try {
            httpClient.get("$TRAKT_BASE/sync/playback") {
                traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            }.body()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Calendar (my shows airing today)
    // -------------------------------------------------------------------------

    suspend fun getCalendar(accessToken: String, days: Int = 7): List<TraktCalendarEpisode> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val resp: List<TraktCalendarResponse> = httpClient.get("$TRAKT_BASE/calendars/my/shows/$today/$days") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }.body()
        return resp.mapNotNull { item ->
            val ep = item.episode ?: return@mapNotNull null
            val show = item.show ?: return@mapNotNull null
            TraktCalendarEpisode(
                showTitle = show.title,
                season = ep.season,
                episode = ep.number,
                episodeTitle = ep.title,
                firstAired = item.firstAired,
                showTmdbId = show.ids?.tmdb,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Scrobble
    // -------------------------------------------------------------------------

    suspend fun scrobbleStart(accessToken: String, body: TraktScrobbleBody) {
        httpClient.post("$TRAKT_BASE/scrobble/start") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    suspend fun scrobblePause(accessToken: String, body: TraktScrobbleBody) {
        httpClient.post("$TRAKT_BASE/scrobble/pause") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    suspend fun scrobbleStop(accessToken: String, body: TraktScrobbleBody) {
        httpClient.post("$TRAKT_BASE/scrobble/stop") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }
}
