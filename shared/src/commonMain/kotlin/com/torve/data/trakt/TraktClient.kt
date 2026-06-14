package com.torve.data.trakt

import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class TraktClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val rateLimiter: TraktRateLimiter = TraktRateLimiter(),
    private val diagnostics: TraktDiagnosticsLogger = TraktDiagnosticsLogger.Stdout,
    private val authScopeProvider: TraktAuthScopeProvider = InMemoryTraktAuthScopeProvider(),
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
        private const val PUBLIC_AUTH_SCOPE = "public"
    }

    var clientId: String = DEFAULT_PUBLIC_CLIENT_ID
        private set
    var clientSecret: String = DEFAULT_PUBLIC_CLIENT_SECRET
        private set

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlightGetMutex = Mutex()
    private val inFlightGets = mutableMapOf<String, Deferred<TraktRawResponse>>()

    fun setCredentials(clientId: String, clientSecret: String) {
        this.clientId = clientId.ifBlank { DEFAULT_PUBLIC_CLIENT_ID }
        this.clientSecret = clientSecret.ifBlank { DEFAULT_PUBLIC_CLIENT_SECRET }
    }

    suspend fun resetRequestControlForAccountChange() {
        inFlightGetMutex.withLock {
            inFlightGets.clear()
        }
        rateLimiter.resetForAccountChange()
        authScopeProvider.resetForAccountChange()
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
            val response = getRaw(
                path = "/movies/$imdbId",
                accessToken = null,
                query = listOf("extended" to "full"),
            )
            if (response.status !in 200..299) return null
            decodeTraktBody<TraktPublicRating>(response)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getShowPublicRating(imdbId: String): TraktPublicRating? {
        return try {
            val response = getRaw(
                path = "/shows/$imdbId",
                accessToken = null,
                query = listOf("extended" to "full"),
            )
            if (response.status !in 200..299) return null
            decodeTraktBody<TraktPublicRating>(response)
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
        val response = postRaw(
            path = "/oauth/device/code",
            accessToken = null,
            bodyText = json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(),
                mapOf("client_id" to clientId),
            ),
            bucket = TraktRequestBucket.OAUTH_REFRESH,
        )
        val resp: TraktDeviceCodeResponse = decodeTraktBody(response, TraktRequestBucket.OAUTH_REFRESH)
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
            val response = postRaw(
                path = "/oauth/device/token",
                accessToken = null,
                bodyText = json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    mapOf(
                        "code" to deviceCode,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                    ),
                ),
                bucket = TraktRequestBucket.OAUTH_REFRESH,
            )
            when (response.status) {
                200 -> {
                    // Parse defensively: if the JSON shape drifts or the
                    // serializer can't decode for any reason, prefer a typed
                    // Error with a short preview of the body instead of
                    // throwing out to the generic catch (which loses context).
                    try {
                        val resp: TraktTokenResponse = decodeTraktBody(response, TraktRequestBucket.OAUTH_REFRESH)
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
                429 -> {
                    rateLimiter.markRateLimited(
                        TraktRequestBucket.OAUTH_REFRESH,
                        response.headers["Retry-After"],
                    )
                    TraktPollResult.SlowDown
                }
                // 5xx is retryable — Trakt sometimes blips mid-flight.
                in 500..599 -> TraktPollResult.TransientError("Server error ${response.status}")
                else -> {
                    TraktPollResult.Error("Trakt authentication failed with HTTP ${response.status}.")
                }
            }
        } catch (e: TraktRateLimitedException) {
            TraktPollResult.SlowDown
        } catch (e: Exception) {
            // Network-layer failures (DNS, connect, timeout) are transient
            // by nature during a 10-minute device-auth window — the network
            // often shifts when the user bounces to the browser. Retrying
            // on the next tick almost always recovers.
            TraktPollResult.TransientError("Network error while connecting to Trakt.")
        }
    }

    suspend fun refreshToken(refreshToken: String): TraktTokens {
        val response = postRaw(
            path = "/oauth/token",
            accessToken = null,
            bodyText = json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(),
                mapOf(
                    "refresh_token" to refreshToken,
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                    "grant_type" to "refresh_token",
                ),
            ),
            bucket = TraktRequestBucket.OAUTH_REFRESH,
        )
        val resp: TraktTokenResponse = decodeTraktBody(response, TraktRequestBucket.OAUTH_REFRESH)
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
        val response = getRaw(
            path = "/users/me",
            accessToken = accessToken,
            query = listOf("extended" to "full"),
        )
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
            postRaw(
                path = "/oauth/revoke",
                accessToken = null,
                bodyText = json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    mapOf(
                        "token" to accessToken,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                    ),
                ),
                bucket = TraktRequestBucket.OAUTH_REFRESH,
            )
        } catch (_: Exception) {
            // Best-effort revocation
        }
    }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    suspend fun syncWatched(accessToken: String, mediaType: String): String {
        return getRaw(
            path = "/sync/watched/$mediaType",
            accessToken = accessToken,
        ).also { ensureSuccess(it) }.body
    }

    suspend fun addToHistory(accessToken: String, body: TraktHistoryBody) {
        postRaw("/sync/history", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    suspend fun getStats(accessToken: String): TraktStats {
        val response = getRaw("/users/me/stats", accessToken)
        val resp: TraktStatsResponse = decodeTraktBody(response)
        return TraktStats(
            moviesWatched = resp.movies?.watched ?: 0,
            episodesWatched = resp.episodes?.watched ?: 0,
            showsWatched = resp.shows?.watched ?: 0,
            minutesWatched = (resp.movies?.minutes ?: 0) + (resp.episodes?.minutes ?: 0),
        )
    }

    suspend fun removeFromHistory(accessToken: String, body: TraktRemoveHistoryBody) {
        postRaw("/sync/history/remove", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    // -------------------------------------------------------------------------
    // Watchlist
    // -------------------------------------------------------------------------

    suspend fun getWatchlist(accessToken: String): List<TraktWatchlistItemResponse> {
        val response = getRaw("/sync/watchlist", accessToken)
        return decodeTraktBody(response)
    }

    suspend fun addToWatchlist(accessToken: String, body: TraktWatchlistBody) {
        postRaw("/sync/watchlist", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    suspend fun removeFromWatchlist(accessToken: String, body: TraktWatchlistBody) {
        postRaw("/sync/watchlist/remove", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    // -------------------------------------------------------------------------
    // Ratings
    // -------------------------------------------------------------------------

    suspend fun getRatings(accessToken: String, limit: Int = 100): List<TraktRatingResponse> {
        val response = getRaw(
            path = "/sync/ratings",
            accessToken = accessToken,
            query = listOf("page" to "1", "limit" to limit.toString()),
        )
        return decodeTraktBody(response)
    }

    suspend fun addRatings(accessToken: String, body: TraktRatingsBody) {
        postRaw("/sync/ratings", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    suspend fun removeRatings(accessToken: String, body: TraktRatingsBody) {
        postRaw("/sync/ratings/remove", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    // -------------------------------------------------------------------------
    // Watch History
    // -------------------------------------------------------------------------

    suspend fun getHistory(accessToken: String, limit: Int = 50): List<TraktHistoryResponse> {
        val response = getRaw(
            path = "/sync/history",
            accessToken = accessToken,
            query = listOf("page" to "1", "limit" to limit.toString()),
        )
        return decodeTraktBody(response)
    }

    // -------------------------------------------------------------------------
    // Playback Progress (in-progress / paused items)
    // -------------------------------------------------------------------------

    suspend fun getPlaybackProgress(accessToken: String): List<TraktPlaybackResponse> {
        val response = getRaw("/sync/playback", accessToken)
        return decodeTraktBody(response)
    }

    // -------------------------------------------------------------------------
    // Calendar (my shows airing today)
    // -------------------------------------------------------------------------

    suspend fun getCalendar(accessToken: String, days: Int = 7): List<TraktCalendarEpisode> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val response = getRaw("/calendars/my/shows/$today/$days", accessToken)
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
        postRaw("/scrobble/start", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    suspend fun scrobblePause(accessToken: String, body: TraktScrobbleBody) {
        postRaw("/scrobble/pause", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    suspend fun scrobbleStop(accessToken: String, body: TraktScrobbleBody) {
        postRaw("/scrobble/stop", accessToken, body = body)
            .also { ensureSuccess(it, TraktRequestBucket.AUTHENTICATED_MUTATION) }
    }

    private suspend fun getRaw(
        path: String,
        accessToken: String?,
        query: List<Pair<String, String>> = emptyList(),
    ): TraktRawResponse {
        val bucket = if (accessToken == null) {
            TraktRequestBucket.UNAUTHENTICATED_GET
        } else {
            TraktRequestBucket.AUTHENTICATED_GET
        }
        val authScope = if (accessToken == null) {
            PUBLIC_AUTH_SCOPE
        } else {
            authScopeProvider.currentAuthenticatedScope()
        }
        val key = traktRequestCoalescingKey("GET", authScope, path, query)
        val winner = inFlightGetMutex.withLock {
            inFlightGets[key]?.also {
                diagnostics.log(
                    "requests.coalesced",
                    mapOf(
                        "method" to "GET",
                        "endpoint_key" to endpointLogKey(path, query),
                    ),
                )
            } ?: requestScope.async {
                try {
                    rateLimiter.run(bucket) {
                        try {
                            val response = httpClient.get("$TRAKT_BASE$path") {
                                traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
                                query.forEach { (k, v) -> parameter(k, v) }
                            }
                            TraktRawResponse(
                                status = response.status.value,
                                headers = response.headers.entries().associate { it.key to it.value.joinToString(",") },
                                body = response.bodyAsText(),
                            )
                        } catch (e: Exception) {
                            if (e is TraktException || e is TraktAuthorizationRequiredException) throw e
                            throw TraktNetworkException(e)
                        }
                    }
                } finally {
                    inFlightGetMutex.withLock { inFlightGets.remove(key) }
                }
            }.also { inFlightGets[key] = it }
        }
        return winner.await()
    }

    private suspend fun postRaw(
        path: String,
        accessToken: String?,
        body: Any? = null,
        bodyText: String? = null,
        bucket: TraktRequestBucket = TraktRequestBucket.AUTHENTICATED_MUTATION,
    ): TraktRawResponse {
        return rateLimiter.run(bucket) {
            try {
                val response = httpClient.post("$TRAKT_BASE$path") {
                    contentType(ContentType.Application.Json)
                    traktHeaders(accessToken).forEach { (k, v) -> header(k, v) }
                    when {
                        bodyText != null -> setBody(bodyText)
                        body != null -> setBody(body)
                    }
                }
                TraktRawResponse(
                    status = response.status.value,
                    headers = response.headers.entries().associate { it.key to it.value.joinToString(",") },
                    body = response.bodyAsText(),
                )
            } catch (e: Exception) {
                if (e is TraktException || e is TraktAuthorizationRequiredException) throw e
                throw TraktNetworkException(e)
            }
        }
    }

    private suspend fun ensureSuccess(
        response: TraktRawResponse,
        bucket: TraktRequestBucket = TraktRequestBucket.AUTHENTICATED_GET,
    ) {
        if (response.status in 200..299) return
        throw traktFailure(response, bucket)
    }

    private suspend inline fun <reified T> decodeTraktBody(
        response: TraktRawResponse,
        bucket: TraktRequestBucket = TraktRequestBucket.AUTHENTICATED_GET,
    ): T {
        ensureSuccess(response, bucket)
        return runCatching { json.decodeFromString<T>(response.body) }
            .getOrElse { error -> throw TraktDecodeException(error) }
    }

    private suspend fun traktFailure(
        response: TraktRawResponse,
        bucket: TraktRequestBucket,
    ): Exception {
        return when (response.status) {
            401 -> TraktAuthorizationRequiredException()
            429 -> rateLimiter.markRateLimited(bucket, response.headers["Retry-After"])
            in 500..599 -> TraktServerException(response.status)
            else -> TraktUnknownException(response.status)
        }
    }

    private fun endpointLogKey(path: String, query: List<Pair<String, String>>): String {
        val queryKey = query.sortedBy { it.first }.joinToString("&") { "${it.first}=<value>" }
        return if (queryKey.isBlank()) path else "$path?$queryKey"
    }
}
