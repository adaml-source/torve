package com.torve.data.stats

import com.torve.data.auth.UserIdProvider
import com.torve.data.mdblist.RatingsCacheRepository
import com.torve.db.TorveDatabase
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsEngine
import com.torve.domain.stats.WatchStatsFilters
import com.torve.domain.stats.WatchStatsMetadata
import com.torve.domain.stats.WatchStatsRepository
import com.torve.domain.stats.WatchStatsSummary

class WatchStatsRepositoryImpl(
    private val database: TorveDatabase,
    preferencesRepository: PreferencesRepository,
    private val userIdProvider: UserIdProvider,
    private val engine: WatchStatsEngine = WatchStatsEngine(),
    private val ratingsCacheRepository: RatingsCacheRepository? = null,
) : WatchStatsRepository {
    private val backfill = WatchStatsBackfill(
        database = database,
        preferencesRepository = preferencesRepository,
        currentUserId = { userIdProvider.currentUserId() },
    )

    override suspend fun getSessions(filters: WatchStatsFilters): List<WatchSession> {
        val bundle = loadSessionBundle()
        return bundle.sessions.filter { session ->
            filters.matches(session, bundle.metadataBySessionId[session.id])
        }
    }

    override suspend fun getAggregation(filters: WatchStatsFilters): WatchStatsSummary {
        val bundle = loadSessionBundle()
        return engine.aggregate(
            sessions = bundle.sessions,
            filters = filters,
            metadataBySessionId = bundle.metadataBySessionId,
        )
    }

    override suspend fun upsertSession(session: WatchSession) {
        database.torveQueries.insertOrReplaceWatchSession(session.copy(userId = userIdProvider.currentUserId()))
    }

    override suspend fun updateSessionProgress(
        id: String,
        endedAt: Long?,
        status: WatchSessionStatus,
        durationMs: Long?,
        maxPositionMs: Long,
        countedWatchMs: Long,
        completionPercent: Double,
        runtimeConfidence: RuntimeConfidence,
        updatedAt: Long,
    ) {
        database.torveQueries.updateWatchSessionProgress(
            userId = userIdProvider.currentUserId(),
            id = id,
            endedAt = endedAt,
            status = status.name,
            durationMs = durationMs,
            maxPositionMs = maxPositionMs,
            countedWatchMs = countedWatchMs,
            completionPercent = completionPercent,
            runtimeConfidence = runtimeConfidence.name,
            updatedAt = updatedAt,
        )
    }

    override suspend fun runBackfillIfNeeded() {
        backfill.runIfNeeded()
    }

    override suspend fun clearForCurrentUser() {
        database.torveQueries.clearWatchSessionsForUser(userIdProvider.currentUserId())
    }

    override suspend fun clearForMedia(mediaId: String) {
        database.torveQueries.clearWatchSessionsForUserAndMedia(
            userId = userIdProvider.currentUserId(),
            mediaId = mediaId,
        )
    }

    private suspend fun loadSessionBundle(): SessionBundle {
        runBackfillIfNeeded()
        val userId = userIdProvider.currentUserId()
        val sessions = database.torveQueries.selectWatchSessionsForUser(userId)
            .executeAsList()
            .map { it.toDomain() }
        return SessionBundle(
            sessions = sessions,
            metadataBySessionId = loadLocalMetadata(userId, sessions),
        )
    }

    private fun loadLocalMetadata(
        userId: String,
        sessions: List<WatchSession>,
    ): Map<String, WatchStatsMetadata> {
        if (sessions.isEmpty()) return emptyMap()
        val watchlist = database.torveQueries.getAllWatchlist(userId)
            .executeAsList()
            .map { row ->
                LocalWatchlistMetadata(
                    mediaId = row.media_id,
                    mediaType = MediaType.fromString(row.media_type),
                    tmdbId = row.tmdb_id.toInt(),
                    imdbId = row.imdb_id,
                    rating = row.rating,
                    year = row.year?.toInt(),
                    genres = row.genres,
                )
            }

        val byMediaId = watchlist.associateBy { it.mediaId.normalizedStatsMediaId() }
        val byTmdb = watchlist
            .filter { it.tmdbId > 0 }
            .associateBy { "${it.mediaType.name}:${it.tmdbId}" }
        val byImdb = watchlist
            .mapNotNull { item -> item.imdbId?.takeIf { it.isNotBlank() }?.let { "${item.mediaType.name}:${it.lowercase()}" to item } }
            .toMap()

        return sessions.mapNotNull { session ->
            val watchlistMeta = byMediaId[session.mediaId.normalizedStatsMediaId()]
                ?: session.tmdbId?.let { byTmdb["${session.mediaType.name}:$it"] }
                ?: session.imdbId?.takeIf { it.isNotBlank() }?.let { byImdb["${session.mediaType.name}:${it.lowercase()}"] }
            val ratings = cachedRatingsFor(session, watchlistMeta)
            val metadata = WatchStatsMetadata(
                year = watchlistMeta?.year,
                genres = watchlistMeta?.genres.parseGenres(),
                ratingImdb = ratings?.imdbScore?.toDouble(),
                ratingRt = ratings?.rottenTomatoesScore?.toDouble(),
                ratingTmdb = ratings?.tmdbScore?.toDouble()
                    ?: watchlistMeta?.rating?.takeIf { it > 0.0 },
            )
            metadata.takeIf { it.hasAnyMetadata }?.let { session.id to it }
        }.toMap()
    }

    private fun cachedRatingsFor(
        session: WatchSession,
        watchlistMeta: LocalWatchlistMetadata?,
    ): MediaRatings? {
        val cache = ratingsCacheRepository ?: return null
        val keys = buildList {
            session.tmdbId?.takeIf { it > 0 }?.let { add("${session.mediaType.name}:$it") }
            session.imdbId?.takeIf { it.isNotBlank() }?.let { add("${session.mediaType.name}:$it") }
            watchlistMeta?.tmdbId?.takeIf { it > 0 }?.let { add("${watchlistMeta.mediaType.name}:$it") }
            watchlistMeta?.imdbId?.takeIf { it.isNotBlank() }?.let { add("${watchlistMeta.mediaType.name}:$it") }
        }.distinct()
        return keys.firstNotNullOfOrNull { key -> cache.getCached(key) }
    }

    private data class SessionBundle(
        val sessions: List<WatchSession>,
        val metadataBySessionId: Map<String, WatchStatsMetadata>,
    )

    private data class LocalWatchlistMetadata(
        val mediaId: String,
        val mediaType: MediaType,
        val tmdbId: Int,
        val imdbId: String?,
        val rating: Double?,
        val year: Int?,
        val genres: String?,
    )
}

private fun String.normalizedStatsMediaId(): String =
    trim()
        .lowercase()
        .removePrefix("tmdb:")
        .removePrefix("movie:")
        .removePrefix("tv:")
        .removePrefix("series:")

private fun String?.parseGenres(): List<String> =
    orEmpty()
        .split(',')
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .distinctBy { it.lowercase() }
