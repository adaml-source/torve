package com.streamvault.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

class TraktClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val TRAKT_BASE = "https://api.trakt.tv"
        const val DEFAULT_CLIENT_ID = "b0db129b5c1a28a04ef433702abe3cbb2dbe37cbb31de2cba4804e0a06f8ee1b"
        const val DEFAULT_CLIENT_SECRET = "be08c2e7d89da0614ae8949e41c975e6cad32e4d04ded43c18c0e36eee7cfe7c"
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
            "Content-Type" to "application/json",
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
        val resp: TraktDeviceCodeResponse = httpClient.post("$TRAKT_BASE/oauth/device/code") {
            contentType(ContentType.Application.Json)
            traktHeaders().forEach { (k, v) -> header(k, v) }
            setBody(mapOf("client_id" to clientId))
        }.body()
        return TraktDeviceCode(
            deviceCode = resp.deviceCode,
            userCode = resp.userCode,
            verificationUrl = resp.verificationUrl,
            interval = resp.interval,
            expiresIn = resp.expiresIn,
        )
    }

    suspend fun pollDeviceToken(deviceCode: String): TraktTokens? {
        return try {
            val resp: TraktTokenResponse = httpClient.post("$TRAKT_BASE/oauth/device/token") {
                contentType(ContentType.Application.Json)
                header("Content-Type", "application/json")
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
            }.body()
            TraktTokens(
                accessToken = resp.accessToken,
                refreshToken = resp.refreshToken,
                expiresIn = resp.expiresIn,
                createdAt = resp.createdAt,
            )
        } catch (_: Exception) {
            null // Still pending or error
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
    // Calendar (my shows airing today)
    // -------------------------------------------------------------------------

    suspend fun getCalendar(accessToken: String, days: Int = 1): List<TraktCalendarEpisode> {
        return try {
            val resp: List<TraktCalendarResponse> = httpClient.get("$TRAKT_BASE/calendars/my/shows/today/$days") {
                traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            }.body()
            resp.mapNotNull { item ->
                val ep = item.episode ?: return@mapNotNull null
                val show = item.show ?: return@mapNotNull null
                TraktCalendarEpisode(
                    showTitle = show.title,
                    season = ep.season,
                    episode = ep.number,
                    episodeTitle = ep.title,
                    firstAired = item.firstAired,
                )
            }
        } catch (_: Exception) {
            emptyList()
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
