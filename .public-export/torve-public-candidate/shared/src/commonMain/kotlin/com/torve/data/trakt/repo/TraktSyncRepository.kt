package com.torve.data.trakt.repo

import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryEpisodeEntry
import com.torve.data.trakt.TraktHistoryMovie
import com.torve.data.trakt.TraktHistorySeasonEntry
import com.torve.data.trakt.TraktHistoryShow
import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.TraktRatingMovie
import com.torve.data.trakt.TraktRatingShow
import com.torve.data.trakt.TraktRatingsBody
import com.torve.data.trakt.TraktRemoveHistoryBody
import com.torve.data.trakt.TraktWatchlistBody
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.db.mapRatingResponseToCache
import com.torve.db.TorveDatabase
import com.torve.domain.model.MediaType
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

interface TraktSyncRepository {
    suspend fun syncRatingsFromTrakt()
    suspend fun getUserRating(tmdbId: Int, mediaType: MediaType): Int?
    suspend fun setUserRating(tmdbId: Int, mediaType: MediaType, imdbId: String?, rating: Int?)
    suspend fun enqueueWatchlistAdd(tmdbId: Int, mediaType: MediaType, imdbId: String?)
    suspend fun enqueueWatchlistRemove(tmdbId: Int, mediaType: MediaType, imdbId: String?)
    suspend fun enqueueHistoryAdd(tmdbId: Int, mediaType: MediaType, imdbId: String?)
    suspend fun enqueueHistoryRemove(tmdbId: Int, mediaType: MediaType, imdbId: String?)
    suspend fun enqueueEpisodeHistoryAdd(tmdbId: Int, imdbId: String?, season: Int, episode: Int)
    suspend fun enqueueEpisodeHistoryRemove(tmdbId: Int, imdbId: String?, season: Int, episode: Int)
    suspend fun flushPendingWrites(maxItems: Int = 50): Int
    suspend fun clearLocalData()
}

