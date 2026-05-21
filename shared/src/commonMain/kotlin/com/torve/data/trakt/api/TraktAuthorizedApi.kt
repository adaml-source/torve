package com.torve.data.trakt.api

import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryResponse
import com.torve.data.trakt.TraktPlaybackResponse
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.TraktRemoveHistoryBody
import com.torve.data.trakt.TraktRatingsBody
import com.torve.data.trakt.TraktRatingResponse
import com.torve.data.trakt.TraktWatchlistBody
import com.torve.data.trakt.TraktWatchlistItemResponse
import com.torve.data.trakt.TraktTokens
import com.torve.data.trakt.auth.TraktTokenStore

class TraktAuthorizedApi(
    private val traktClient: TraktClient,
    private val tokenStore: TraktTokenStore,
) {
    suspend fun hasConnection(): Boolean =
        tokenStore.read()?.accessToken?.isNotBlank() == true

    suspend fun getWatchlist(): List<TraktWatchlistItemResponse> =
        executeWithRefresh { token -> traktClient.getWatchlist(token) }

    suspend fun getHistory(limit: Int = 100): List<TraktHistoryResponse> =
        executeWithRefresh { token -> traktClient.getHistory(token, limit) }

    suspend fun getPlaybackProgress(): List<TraktPlaybackResponse> =
        executeWithRefresh { token -> traktClient.getPlaybackProgress(token) }

    suspend fun getCalendar(days: Int = 7): List<TraktCalendarEpisode> =
        executeWithRefresh { token -> traktClient.getCalendar(token, days) }

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
            refresh = ::refreshTokens,
            isUnauthorized = ::isUnauthorized,
        )
    }

    private suspend fun refreshTokens(refreshToken: String): com.torve.data.trakt.TraktTokens {
        val refreshed = traktClient.refreshToken(refreshToken)
        tokenStore.write(refreshed)
        return refreshed
    }

    private fun isUnauthorized(error: Throwable): Boolean {
        val message = error.message ?: return false
        return "401" in message ||
            "Unauthorized" in message ||
            "authentication required" in message ||
            "invalid_grant" in message ||
            "revoked" in message
    }
}

class TraktAuthorizationRequiredException : IllegalStateException("Trakt not connected")

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
