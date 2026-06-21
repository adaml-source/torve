package com.torve.data.trakt

import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktRequestControlTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun token_store_accepts_legacy_access_only_token_for_initial_connection_probe() = runTest {
        val store = InMemorySecretStore()
        store.put(IntegrationSecretKey.TRAKT_ACCESS_TOKEN, "legacy-access-token")
        val tokenStore = TraktTokenStore(store, json)

        val tokens = tokenStore.read()

        assertEquals("legacy-access-token", tokens?.accessToken)
        assertEquals("", tokens?.refreshToken)
        assertEquals("legacy-access-token", tokenStore.accessToken())
    }

    @Test
    fun retry_after_seconds_is_parsed_from_429() = runTest {
        val client = client { respondJson("{}", HttpStatusCode.TooManyRequests, retryAfter = "42") }

        val error = assertFailsWith<TraktRateLimitedException> {
            client.getWatchlist("access")
        }

        assertEquals(42L, error.retryAfterSeconds)
        assertEquals(TraktRequestBucket.AUTHENTICATED_GET, error.bucket)
    }

    @Test
    fun retry_after_http_date_is_parsed() {
        val now = Instant.parse("2026-05-25T08:00:00Z").toEpochMilliseconds()

        val retryAfter = parseRetryAfterSeconds("Mon, 25 May 2026 08:01:30 GMT", now)

        assertEquals(90L, retryAfter)
    }

    @Test
    fun requests_during_cooldown_do_not_hit_network() = runTest {
        var networkCalls = 0
        val client = client { _ ->
            networkCalls += 1
            respondJson("{}", HttpStatusCode.TooManyRequests, retryAfter = "60")
        }

        assertFailsWith<TraktRateLimitedException> { client.getWatchlist("access") }
        assertFailsWith<TraktRateLimitedException> { client.getWatchlist("access") }

        assertEquals(1, networkCalls)
    }

    @Test
    fun huge_retry_after_seconds_are_capped() = runTest {
        val client = client { respondJson("{}", HttpStatusCode.TooManyRequests, retryAfter = "999999") }

        val error = assertFailsWith<TraktRateLimitedException> {
            client.getWatchlist("access")
        }

        assertEquals(15L * 60L, error.retryAfterSeconds)
    }

    @Test
    fun far_future_retry_after_http_date_is_capped() = runTest {
        val client = client { respondJson("{}", HttpStatusCode.TooManyRequests, retryAfter = "Mon, 25 May 2099 08:01:30 GMT") }

        val error = assertFailsWith<TraktRateLimitedException> {
            client.getWatchlist("access")
        }

        assertEquals(15L * 60L, error.retryAfterSeconds)
    }

    @Test
    fun missing_retry_after_uses_fallback_cooldown() = runTest {
        val limiter = TraktRateLimiter(
            config = TraktRateLimiterConfig(defaultRateLimitCooldownMs = 12_000L),
            diagnostics = TraktDiagnosticsLogger { _, _ -> },
        )

        val error = limiter.markRateLimited(TraktRequestBucket.AUTHENTICATED_GET, retryAfterHeader = null)

        assertEquals(12L, error.retryAfterSeconds)
    }

    @Test
    fun identical_concurrent_get_calls_coalesce_into_one_network_call() = runTest {
        var networkCalls = 0
        val events = mutableListOf<String>()
        val client = client(
            diagnostics = TraktDiagnosticsLogger { event, _ -> events += event },
        ) { _ ->
            networkCalls += 1
            delay(50)
            respondJson("[]")
        }

        val first = async { client.getWatchlist("access") }
        val second = async { client.getWatchlist("access") }

        assertEquals(emptyList(), first.await())
        assertEquals(emptyList(), second.await())
        assertEquals(1, networkCalls)
        assertTrue(events.contains("requests.coalesced"))
    }

    @Test
    fun non_identical_get_calls_do_not_coalesce() = runTest {
        var networkCalls = 0
        val client = client { _ ->
            networkCalls += 1
            respondJson("[]")
        }

        client.getHistory("access", limit = 1)
        client.getHistory("access", limit = 2)

        assertEquals(2, networkCalls)
    }

    @Test
    fun anonymous_and_authenticated_get_keys_do_not_collide() {
        val anonymous = traktRequestCoalescingKey(
            method = "GET",
            authScope = "public",
            path = "/movies/tt123",
            query = emptyList(),
        )
        val authenticated = traktRequestCoalescingKey(
            method = "GET",
            authScope = "local-auth-scope",
            path = "/movies/tt123",
            query = emptyList(),
        )

        assertFalse(anonymous == authenticated)
    }

    @Test
    fun mutation_calls_are_paced_at_one_per_configured_interval() = runTest {
        val clock = MutableClock(1_000L)
        val waits = mutableListOf<Long>()
        val limiter = TraktRateLimiter(
            config = TraktRateLimiterConfig(mutationSpacingMs = 1_000L),
            clock = clock,
            diagnostics = TraktDiagnosticsLogger { _, _ -> },
            delayMs = { waitMs ->
                waits += waitMs
                clock.advance(waitMs)
            },
        )

        limiter.run(TraktRequestBucket.AUTHENTICATED_MUTATION) { "first" }
        limiter.run(TraktRequestBucket.AUTHENTICATED_MUTATION) { "second" }

        assertEquals(listOf(1_000L), waits)
    }

    @Test
    fun unauthenticated_get_calls_are_paced_to_avoid_public_trakt_bursts() = runTest {
        val clock = MutableClock(1_000L)
        val waits = mutableListOf<Long>()
        val limiter = TraktRateLimiter(
            config = TraktRateLimiterConfig(unauthenticatedGetSpacingMs = 500L),
            clock = clock,
            diagnostics = TraktDiagnosticsLogger { _, _ -> },
            delayMs = { waitMs ->
                waits += waitMs
                clock.advance(waitMs)
            },
        )

        limiter.run(TraktRequestBucket.UNAUTHENTICATED_GET) { "first" }
        limiter.run(TraktRequestBucket.UNAUTHENTICATED_GET) { "second" }

        assertEquals(listOf(500L), waits)
    }

    @Test
    fun oauth_refresh_calls_are_not_delayed_by_public_rating_pacing() = runTest {
        val clock = MutableClock(1_000L)
        val waits = mutableListOf<Long>()
        val limiter = TraktRateLimiter(
            config = TraktRateLimiterConfig(unauthenticatedGetSpacingMs = 500L),
            clock = clock,
            diagnostics = TraktDiagnosticsLogger { _, _ -> },
            delayMs = { waitMs ->
                waits += waitMs
                clock.advance(waitMs)
            },
        )

        limiter.run(TraktRequestBucket.UNAUTHENTICATED_GET) { "public-rating" }
        limiter.run(TraktRequestBucket.OAUTH_REFRESH) { "device-auth" }

        assertEquals(emptyList(), waits)
    }

    @Test
    fun concurrent_401_responses_trigger_only_one_token_refresh() = runTest {
        var refreshCalls = 0
        var freshWatchlistCalls = 0
        val api = authorizedApi { request ->
            when (request.url.encodedPath) {
                "/sync/watchlist" -> {
                    if (request.headers["Authorization"] == "Bearer expired") {
                        respondJson("{}", HttpStatusCode.Unauthorized)
                    } else {
                        freshWatchlistCalls += 1
                        respondJson("[]")
                    }
                }
                "/oauth/token" -> {
                    refreshCalls += 1
                    delay(50)
                    respondJson(tokenBody(access = "fresh", refresh = "refresh_2"))
                }
                else -> respondJson("{}", HttpStatusCode.NotFound)
            }
        }

        val first = async { api.getWatchlist() }
        val second = async { api.getWatchlist() }

        assertEquals(emptyList(), first.await())
        assertEquals(emptyList(), second.await())
        assertEquals(1, refreshCalls)
        assertEquals(1, freshWatchlistCalls)
    }

    @Test
    fun refresh_failure_maps_waiting_calls_to_auth_required_and_cools_down() = runTest {
        var refreshCalls = 0
        val api = authorizedApi { request ->
            when (request.url.encodedPath) {
                "/sync/watchlist" -> respondJson("{}", HttpStatusCode.Unauthorized)
                "/oauth/token" -> {
                    refreshCalls += 1
                    respondJson("{}", HttpStatusCode.Unauthorized)
                }
                else -> respondJson("{}", HttpStatusCode.NotFound)
            }
        }

        val first = async { assertFailsWith<TraktAuthorizationRequiredException> { api.getWatchlist() } }
        val second = async { assertFailsWith<TraktAuthorizationRequiredException> { api.getWatchlist() } }
        first.await()
        second.await()
        assertFailsWith<TraktAuthorizationRequiredException> { api.getWatchlist() }

        assertEquals(1, refreshCalls)
    }

    @Test
    fun calendar_uses_cached_data_on_rate_limit_failure() = runTest {
        var calls = 0
        val api = authorizedApi(calendarCacheTtlMs = -1L) { request ->
            when (request.url.encodedPath) {
                "/oauth/token" -> respondJson(tokenBody(access = "fresh", refresh = "refresh_2"))
                else -> {
                    calls += 1
                    if (calls == 1) {
                        respondJson(calendarBody("Cached Show"))
                    } else {
                        respondJson("{}", HttpStatusCode.TooManyRequests, retryAfter = "60")
                    }
                }
            }
        }

        val fresh = api.getCalendarCached(days = 33)
        delay(10)
        val stale = api.getCalendarCached(days = 33)

        assertFalse(fresh.isStale)
        assertTrue(stale.isStale)
        assertEquals("Cached Show", stale.episodes.single().showTitle)
    }

    @Test
    fun token_refresh_does_not_change_stable_auth_scope_for_calendar_cache() = runTest {
        var calendarCalls = 0
        val api = authorizedApi(calendarCacheTtlMs = 30L * 60L * 1000L) { request ->
            when (request.url.encodedPath) {
                "/oauth/token" -> respondJson(tokenBody(access = "fresh", refresh = "refresh_2"))
                else -> {
                    calendarCalls += 1
                    if (request.headers["Authorization"] == "Bearer expired") {
                        respondJson("{}", HttpStatusCode.Unauthorized)
                    } else {
                        respondJson(calendarBody("Stable Scope Show"))
                    }
                }
            }
        }

        val first = api.getCalendarCached(days = 33)
        val second = api.getCalendarCached(days = 33)

        assertEquals("Stable Scope Show", first.episodes.single().showTitle)
        assertEquals("Stable Scope Show", second.episodes.single().showTitle)
        assertEquals(2, calendarCalls)
    }

    @Test
    fun auth_required_does_not_serve_stale_calendar_data() = runTest {
        var calls = 0
        val api = authorizedApi(calendarCacheTtlMs = -1L) { request ->
            when (request.url.encodedPath) {
                "/oauth/token" -> respondJson("{}", HttpStatusCode.Unauthorized)
                else -> {
                    calls += 1
                    if (calls == 1) {
                        respondJson(calendarBody("Cached Show"))
                    } else {
                        respondJson("{}", HttpStatusCode.Unauthorized)
                    }
                }
            }
        }

        api.getCalendarCached(days = 33)

        assertFailsWith<TraktAuthorizationRequiredException> {
            api.getCalendarCached(days = 33)
        }
    }

    @Test
    fun reset_clears_calendar_cache() = runTest {
        var calls = 0
        val api = authorizedApi(calendarCacheTtlMs = 30L * 60L * 1000L) {
            calls += 1
            respondJson(calendarBody("Show $calls"))
        }

        val first = api.getCalendarCached(days = 33)
        api.resetForAccountChange()
        val second = api.getCalendarCached(days = 33)

        assertEquals("Show 1", first.episodes.single().showTitle)
        assertEquals("Show 2", second.episodes.single().showTitle)
    }

    @Test
    fun reset_clears_rate_limiter_cooldowns() = runTest {
        val limiter = TraktRateLimiter(diagnostics = TraktDiagnosticsLogger { _, _ -> })

        limiter.markRateLimited(TraktRequestBucket.AUTHENTICATED_GET, retryAfterHeader = "60")
        assertFailsWith<TraktRateLimitedException> {
            limiter.run(TraktRequestBucket.AUTHENTICATED_GET) { Unit }
        }

        limiter.resetForAccountChange()
        limiter.run(TraktRequestBucket.AUTHENTICATED_GET) { Unit }
    }

    @Test
    fun reset_clears_inflight_coalescing_state() = runTest {
        var networkCalls = 0
        val client = client { _ ->
            networkCalls += 1
            delay(50)
            respondJson("[]")
        }

        val first = async { client.getWatchlist("access") }
        delay(1)
        client.resetRequestControlForAccountChange()
        val second = async { client.getWatchlist("access") }

        assertEquals(emptyList(), first.await())
        assertEquals(emptyList(), second.await())
        assertEquals(2, networkCalls)
    }

    @Test
    fun diagnostics_do_not_include_tokens_authorization_headers_or_auth_scope() = runTest {
        val fields = mutableListOf<Map<String, String>>()
        val authScopeProvider = FixedAuthScopeProvider("local-scope-id")
        val client = client(
            diagnostics = TraktDiagnosticsLogger { _, eventFields -> fields += eventFields },
            authScopeProvider = authScopeProvider,
        ) { _ ->
            delay(50)
            respondJson("[]")
        }

        val first = async { client.getWatchlist("secret-access-token") }
        val second = async { client.getWatchlist("secret-access-token") }
        first.await()
        second.await()

        val rendered = fields.joinToString(" ")
        assertFalse(rendered.contains("secret-access-token"))
        assertFalse(rendered.contains("Authorization"))
        assertFalse(rendered.contains("Bearer"))
        assertFalse(rendered.contains("local-scope-id"))
    }

    private fun client(
        diagnostics: TraktDiagnosticsLogger = TraktDiagnosticsLogger { _, _ -> },
        authScopeProvider: TraktAuthScopeProvider = InMemoryTraktAuthScopeProvider(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): TraktClient {
        val httpClient = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return TraktClient(
            httpClient = httpClient,
            json = json,
            rateLimiter = TraktRateLimiter(diagnostics = diagnostics),
            diagnostics = diagnostics,
            authScopeProvider = authScopeProvider,
        )
    }

    private suspend fun authorizedApi(
        calendarCacheTtlMs: Long = 30L * 60L * 1000L,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): TraktAuthorizedApi {
        val store = InMemorySecretStore()
        val tokenStore = TraktTokenStore(store, json)
        val authScopeProvider = PersistedTraktAuthScopeProvider(store)
        tokenStore.write(
            TraktTokens(
                accessToken = "expired",
                refreshToken = "refresh_1",
                expiresIn = 3600,
                createdAt = 0L,
            ),
        )
        return TraktAuthorizedApi(
            traktClient = client(authScopeProvider = authScopeProvider, handler = handler),
            tokenStore = tokenStore,
            diagnostics = TraktDiagnosticsLogger { _, _ -> },
            authScopeProvider = authScopeProvider,
            calendarCacheTtlMs = calendarCacheTtlMs,
            refreshFailureCooldownMs = 300L * 1000L,
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        retryAfter: String? = null,
    ) = respond(
        content = body,
        status = status,
        headers = if (retryAfter == null) {
            headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
        } else {
            headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                "Retry-After" to listOf(retryAfter),
            )
        },
    )

    private fun tokenBody(access: String, refresh: String): String =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"created_at":0}"""

    private fun calendarBody(title: String): String =
        """
        [
          {
            "first_aired":"2026-05-25T20:00:00.000Z",
            "episode":{"season":1,"number":2,"title":"Episode"},
            "show":{"title":"$title","ids":{"tmdb":123}}
          }
        ]
        """.trimIndent()
}

private class FixedAuthScopeProvider(
    private val scope: String,
) : TraktAuthScopeProvider {
    override suspend fun currentAuthenticatedScope(): String = scope
}

private class InMemorySecretStore : IntegrationSecretStore {
    private val values = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        values[key to subKey] = value
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        values[key to subKey]

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        values.remove(key to subKey)
    }

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit

    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
        IntegrationStorageMode.DEVICE_ONLY

    override suspend fun clearAllSecrets() {
        values.clear()
    }
}

private class MutableClock(startMs: Long) : Clock {
    private var nowMs: Long = startMs

    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)

    fun advance(ms: Long) {
        nowMs += ms
    }
}
