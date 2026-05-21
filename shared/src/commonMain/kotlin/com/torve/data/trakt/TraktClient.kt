package com.torve.data.trakt

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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class TraktClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        const val TRAKT_BASE = "https://api.trakt.tv"
        // Trakt's device-code flow calls this pair client_id/client_secret,
        // but for native clients it is public OAuth app configuration. It
        // never grants Torve premium, resolver access, or backend access.
        const val DEFAULT_PUBLIC_CLIENT_ID = "1e8d7696fb3bae585a4036fd03569e68426aa4b540b2911180ec9f540688ac6a"
        const val DEFAULT_PUBLIC_CLIENT_SECRET = "4fd2e66df137b876575ff390a1c8d46f27c9b4a4db08f40d4c9867ce6d65e6a3"
        // Sent on every Trakt request. Required by Trakt's API guide
        // and also keeps us out of Cloudflare's "no-UA = bot" filter
        // (which surfaces as 429 / Cloudflare error 1015).
        private const val USER_AGENT = "Torve/1.0 (+https://torve.app)"
    }

    var clientId: String = DEFAULT_PUBLIC_CLIENT_ID
        private set
    var clientSecret: String = DEFAULT_PUBLIC_CLIENT_SECRET
        private set

    fun setCredentials(clientId: String, clientSecret: String) {
        this.clientId = clientId.ifBlank { DEFAULT_PUBLIC_CLIENT_ID }
        this.clientSecret = clientSecret.ifBlank { DEFAULT_PUBLIC_CLIENT_SECRET }
    }

    private fun traktHeaders(accessToken: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "trakt-api-version" to "2",
            "trakt-api-key" to clientId,
            // Cloudflare's edge filtering (which Trakt sits behind)
            // hard-rate-limits requests without a recognisable User-
            // Agent string -- the symptom is "Trakt API error 429:
            // error code: 1015" with no real-world traffic against
            // the user. Trakt's API reference requires a real UA
            // identifying the app + version. We pin a stable string
            // here so the same UA renders across environments.
            "User-Agent" to USER_AGENT,
        )
        if (accessToken != null) {
            headers["Authorization"] = "Bearer $accessToken"
        }
        return headers
    }

    // -------------------------------------------------------------------------
    // Public Rating Lookup (no user auth — only client_id header)
    // -------------------------------------------------------------------------

    suspend fun getMoviePublicRating(imdbId: String): TraktPublicRating? {
        return try {
            val response = httpClient.get("$TRAKT_BASE/movies/$imdbId") {
                traktHeaders().forEach { (k, v) -> header(k, v) }
                parameter("extended", "full")
            }
            if (!response.status.isSuccess()) return null
            response.body<TraktPublicRating>()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getShowPublicRating(imdbId: String): TraktPublicRating? {
        return try {
            val response = httpClient.get("$TRAKT_BASE/shows/$imdbId") {
                traktHeaders().forEach { (k, v) -> header(k, v) }
                parameter("extended", "full")
            }
            if (!response.status.isSuccess()) return null
            response.body<TraktPublicRating>()
        } catch (_: Exception) {
            null
        }
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
            throw Exception(traktErrorMessage(response))
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
                    // Parse defensively: if the JSON shape drifts or the
                    // serializer can't decode for any reason, prefer a typed
                    // Error with a short preview of the body instead of
                    // throwing out to the generic catch (which loses context).
                    try {
                        val resp: TraktTokenResponse = decodeTraktBody(response)
                        if (resp.accessToken.isBlank() || resp.refreshToken.isBlank()) {
                            TraktPollResult.Error("Trakt returned an invalid token response.")
                        } else {
                            TraktPollResult.Success(
                                TraktTokens(
                                    accessToken = resp.accessToken,
                                    refreshToken = resp.refreshToken,
                                    expiresIn = resp.expiresIn,
                                    createdAt = resp.createdAt,
                                ),
                            )
                        }
                    } catch (parseErr: Exception) {
                        TraktPollResult.Error("Trakt token response could not be decoded.")
                    }
                }
                400 -> TraktPollResult.Pending
                403 -> TraktPollResult.SlowDown
                404, 410 -> TraktPollResult.Expired
                409 -> TraktPollResult.AlreadyUsed
                418 -> TraktPollResult.Denied
                429 -> TraktPollResult.SlowDown
                // 5xx is retryable — Trakt sometimes blips mid-flight.
                in 500..599 -> TraktPollResult.TransientError("Server error ${response.status.value}")
                else -> {
                    TraktPollResult.Error("Trakt authentication failed with HTTP ${response.status.value}.")
                }
            }
        } catch (e: Exception) {
            // Network-layer failures (DNS, connect, timeout) are transient
            // by nature during a 10-minute device-auth window — the network
            // often shifts when the user bounces to the browser. Retrying
            // on the next tick almost always recovers.
            TraktPollResult.TransientError("Network error while connecting to Trakt.")
        }
    }

    suspend fun refreshToken(refreshToken: String): TraktTokens {
        val response = httpClient.post("$TRAKT_BASE/oauth/token") {
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
        }
        val resp: TraktTokenResponse = decodeTraktBody(response)
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
        val response = httpClient.get("$TRAKT_BASE/users/me") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            parameter("extended", "full")
        }
        val resp: TraktUserResponse = decodeTraktBody(response)
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
        val response = httpClient.get("$TRAKT_BASE/users/me/stats") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }
        val resp: TraktStatsResponse = decodeTraktBody(response)
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
        val response = httpClient.get("$TRAKT_BASE/sync/watchlist") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }
        return decodeTraktBody(response)
    }

    suspend fun addToWatchlist(accessToken: String, body: TraktWatchlistBody) {
        val response = httpClient.post("$TRAKT_BASE/sync/watchlist") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw Exception(traktErrorMessage(response))
        }
    }

    suspend fun removeFromWatchlist(accessToken: String, body: TraktWatchlistBody) {
        val response = httpClient.post("$TRAKT_BASE/sync/watchlist/remove") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw Exception(traktErrorMessage(response))
        }
    }

    // -------------------------------------------------------------------------
    // Ratings
    // -------------------------------------------------------------------------

    suspend fun getRatings(accessToken: String, limit: Int = 100): List<TraktRatingResponse> {
        val response = httpClient.get("$TRAKT_BASE/sync/ratings") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            parameter("page", 1)
            parameter("limit", limit)
        }
        return decodeTraktBody(response)
    }

    suspend fun addRatings(accessToken: String, body: TraktRatingsBody) {
        httpClient.post("$TRAKT_BASE/sync/ratings") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    suspend fun removeRatings(accessToken: String, body: TraktRatingsBody) {
        httpClient.post("$TRAKT_BASE/sync/ratings/remove") {
            contentType(ContentType.Application.Json)
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            setBody(body)
        }
    }

    // -------------------------------------------------------------------------
    // Watch History
    // -------------------------------------------------------------------------

    suspend fun getHistory(accessToken: String, limit: Int = 50): List<TraktHistoryResponse> {
        val response = httpClient.get("$TRAKT_BASE/sync/history") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
            parameter("page", 1)
            parameter("limit", limit)
        }
        return decodeTraktBody(response)
    }

    // -------------------------------------------------------------------------
    // Playback Progress (in-progress / paused items)
    // -------------------------------------------------------------------------

    suspend fun getPlaybackProgress(accessToken: String): List<TraktPlaybackResponse> {
        val response = httpClient.get("$TRAKT_BASE/sync/playback") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }
        return decodeTraktBody(response)
    }

    // -------------------------------------------------------------------------
    // Calendar (my shows airing today)
    // -------------------------------------------------------------------------

    suspend fun getCalendar(accessToken: String, days: Int = 7): List<TraktCalendarEpisode> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val response = httpClient.get("$TRAKT_BASE/calendars/my/shows/$today/$days") {
            traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
        }
        val resp: List<TraktCalendarResponse> = decodeTraktBody(response)
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

    // -------------------------------------------------------------------------
    // Error message formatting
    // -------------------------------------------------------------------------

    /**
     * Translate a non-success Trakt response into a message the user
     * can act on. The previous "Trakt API error 429: error code: 1015"
     * surface confused operators because:
     *   - 1015 is Cloudflare's IP-rate-limit code, not a Trakt account
     *     issue. Telling the user to "fix" Trakt sends them down the
     *     wrong rabbit hole.
     *   - The Retry-After header (Cloudflare returns this on 429)
     *     was being thrown away.
     *
     * Now we read Retry-After when present and surface "Trakt is
     * temporarily rate-limiting this network. Try again in N
     * seconds." for 429s, and other recognisable shapes for the
     * common Trakt error codes.
     */
    private suspend fun traktErrorMessage(response: HttpResponse): String {
        val status = response.status.value
        val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
        val cfRay = response.headers["cf-ray"]
        val isCloudflareEdge = response.headers["Server"]?.contains("cloudflare", ignoreCase = true) == true
        return when {
            status == 429 -> {
                val waitText = retryAfter?.let { "Try again in $it seconds." }
                    ?: "Try again in a minute or two."
                if (isCloudflareEdge) {
                    "Trakt is rate-limiting this network at its edge (HTTP 429" +
                        cfRay?.let { ", CF-Ray $it" }.orEmpty() +
                        "). $waitText If it keeps happening, try a different network (mobile hotspot / VPN) " +
                        "to confirm whether your IP got flagged or your account did."
                } else {
                    "Trakt is rate-limiting your account (HTTP 429). $waitText"
                }
            }
            status == 401 -> "Trakt authentication required (HTTP 401). Reconnect Trakt in Settings."
            status == 403 -> "Trakt refused this request (HTTP 403). Your client ID may be invalid."
            status in 500..599 -> "Trakt is having a server problem (HTTP $status). Try again shortly."
            else -> "Trakt request failed (HTTP $status)."
        }
    }

    private suspend inline fun <reified T> decodeTraktBody(response: HttpResponse): T {
        if (!response.status.isSuccess()) {
            throw Exception(traktErrorMessage(response))
        }
        val body = response.bodyAsText()
        return runCatching { json.decodeFromString<T>(body) }
            .getOrElse { error ->
                throw Exception(
                    "Trakt response could not be decoded: ${error.message ?: error::class.simpleName}",
                )
            }
    }
}
