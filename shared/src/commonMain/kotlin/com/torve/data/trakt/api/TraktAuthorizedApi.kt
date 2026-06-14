package com.torve.data.trakt.api

import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.TraktAuthScopeProvider
import com.torve.data.trakt.TraktDiagnosticsLogger
import com.torve.data.trakt.InMemoryTraktAuthScopeProvider
import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryResponse
import com.torve.data.trakt.TraktPlaybackResponse
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.TraktNetworkException
import com.torve.data.trakt.TraktRateLimitedException
import com.torve.data.trakt.TraktRemoveHistoryBody
import com.torve.data.trakt.TraktRatingsBody
import com.torve.data.trakt.TraktRatingResponse
import com.torve.data.trakt.TraktServerException
import com.torve.data.trakt.TraktWatchlistBody
import com.torve.data.trakt.TraktWatchlistItemResponse
import com.torve.data.trakt.TraktTokens
import com.torve.data.trakt.auth.TraktTokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class TraktAuthorizedApi(
    private val traktClient: TraktClient,
    private val tokenStore: TraktTokenStore,
    private val clock: Clock = Clock.System,
    private val diagnostics: TraktDiagnosticsLogger = TraktDiagnosticsLogger.Stdout,
    private val authScopeProvider: TraktAuthScopeProvider = InMemoryTraktAuthScopeProvider(),
    private val calendarCacheTtlMs: Long = 15L * 60L * 1000L,
    private val refreshFailureCooldownMs: Long = 60L * 1000L,
) {
    private val accountStateMutex = Mutex()
    private var accountGeneration: Long = 0L
    private val refreshMutex = Mutex()
    private var refreshFailureBlockedUntilMs: Long = 0L
    private val calendarCacheMutex = Mutex()
    private val calendarCache = mutableMapOf<String, CalendarCacheEntry>()

    suspend fun hasConnection(): Boolean =
        tokenStore.read()?.accessToken?.isNotBlank() == true

    suspend fun getWatchlist(): List<TraktWatchlistItemResponse> =
        executeWithRefresh { token -> traktClient.getWatchlist(token) }

    suspend fun getHistory(limit: Int = 100): List<TraktHistoryResponse> =
        executeWithRefresh { token -> traktClient.getHistory(token, limit) }

    suspend fun getPlaybackProgress(): List<TraktPlaybackResponse> =
        executeWithRefresh { token -> traktClient.getPlaybackProgress(token) }

    suspend fun getCalendar(days: Int = 7): List<TraktCalendarEpisode> =
        getCalendarCached(days).episodes

    suspend fun getCalendarCached(days: Int = 7): TraktCalendarResult {
        val tokens = tokenStore.read() ?: throw TraktAuthorizationRequiredException()
        val generation = currentAccountGeneration()
        val key = calendarKey(authScopeProvider.currentAuthenticatedScope(), days)
        calendarCacheMutex.withLock {
            val cached = calendarCache[key]
            if (
                cached != null &&
                clock.now().toEpochMilliseconds() - cached.loadedAtMs <= calendarCacheTtlMs
            ) {
                diagnostics.log(
                    "cache.hit",
                    mapOf("feature" to "calendar", "stale" to "false"),
                )
                return TraktCalendarResult(cached.episodes, isStale = false)
            }
        }

        return try {
            val episodes = executeWithTokenRefresh(
                initial = tokens,
                execute = { token -> traktClient.getCalendar(token, days) },
                refresh = ::refreshTokensSingleFlight,
                isUnauthorized = ::isUnauthorized,
            )
            val generationAfterFetch = currentAccountGeneration()
            calendarCacheMutex.withLock {
                if (generation == generationAfterFetch) {
                    calendarCache[key] = CalendarCacheEntry(episodes, clock.now().toEpochMilliseconds())
                }
            }
            TraktCalendarResult(episodes, isStale = false)
        } catch (error: Exception) {
            if (error is TraktAuthorizationRequiredException) throw error
            if (!error.canServeStaleCalendar()) throw error
            val generationAfterError = currentAccountGeneration()
            if (generation != generationAfterError) throw error
            val cached = calendarCacheMutex.withLock { calendarCache[key] } ?: throw error
            diagnostics.log(
                "cache.stale_served",
                mapOf(
                    "feature" to "calendar",
                    "error" to error::class.simpleName.orEmpty(),
                ),
            )
            TraktCalendarResult(cached.episodes, isStale = true, refreshError = error)
        }
    }

    suspend fun resetForAccountChange() {
        accountStateMutex.withLock {
            accountGeneration += 1L
        }
        refreshMutex.withLock {
            refreshFailureBlockedUntilMs = 0L
        }
        calendarCacheMutex.withLock {
            calendarCache.clear()
        }
        traktClient.resetRequestControlForAccountChange()
        authScopeProvider.resetForAccountChange()
    }

    suspend fun addToWatchlist(body: TraktWatchlistBody) {
        executeWithRefresh { token ->
            traktClient.addToWatchlist(token, body)
        }
    }

    suspend fun removeFromWatchlist(body: TraktWatchlistBody) {
        executeWithRefresh { token ->
            traktClient.removeFromWatchlist(token, body)
        }
    }

    suspend fun addToHistory(body: TraktHistoryBody) {
        executeWithRefresh { token ->
            traktClient.addToHistory(token, body)
        }
    }

    suspend fun removeFromHistory(body: TraktRemoveHistoryBody) {
        executeWithRefresh { token ->
            traktClient.removeFromHistory(token, body)
        }
    }

    suspend fun getRatings(limit: Int = 100): List<TraktRatingResponse> =
        executeWithRefresh { token -> traktClient.getRatings(token, limit) }

    suspend fun addRatings(body: TraktRatingsBody) {
        executeWithRefresh { token ->
            traktClient.addRatings(token, body)
        }
    }

    suspend fun removeRatings(body: TraktRatingsBody) {
        executeWithRefresh { token ->
            traktClient.removeRatings(token, body)
        }
    }

    private suspend fun <T> executeWithRefresh(block: suspend (accessToken: String) -> T): T {
        return executeWithTokenRefresh(
            initial = tokenStore.read(),
            execute = block,
            refresh = ::refreshTokensSingleFlight,
            isUnauthorized = ::isUnauthorized,
        )
    }

    private suspend fun refreshTokensSingleFlight(refreshToken: String): TraktTokens {
        val generation = currentAccountGeneration()
        val now = clock.now().toEpochMilliseconds()
        if (refreshFailureBlockedUntilMs > now) {
            throw TraktAuthorizationRequiredException()
        }
        if (!refreshMutex.tryLock()) {
            diagnostics.log("auth_refresh.joined", emptyMap())
            return refreshMutex.withLock {
                if (refreshFailureBlockedUntilMs > clock.now().toEpochMilliseconds()) {
                    throw TraktAuthorizationRequiredException()
                }
                tokenStore.read()
                    ?.takeIf { it.accessToken.isNotBlank() }
                    ?: throw TraktAuthorizationRequiredException()
            }
        }
        try {
            tokenStore.read()
                ?.takeIf { it.refreshToken != refreshToken && it.accessToken.isNotBlank() }
                ?.let { return it }
            diagnostics.log("auth_refresh.started", emptyMap())
            val refreshed = traktClient.refreshToken(refreshToken)
            if (generation != currentAccountGeneration()) {
                throw TraktAuthorizationRequiredException()
            }
            tokenStore.write(refreshed)
            return refreshed
        } catch (error: Exception) {
            refreshFailureBlockedUntilMs = clock.now().toEpochMilliseconds() + refreshFailureCooldownMs
            diagnostics.log(
                "auth_refresh.failed",
                mapOf("exception_class" to error::class.simpleName.orEmpty()),
            )
            throw TraktAuthorizationRequiredException()
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun isUnauthorized(error: Throwable): Boolean {
        if (error is TraktAuthorizationRequiredException) return true
        val message = error.message ?: return false
        return "401" in message ||
            "Unauthorized" in message ||
            "authentication required" in message ||
            "invalid_grant" in message ||
            "revoked" in message
    }

    private fun calendarKey(authScope: String, days: Int): String =
        "calendar:$authScope:$days"

    private suspend fun currentAccountGeneration(): Long =
        accountStateMutex.withLock { accountGeneration }

    private fun Throwable.canServeStaleCalendar(): Boolean =
        this is TraktNetworkException ||
            this is TraktRateLimitedException ||
            this is TraktServerException
}

class TraktAuthorizationRequiredException : IllegalStateException("Trakt not connected")

data class TraktCalendarResult(
    val episodes: List<TraktCalendarEpisode>,
    val isStale: Boolean,
    val refreshError: Throwable? = null,
)

private data class CalendarCacheEntry(
    val episodes: List<TraktCalendarEpisode>,
    val loadedAtMs: Long,
)

suspend fun <T> executeWithTokenRefresh(
    initial: TraktTokens?,
    execute: suspend (accessToken: String) -> T,
    refresh: suspend (refreshToken: String) -> TraktTokens,
    isUnauthorized: (Throwable) -> Boolean,
): T {
    val initialTokens = initial ?: throw TraktAuthorizationRequiredException()
    return try {
        execute(initialTokens.accessToken)
    } catch (first: Exception) {
        if (!isUnauthorized(first)) throw first
        val refreshed = try {
            refresh(initialTokens.refreshToken)
        } catch (_: Exception) {
            throw TraktAuthorizationRequiredException()
        }
        try {
            execute(refreshed.accessToken)
        } catch (second: Exception) {
            if (isUnauthorized(second)) throw TraktAuthorizationRequiredException()
            throw second
        }
    }
}
