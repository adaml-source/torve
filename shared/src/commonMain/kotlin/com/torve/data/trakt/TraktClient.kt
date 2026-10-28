package com.torve.data.trakt

import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        internal const val WATCHED_SHOW_PAGE_SIZE = 100
        internal const val MAX_WATCHED_SHOW_PAGES = 100
    }

    var clientId: String = DEFAULT_PUBLIC_CLIENT_ID
        private set
    var clientSecret: String = DEFAULT_PUBLIC_CLIENT_SECRET
        private set

    private var packagedClientId: String = DEFAULT_PUBLIC_CLIENT_ID
    private var packagedClientSecret: String = DEFAULT_PUBLIC_CLIENT_SECRET

    val usesCustomCredentials: Boolean
        get() = clientId != DEFAULT_PUBLIC_CLIENT_ID

    fun setPackagedCredentials(clientId: String, clientSecret: String) {
        val normalizedClientId = clientId.trim()
        val normalizedClientSecret = clientSecret.trim()
        if (normalizedClientId.isBlank() || normalizedClientSecret.isBlank()) return
        packagedClientId = normalizedClientId
        packagedClientSecret = normalizedClientSecret
        this.clientId = normalizedClientId
        this.clientSecret = normalizedClientSecret
    }

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlightGetMutex = Mutex()
    private val inFlightGets = mutableMapOf<String, Deferred<TraktRawResponse>>()

    fun setCredentials(clientId: String, clientSecret: String) {
        val normalizedClientId = clientId.trim()
        val normalizedClientSecret = clientSecret.trim()
        val hasCompleteCustomCredentials = normalizedClientId.isNotBlank() &&
            normalizedClientSecret.isNotBlank()
        this.clientId = if (hasCompleteCustomCredentials) normalizedClientId else packagedClientId
        this.clientSecret = if (hasCompleteCustomCredentials) normalizedClientSecret else packagedClientSecret
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

    /** Exact identity conversion for sparse saved items; never match by title. */
    suspend fun getImdbIdByTmdb(tmdbId: Int, series: Boolean): String? {
        require(tmdbId > 0)
        val type = if (series) "show" else "movie"
        val response = getRaw("/search/tmdb/$tmdbId", null, listOf("type" to type))
        return decodeTraktBody<List<TraktBrowseListEntry>>(response, TraktRequestBucket.UNAUTHENTICATED_GET)
            .filter { it.type == type }
            .mapNotNull { if (series) it.show?.ids else it.movie?.ids }
            .filter { it.tmdb == tmdbId }
            .mapNotNull { it.imdb?.takeIf { id -> id.matches(Regex("tt[0-9]+")) } }
            .distinct().singleOrNull()
    }

    suspend fun getContentComments(imdbId: String, series: Boolean, page: Int): com.torve.presentation.detail.ContentCommentPage {
        require(Regex("tt[0-9]+").matches(imdbId))
        val response = getRaw("/${if (series) "shows" else "movies"}/$imdbId/comments/newest", null,
            query = listOf("page" to page.coerceAtLeast(1).toString(), "limit" to "10"))
        val comments = decodeTraktBody<List<com.torve.presentation.detail.ContentComment>>(response)
        val pages = response.headers.entries.firstOrNull { it.key.equals("X-Pagination-Page-Count", true) }?.value?.toIntOrNull()
        return com.torve.presentation.detail.ContentCommentPage(comments, page,
            if (pages != null) page < pages else comments.size == 10)
    }

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
        println(
            "[TraktAuth] Device code response status=${response.status} " +
                "credentialSource=${if (usesCustomCredentials) "configured" else "bundled"}",
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
                403 -> TraktPollResult.Error(
                    "Trakt rejected Torve's OAuth application (HTTP 403).",
                )
                404, 410 -> {
                    println("[TraktAuth] Device authorization ended status=${response.status}")
                    TraktPollResult.Expired
                }
                409 -> {
                    println("[TraktAuth] Device authorization ended status=409")
                    TraktPollResult.AlreadyUsed
                }
                418 -> {
                    println("[TraktAuth] Device authorization ended status=418")
                    TraktPollResult.Denied
                }
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

    /** Authenticated account endpoint; no history sync, mutations or OAuth refresh. */
    suspend fun probeAccount(accessToken: String): com.torve.domain.integrations.ConnectionProbeResult =
        com.torve.data.integrations.authenticatedProbe {
            httpClient.get("$TRAKT_BASE/users/settings") {
                traktHeaders(accessToken).forEach { (key, value) -> header(key, value) }
            }.status
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

    /**
     * Loads the complete watched-show set. `extended=progress` is required by
     * Trakt for season/episode rows, and the endpoint has required pagination
     * since July 2026. We stop at the advertised final page when available and
     * otherwise continue until Trakt returns an empty page.
     */
    suspend fun getWatchedShows(accessToken: String): List<TraktWatchedShowResponse> {
        val watched = mutableListOf<TraktWatchedShowResponse>()
        for (page in 1..MAX_WATCHED_SHOW_PAGES) {
            val response = getRaw(
                path = "/sync/watched/shows",
                accessToken = accessToken,
                query = listOf(
                    "page" to page.toString(),
                    "limit" to WATCHED_SHOW_PAGE_SIZE.toString(),
                    "extended" to "progress",
                ),
            )
            val pageItems: List<TraktWatchedShowResponse> = decodeTraktBody(response)
            if (pageItems.isEmpty()) break
            watched += pageItems

            val pageCount = response.headers.entries
                .firstOrNull { (name, _) -> name.equals("X-Pagination-Page-Count", ignoreCase = true) }
                ?.value
                ?.substringBefore(',')
                ?.trim()
                ?.toIntOrNull()
            if (pageCount != null && page >= pageCount) break
        }
        return watched
    }

    suspend fun getShowWatchedProgress(accessToken: String, imdbId: String): TraktShowWatchedProgress {
        require(imdbId.matches(Regex("tt[0-9]+"))) { "A show IMDb ID is required" }
        return decodeTraktBody(
            getRaw(
                path = "/shows/$imdbId/progress/watched",
                accessToken = accessToken,
                query = listOf("hidden" to "true", "specials" to "true"),
            ),
        )
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

    suspend fun browsePublicLists(query: String, page: Int = 1): List<TraktBrowseList> {
        val text = query.trim().take(200)
        if (text.contains("://")) {
            val address = TraktListLinks.parse(text) ?: error("Paste a public Trakt list URL.")
            return listOf(decodeTraktBody(getRaw(address.path, null)))
        }
        val response = if (text.isBlank()) getRaw("/lists/trending", null, listOf("page" to page.coerceAtLeast(1).toString(), "limit" to "20"))
        else getRaw("/search/list", null, listOf("query" to text, "page" to page.coerceAtLeast(1).toString(), "limit" to "20"))
        return decodeTraktBody<List<TraktBrowseListEnvelope>>(response).mapNotNull { entry -> entry.list?.let { it.copy(likes = maxOf(it.likes, entry.likeCount)) } }.filter { (it.ids.trakt ?: 0) > 0 }
    }
    suspend fun getPublicListItems(listId: Int, page: Int = 1): List<TraktBrowseListEntry> {
        require(listId > 0)
        return decodeTraktBody(getRaw("/lists/$listId/items/movie,show", null,
            listOf("page" to page.coerceAtLeast(1).toString(), "limit" to "30", "extended" to "full,images")))
    }
    suspend fun getLikedBrowseLists(accessToken: String, page: Int = 1): List<TraktBrowseList> =
        decodeTraktBody<List<TraktBrowseListEnvelope>>(getRaw("/users/likes/lists", accessToken,
            listOf("page" to page.coerceAtLeast(1).toString(), "limit" to "20"))).mapNotNull { it.list }
    suspend fun getOwnBrowseListItems(accessToken: String, listId: Int, page: Int = 1): List<TraktBrowseListEntry> {
        require(listId > 0)
        return decodeTraktBody(getRaw("/users/me/lists/$listId/items/movie,show", accessToken,
            listOf("page" to page.coerceAtLeast(1).toString(), "limit" to "30", "extended" to "full,images")))
    }
    suspend fun followBrowseList(accessToken: String, listId: Int, followed: Boolean) {
        require(listId > 0)
        ensureSuccess(listMutationRaw(if (followed) HttpMethod.Post else HttpMethod.Delete, "/lists/$listId/like", accessToken), TraktRequestBucket.AUTHENTICATED_MUTATION)
    }

    suspend fun getPersonalLists(accessToken: String): List<TraktPersonalList> =
        decodeTraktBody(getRaw("/users/me/lists", accessToken))

    suspend fun createPersonalList(accessToken: String, name: String): TraktPersonalList =
        decodeTraktBody(postRaw("/users/me/lists", accessToken, TraktListWrite(validListName(name))))

    suspend fun renamePersonalList(accessToken: String, listId: Int, name: String): TraktPersonalList {
        require(listId > 0)
        return decodeTraktBody(listMutationRaw(HttpMethod.Put, "/users/me/lists/$listId", accessToken,
            buildJsonObject { put("name", validListName(name)) }.toString()))
    }

    suspend fun deletePersonalList(accessToken: String, listId: Int) {
        require(listId > 0)
        ensureSuccess(listMutationRaw(HttpMethod.Delete, "/users/me/lists/$listId", accessToken), TraktRequestBucket.AUTHENTICATED_MUTATION)
    }

    suspend fun getPersonalListItems(accessToken: String, listId: Int): List<TraktWatchlistItemResponse> {
        require(listId > 0)
        val items = mutableListOf<TraktWatchlistItemResponse>()
        var page = 1
        do {
            val response = getRaw("/users/me/lists/$listId/items", accessToken,
                listOf("page" to page.toString(), "limit" to "100"))
            val slice = decodeTraktBody<List<TraktWatchlistItemResponse>>(response)
            items.addAll(slice)
            val pageCount = response.headers.entries.firstOrNull { it.key.equals("X-Pagination-Page-Count", true) }
                ?.value?.toIntOrNull()
            if (slice.isEmpty() || (pageCount != null && page >= pageCount) || (pageCount == null && slice.size < 100)) break
            page++
            check(page <= 100) { "This list is too large to load. Please narrow it in Trakt." }
        } while (true)
        return items
    }

    suspend fun mutatePersonalListItems(accessToken: String, listId: Int, body: TraktListItemsWrite, remove: Boolean) {
        require(listId > 0)
        val result = decodeTraktBody<TraktListMutationResponse>(postRaw(
            "/users/me/lists/$listId/items${if (remove) "/remove" else ""}", accessToken, body))
        result.requireItemMatched()
    }

    private fun validListName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 100) { "Enter a list name of up to 100 characters." }
    }

    private suspend fun listMutationRaw(method: HttpMethod, path: String, accessToken: String, body: String? = null): TraktRawResponse =
        rateLimiter.run(TraktRequestBucket.AUTHENTICATED_MUTATION) {
            try {
                val response = httpClient.request("$TRAKT_BASE$path") {
                    this.method = method
                    contentType(ContentType.Application.Json)
                    traktHeaders(accessToken).forEach { (key, value) -> header(key, value) }
                    if (body != null) setBody(body)
                }
                TraktRawResponse(response.status.value,
                    response.headers.entries().associate { it.key to it.value.joinToString(",") }, response.bodyAsText())
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException || error is TraktException) throw error
                throw TraktNetworkException(error)
            }
        }

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