class TraktSyncRepositoryImpl(
    private val database: TorveDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val tokenStore: TraktTokenStore,
    private val json: Json,
) : TraktSyncRepository {

    override suspend fun syncRatingsFromTrakt() {
        val token = tokenStore.accessToken() ?: return
        if (token.isBlank()) return

        val ratings = traktApi.getRatings(limit = 200)
        database.torveQueries.clearTraktRatings()

        val now = Clock.System.now().toEpochMilliseconds()
        ratings.forEach { item ->
            val cached = mapRatingResponseToCache(item, now) ?: return@forEach
            database.torveQueries.upsertTraktRating(
                media_key = cached.mediaKey,
                tmdb_id = cached.tmdbId.toLong(),
                media_type = cached.mediaType,
                rating = cached.rating.toLong(),
                rated_at = cached.ratedAt,
                updated_at = cached.updatedAt,
            )
        }

        database.torveQueries.upsertTraktSyncState(
            domain = DOMAIN_RATINGS,
            last_sync_at = now,
            cursor = null,
        )
    }

    override suspend fun getUserRating(tmdbId: Int, mediaType: MediaType): Int? {
        val row = database.torveQueries.getTraktRatingByTmdb(
            tmdb_id = tmdbId.toLong(),
            media_type = mediaType.toStorageType(),
        ).executeAsOneOrNull() ?: return null
        return row.rating.toInt()
    }

    override suspend fun setUserRating(tmdbId: Int, mediaType: MediaType, imdbId: String?, rating: Int?) {
        val normalized = rating?.coerceIn(1, 10)
        val mediaTypeStorage = mediaType.toStorageType()
        val key = toMediaKey(tmdbId, mediaTypeStorage)

        if (normalized == null) {
            database.torveQueries.removeTraktRating(key)
        } else {
            val now = Clock.System.now().toEpochMilliseconds()
            database.torveQueries.upsertTraktRating(
                media_key = key,
                tmdb_id = tmdbId.toLong(),
                media_type = mediaTypeStorage,
                rating = normalized.toLong(),
                rated_at = now,
                updated_at = now,
            )
        }

        // No token means disconnected; keep local cache only.
        if (tokenStore.accessToken().isNullOrBlank()) return

        try {
            if (normalized == null) {
                traktApi.removeRatings(buildRatingsBody(tmdbId, mediaType, imdbId, 1))
            } else {
                traktApi.addRatings(buildRatingsBody(tmdbId, mediaType, imdbId, normalized))
            }
        } catch (_: Exception) {
            enqueue(
                if (normalized == null) ACTION_RATING_REMOVE else ACTION_RATING_SET,
                TraktQueuePayload(
                    tmdbId = tmdbId,
                    mediaType = mediaTypeStorage,
                    imdbId = imdbId,
                    rating = normalized,
                ),
            )
        }
    }

    override suspend fun enqueueWatchlistAdd(tmdbId: Int, mediaType: MediaType, imdbId: String?) {
        enqueue(
            ACTION_WATCHLIST_ADD,
            TraktQueuePayload(tmdbId = tmdbId, mediaType = mediaType.toStorageType(), imdbId = imdbId),
        )
    }

    override suspend fun enqueueWatchlistRemove(tmdbId: Int, mediaType: MediaType, imdbId: String?) {
        enqueue(
            ACTION_WATCHLIST_REMOVE,
            TraktQueuePayload(tmdbId = tmdbId, mediaType = mediaType.toStorageType(), imdbId = imdbId),
        )
    }

    override suspend fun enqueueHistoryAdd(tmdbId: Int, mediaType: MediaType, imdbId: String?) {
        enqueue(
            ACTION_HISTORY_ADD,
            TraktQueuePayload(tmdbId = tmdbId, mediaType = mediaType.toStorageType(), imdbId = imdbId),
        )
    }

    override suspend fun enqueueHistoryRemove(tmdbId: Int, mediaType: MediaType, imdbId: String?) {
        enqueue(
            ACTION_HISTORY_REMOVE,
            TraktQueuePayload(tmdbId = tmdbId, mediaType = mediaType.toStorageType(), imdbId = imdbId),
        )
    }

    override suspend fun enqueueEpisodeHistoryAdd(tmdbId: Int, imdbId: String?, season: Int, episode: Int) {
        enqueue(
            ACTION_HISTORY_ADD,
            TraktQueuePayload(
                tmdbId = tmdbId,
                mediaType = MediaType.SERIES.toStorageType(),
                imdbId = imdbId,
                seasonNumber = season,
                episodeNumber = episode,
            ),
        )
    }

    override suspend fun enqueueEpisodeHistoryRemove(tmdbId: Int, imdbId: String?, season: Int, episode: Int) {
        enqueue(
            ACTION_HISTORY_REMOVE,
            TraktQueuePayload(
                tmdbId = tmdbId,
                mediaType = MediaType.SERIES.toStorageType(),
                imdbId = imdbId,
                seasonNumber = season,
                episodeNumber = episode,
            ),
        )
    }

    override suspend fun flushPendingWrites(maxItems: Int): Int {
        if (tokenStore.accessToken().isNullOrBlank()) return 0
        val now = Clock.System.now().toEpochMilliseconds()
        val items = database.torveQueries.getPendingTraktQueue(now, maxItems.toLong()).executeAsList()
        var successCount = 0
        for (item in items) {
            val payload = runCatching {
                json.decodeFromString<TraktQueuePayload>(item.payload_json)
            }.getOrNull()
            if (payload == null) {
                database.torveQueries.deleteTraktQueueItem(item.id)
                continue
            }

            try {
                when (item.action_type) {
                    ACTION_WATCHLIST_ADD -> traktApi.addToWatchlist(buildWatchlistBody(payload))
                    ACTION_WATCHLIST_REMOVE -> traktApi.removeFromWatchlist(buildWatchlistBody(payload))
                    ACTION_HISTORY_ADD -> traktApi.addToHistory(buildHistoryBody(payload))
                    ACTION_HISTORY_REMOVE -> traktApi.removeFromHistory(buildRemoveHistoryBody(payload))
                    ACTION_RATING_SET -> {
                        val rating = payload.rating ?: 1
                        traktApi.addRatings(buildRatingsBody(payload.tmdbId, payload.toMediaType(), payload.imdbId, rating))
                    }
                    ACTION_RATING_REMOVE -> {
                        traktApi.removeRatings(buildRatingsBody(payload.tmdbId, payload.toMediaType(), payload.imdbId, 1))
                    }
                }
                database.torveQueries.deleteTraktQueueItem(item.id)
                successCount += 1
            } catch (e: Exception) {
                val nextAttempts = item.attempts + 1
                val backoffMs = minOf(300_000L, 1000L * (1L shl minOf(nextAttempts.toInt(), 8)))
                database.torveQueries.updateTraktQueueAttempt(
                    attempts = nextAttempts,
                    last_error = (e.message ?: "sync_failed").take(200),
                    next_retry_at = Clock.System.now().toEpochMilliseconds() + backoffMs,
                    id = item.id,
                )
            }
        }
        return successCount
    }

    override suspend fun clearLocalData() {
        database.torveQueries.clearTraktRatings()
        database.torveQueries.clearTraktSyncState()
        database.torveQueries.clearTraktQueue()
    }

    private suspend fun enqueue(actionType: String, payload: TraktQueuePayload) {
        val now = Clock.System.now().toEpochMilliseconds()
        val id = "trakt_q_${now}_${Random.nextInt(1000, 9999)}"
        database.torveQueries.insertTraktQueueItem(
            id = id,
            action_type = actionType,
            payload_json = json.encodeToString(payload),
            created_at = now,
            attempts = 0,
            last_error = null,
        )
    }

    private fun buildWatchlistBody(payload: TraktQueuePayload): TraktWatchlistBody {
        val ids = TraktIds(tmdb = payload.tmdbId, imdb = payload.imdbId)
        return if (payload.mediaType == "movie") {
            TraktWatchlistBody(movies = listOf(TraktHistoryMovie(ids)))
        } else {
            TraktWatchlistBody(shows = listOf(TraktHistoryShow(ids)))
        }
    }

    private fun buildHistoryBody(payload: TraktQueuePayload): TraktHistoryBody {
        val ids = TraktIds(tmdb = payload.tmdbId, imdb = payload.imdbId)
        return if (payload.mediaType == "movie") {
            TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids)))
        } else if (payload.seasonNumber != null && payload.episodeNumber != null) {
            TraktHistoryBody(shows = listOf(episodeScopedShow(ids, payload.seasonNumber, payload.episodeNumber)))
        } else {
            TraktHistoryBody(shows = listOf(TraktHistoryShow(ids)))
        }
    }

    private fun buildRemoveHistoryBody(payload: TraktQueuePayload): TraktRemoveHistoryBody {
        val ids = TraktIds(tmdb = payload.tmdbId, imdb = payload.imdbId)
        return if (payload.mediaType == "movie") {
            TraktRemoveHistoryBody(movies = listOf(TraktHistoryMovie(ids)))
        } else if (payload.seasonNumber != null && payload.episodeNumber != null) {
            TraktRemoveHistoryBody(shows = listOf(episodeScopedShow(ids, payload.seasonNumber, payload.episodeNumber)))
        } else {
            TraktRemoveHistoryBody(shows = listOf(TraktHistoryShow(ids)))
        }
    }

    private fun episodeScopedShow(ids: TraktIds, season: Int, episode: Int): TraktHistoryShow =
        TraktHistoryShow(
            ids = ids,
            seasons = listOf(
                TraktHistorySeasonEntry(
                    number = season,
                    episodes = listOf(TraktHistoryEpisodeEntry(number = episode)),
                ),
            ),
        )

    private fun buildRatingsBody(
        tmdbId: Int,
        mediaType: MediaType,
        imdbId: String?,
        rating: Int,
    ): TraktRatingsBody {
        val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
        return if (mediaType == MediaType.MOVIE) {
            TraktRatingsBody(movies = listOf(TraktRatingMovie(rating = rating, ids = ids)))
        } else {
            TraktRatingsBody(shows = listOf(TraktRatingShow(rating = rating, ids = ids)))
        }
    }

    private fun toMediaKey(tmdbId: Int, mediaType: String): String = "$mediaType:$tmdbId"

    private fun MediaType.toStorageType(): String = if (this == MediaType.MOVIE) "movie" else "series"

    private fun TraktQueuePayload.toMediaType(): MediaType =
        if (mediaType == "movie") MediaType.MOVIE else MediaType.SERIES

    private companion object {
        const val DOMAIN_RATINGS = "ratings"

        const val ACTION_WATCHLIST_ADD = "watchlist_add"
        const val ACTION_WATCHLIST_REMOVE = "watchlist_remove"
        const val ACTION_HISTORY_ADD = "history_add"
        const val ACTION_HISTORY_REMOVE = "history_remove"
        const val ACTION_RATING_SET = "rating_set"
        const val ACTION_RATING_REMOVE = "rating_remove"
    }
}

@Serializable
data class TraktQueuePayload(
    val tmdbId: Int,
    val mediaType: String,
    val imdbId: String? = null,
    val rating: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class WatchlistMergePlan(
    val toAdd: Set<String>,
    val toRemove: Set<String>,
)

fun deriveWatchlistMerge(localIds: Set<String>, remoteIds: Set<String>): WatchlistMergePlan {
    return WatchlistMergePlan(
        toAdd = remoteIds - localIds,
        // Keep local-only items; they are pushed explicitly via user actions/sync queue.
        toRemove = emptySet(),
    )
}
