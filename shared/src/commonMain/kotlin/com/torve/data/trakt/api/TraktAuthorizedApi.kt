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
import com.torve.data.trakt.TraktWatchedShowResponse
import com.torve.data.trakt.TraktShowWatchedProgress
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
    private val personalScopeProvider: () -> String = { "default" },
    calendarPreferences: com.torve.domain.repository.PreferencesRepository? = null,
    private val calendarUserIds: com.torve.data.auth.UserIdProvider? = null,
    private val accountTokens: suspend () -> TraktTokens? = { null },
    private val publishTokens: suspend (String, TraktTokens) -> TraktTokens = { _, tokens -> tokens },
) {
    private val accountStateMutex = Mutex()
    private var accountGeneration: Long = 0L
    private val refreshMutex = Mutex()
    private val refreshFailureBlockedUntilMs = mutableMapOf<String, Long>()
    private val calendarCacheMutex = Mutex()
    private val calendarCache = mutableMapOf<String, CalendarCacheEntry>()
    private val calendarSnapshots = calendarPreferences?.let { TraktCalendarSnapshotStore(it) }

    suspend fun getUser(): com.torve.data.trakt.TraktUser =
        executeWithRefresh(traktClient::getUser)

    suspend fun getStats(): com.torve.data.trakt.TraktStats =
        executeWithRefresh(traktClient::getStats)

    suspend fun scrobbleStart(body: com.torve.data.trakt.TraktScrobbleBody) =
        executeWithRefresh { traktClient.scrobbleStart(it, body) }

    suspend fun scrobblePause(body: com.torve.data.trakt.TraktScrobbleBody) =
        executeWithRefresh { traktClient.scrobblePause(it, body) }

    suspend fun scrobbleStop(body: com.torve.data.trakt.TraktScrobbleBody) =
        executeWithRefresh { traktClient.scrobbleStop(it, body) }

    /** Check the account through the same single-flight refresh path as sync. */
    suspend fun probeAccount(): com.torve.domain.integrations.ConnectionProbeResult {
        val rejected = com.torve.domain.integrations.ConnectionProbeResult.AUTH_REJECTED
        return try {
            executeWithRefresh { token ->
                traktClient.probeAccount(token).also {
                    if (it == rejected) throw TraktAuthorizationRequiredException()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: TraktAuthorizationRequiredException) { rejected }
        catch (_: TraktNetworkException) { com.torve.domain.integrations.ConnectionProbeResult.UNREACHABLE }
        catch (_: TraktRateLimitedException) { com.torve.domain.integrations.ConnectionProbeResult.UNKNOWN_FAILURE }
        catch (_: TraktServerException) { com.torve.domain.integrations.ConnectionProbeResult.UNREACHABLE }
    }

    suspend fun hasConnection(): Boolean =
        tokenStore.read()?.accessToken?.isNotBlank() == true

    suspend fun getWatchlist(): List<TraktWatchlistItemResponse> =
        executeWithRefresh { token -> traktClient.getWatchlist(token) }

    suspend fun getLikedBrowseLists(page: Int = 1): List<com.torve.data.trakt.TraktBrowseList> = executeWithRefresh { traktClient.getLikedBrowseLists(it, page) }
    suspend fun getOwnBrowseListItems(listId: Int, page: Int): List<com.torve.data.trakt.TraktBrowseListEntry> = executeWithRefresh { traktClient.getOwnBrowseListItems(it, listId, page) }
    suspend fun followBrowseList(listId: Int, followed: Boolean) = executeWithRefresh { traktClient.followBrowseList(it, listId, followed) }

    suspend fun getPersonalLists(): List<com.torve.data.trakt.TraktPersonalList> =
        executeWithRefresh { traktClient.getPersonalLists(it) }

    suspend fun createPersonalList(name: String): com.torve.data.trakt.TraktPersonalList =
        executeWithRefresh { traktClient.createPersonalList(it, name) }

    suspend fun renamePersonalList(listId: Int, name: String): com.torve.data.trakt.TraktPersonalList =
        executeWithRefresh { traktClient.renamePersonalList(it, listId, name) }

    suspend fun deletePersonalList(listId: Int) = executeWithRefresh { traktClient.deletePersonalList(it, listId) }

    suspend fun getPersonalListItems(listId: Int): List<TraktWatchlistItemResponse> =
        executeWithRefresh { traktClient.getPersonalListItems(it, listId) }

    suspend fun mutatePersonalListItems(listId: Int, body: com.torve.data.trakt.TraktListItemsWrite, remove: Boolean = false) =
        executeWithRefresh { traktClient.mutatePersonalListItems(it, listId, body, remove) }

    suspend fun getHistory(limit: Int = 100): List<TraktHistoryResponse> =
        executeWithRefresh { token -> traktClient.getHistory(token, limit) }

    suspend fun getWatchedShows(): List<TraktWatchedShowResponse> =
        executeWithRefresh { token -> traktClient.getWatchedShows(token) }

    suspend fun getShowWatchedProgress(imdbId: String): TraktShowWatchedProgress =
        executeWithRefresh { token -> traktClient.getShowWatchedProgress(token, imdbId) }

    /** Opaque connection identity for account-safe refreshes, never an access token. */
    suspend fun watchedRefreshScope(): String? = if (hasConnection()) {
        "${currentAccountGeneration()}:${authScopeProvider.currentAuthenticatedScope()}"
    } else {
        null
    }

    suspend fun getPlaybackProgress(): List<TraktPlaybackResponse> =
        executeWithRefresh { token -> traktClient.getPlaybackProgress(token) }

    suspend fun getCalendar(days: Int = 7): List<TraktCalendarEpisode> =
        getCalendarCached(days).episodes

    /** Local-only first frame, including a bounded stale copy while the network refreshes. */
    suspend fun cachedCalendar(days: Int = 7): TraktCalendarResult? = inCalendarScope {
        cachedCalendarForCurrentScope(days)
    }

    private suspend fun cachedCalendarForCurrentScope(days: Int): TraktCalendarResult? {
        if (!hasConnection()) return null
        val generation = currentAccountGeneration()
        val person = personalScopeProvider()
        val key = calendarKey(authScopeProvider.currentAuthenticatedScope(), days, person)
        val cached = calendarCacheMutex.withLock { calendarCache[key] }
            ?: calendarSnapshots?.read(key, days, clock.now().toEpochMilliseconds())
                ?.let { CalendarCacheEntry(it.episodes, it.loadedAtMs) }
            ?: return null
        if (generation != currentAccountGeneration() || person != personalScopeProvider()) return null
        val age = clock.now().toEpochMilliseconds() - cached.loadedAtMs
        if (age !in 0..TraktCalendarSnapshotStore.MAX_AGE_MS) return null
        calendarCacheMutex.withLock { calendarCache[key] = cached }
        return TraktCalendarResult(cached.episodes, isStale = age > calendarCacheTtlMs)
    }

    suspend fun getCalendarCached(days: Int = 7): TraktCalendarResult = inCalendarScope {
        getCalendarForCurrentScope(days)
    }

    private suspend fun getCalendarForCurrentScope(days: Int): TraktCalendarResult {
        if (!hasConnection()) throw TraktAuthorizationRequiredException()
        val generation = currentAccountGeneration()
        val person = personalScopeProvider()
        val key = calendarKey(authScopeProvider.currentAuthenticatedScope(), days, person)
        val cached = cachedCalendarForCurrentScope(days)
        if (generation != currentAccountGeneration() || person != personalScopeProvider())
            throw kotlinx.coroutines.CancellationException("Calendar owner changed")
        if (cached != null && !cached.isStale) {
            diagnostics.log("cache.hit", mapOf("feature" to "calendar", "stale" to "false"))
            return cached
        }

        return try {
            val episodes = executeWithRefresh { token -> traktClient.getCalendar(token, days) }
            val generationAfterFetch = currentAccountGeneration()
            if (generation != generationAfterFetch || person != personalScopeProvider())
                throw kotlinx.coroutines.CancellationException("Calendar owner changed")
            val entry = CalendarCacheEntry(episodes, clock.now().toEpochMilliseconds())
            calendarCacheMutex.withLock {
                if (generation == generationAfterFetch) {
                    calendarCache[key] = entry
                }
            }
            calendarSnapshots?.write(SavedTraktCalendar(key, days, entry.loadedAtMs, episodes))
            if (generation != currentAccountGeneration() || person != personalScopeProvider())
                throw kotlinx.coroutines.CancellationException("Calendar owner changed")
            TraktCalendarResult(episodes, isStale = false)
        } catch (error: Exception) {
            if (error is TraktAuthorizationRequiredException) throw error
            if (!error.canServeStaleCalendar()) throw error
            val generationAfterError = currentAccountGeneration()
            if (generation != generationAfterError || person != personalScopeProvider()) throw error
            if (cached == null) throw error
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

    private suspend fun <T> inCalendarScope(block: suspend () -> T): T {
        val ids = calendarUserIds ?: return block()
        return kotlinx.coroutines.withContext(com.torve.data.auth.capturePersonalDataScope(ids)) { block() }
    }

    suspend fun resetForAccountChange() {
        accountStateMutex.withLock {
            accountGeneration += 1L
        }
        refreshMutex.withLock {
            refreshFailureBlockedUntilMs.clear()
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
        val scope = personalScopeProvider()
        return executeWithTokenRefresh(
            initial = tokenStore.read() ?: accountTokens()?.also {
                if (scope != personalScopeProvider()) throw kotlinx.coroutines.CancellationException("Profile changed")
                tokenStore.write(it)
            },
            execute = { token ->
                if (scope != personalScopeProvider()) throw kotlinx.coroutines.CancellationException("Profile changed")
                block(token).also {
                    if (scope != personalScopeProvider()) throw kotlinx.coroutines.CancellationException("Profile changed")
                }
            },
            refresh = { token ->
                if (scope != personalScopeProvider()) throw kotlinx.coroutines.CancellationException("Profile changed")
                refreshTokensSingleFlight(token)
            },
            isUnauthorized = ::isUnauthorized,
        )
    }

    private suspend fun refreshTokensSingleFlight(refreshToken: String): TraktTokens {
        val generation = currentAccountGeneration()
        val personalScope = personalScopeProvider()
        val now = clock.now().toEpochMilliseconds()
        if ((refreshFailureBlockedUntilMs[personalScope] ?: 0L) > now) {
            throw TraktAuthorizationRequiredException()
        }
        if (!refreshMutex.tryLock()) {
            diagnostics.log("auth_refresh.joined", emptyMap())
            return refreshMutex.withLock {
                if (personalScope != personalScopeProvider()) throw kotlinx.coroutines.CancellationException("Profile changed")
                if ((refreshFailureBlockedUntilMs[personalScope] ?: 0L) > clock.now().toEpochMilliseconds()) {
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
            val accountCopy = runCatching { accountTokens() }.getOrNull()
            if (accountCopy != null && accountCopy.refreshToken != refreshToken && accountCopy.accessToken.isNotBlank()) {
                tokenStore.write(accountCopy); return accountCopy
            }
            diagnostics.log("auth_refresh.started", emptyMap())
            val refreshed = try { traktClient.refreshToken(refreshToken) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val latest = runCatching { accountTokens() }.getOrNull()
                if (latest != null && latest.refreshToken != refreshToken && latest.accessToken.isNotBlank()) latest else throw e
            }
            if (generation != currentAccountGeneration() || personalScope != personalScopeProvider()) {
                throw kotlinx.coroutines.CancellationException("Account or profile changed")
            }
            val shared = publishTokens(refreshToken, refreshed)
            tokenStore.write(shared)
            return shared
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error !is TraktNetworkException && error !is TraktRateLimitedException && error !is TraktServerException) {
                refreshFailureBlockedUntilMs[personalScope] = clock.now().toEpochMilliseconds() + refreshFailureCooldownMs
            }
            diagnostics.log(
                "auth_refresh.failed",
                mapOf("exception_class" to error::class.simpleName.orEmpty()),
            )
            if (error is TraktNetworkException || error is TraktRateLimitedException || error is TraktServerException) throw error
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

    private fun calendarKey(authScope: String, days: Int, person: String): String =
        "calendar:$person:$authScope:$days"

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
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException || error is TraktNetworkException ||
                error is TraktRateLimitedException || error is TraktServerException) throw error
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
