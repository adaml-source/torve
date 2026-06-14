package com.torve.data.stats

import com.torve.db.TorveDatabase
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WATCHED_THRESHOLD_PERCENT
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.classifyPlaybackSession
import com.torve.domain.stats.countedWatchTimeForStatus

class WatchStatsBackfill(
    private val database: TorveDatabase,
    private val preferencesRepository: PreferencesRepository,
    private val currentUserId: () -> String,
) {
    companion object {
        const val BACKFILL_MARKER_KEY = "watch_stats_backfill_v1_signature"
        private const val MANUAL_PLACEHOLDER_POSITION_MS = 900_000L
        private const val MANUAL_PLACEHOLDER_DURATION_MS = 1_000_000L
    }

    suspend fun runIfNeeded() {
        val userId = currentUserId()
        val historyRows = database.torveQueries.getAllHistory(userId).executeAsList()
        val progressRows = database.torveQueries.getAllProgress(userId).executeAsList()
        val signature = buildSignature(
            historySize = historyRows.size,
            historyMaxWatchedAt = historyRows.maxOfOrNull { it.watched_at } ?: 0L,
            progressSize = progressRows.size,
            progressMaxUpdatedAt = progressRows.maxOfOrNull { it.updated_at } ?: 0L,
        )
        if (preferencesRepository.getString(BACKFILL_MARKER_KEY) == signature) return

        database.torveQueries.transaction {
            historyRows
                .filter { it.duration_watched_ms > 0L }
                .forEach { row ->
                    database.torveQueries.insertOrReplaceWatchSession(row.toMigratedHistorySession(userId))
                }
            progressRows
                .filter { it.position_ms > 0L || it.duration_ms > 0L }
                .forEach { row ->
                    database.torveQueries.insertOrReplaceWatchSession(row.toMigratedProgressSession(userId))
                }
        }
        preferencesRepository.setString(BACKFILL_MARKER_KEY, signature)
    }

    private fun buildSignature(
        historySize: Int,
        historyMaxWatchedAt: Long,
        progressSize: Int,
        progressMaxUpdatedAt: Long,
    ): String = "h:$historySize:$historyMaxWatchedAt|p:$progressSize:$progressMaxUpdatedAt"

    private fun com.torve.db.Watch_history.toMigratedHistorySession(userId: String): WatchSession {
        val mediaType = media_type.toWatchMediaType()
        return WatchSession(
            id = "migrated_history:$userId:$id",
            userId = userId,
            mediaId = media_id,
            mediaType = mediaType,
            title = title,
            showId = if (mediaType == MediaType.SERIES) media_id else null,
            showTitle = show_title,
            seasonNumber = season_number?.toInt(),
            episodeNumber = episode_number?.toInt(),
            posterUrl = poster_url,
            backdropUrl = backdrop_url,
            tmdbId = media_id.extractTmdbIdOrNull(),
            imdbId = media_id.extractImdbIdOrNull(),
            startedAt = watched_at,
            endedAt = watched_at,
            source = WatchSessionSource.MIGRATED_HISTORY,
            status = WatchSessionStatus.PARTIAL,
            durationMs = null,
            maxPositionMs = duration_watched_ms,
            countedWatchMs = duration_watched_ms,
            completionPercent = 0.0,
            runtimeConfidence = RuntimeConfidence.ESTIMATED,
            createdAt = watched_at,
            updatedAt = watched_at,
        )
    }

    private fun com.torve.db.Watch_progress.toMigratedProgressSession(userId: String): WatchSession {
        val mediaType = MediaType.fromString(media_type)
        val isLegacyManualPlaceholder =
            position_ms == MANUAL_PLACEHOLDER_POSITION_MS &&
                duration_ms == MANUAL_PLACEHOLDER_DURATION_MS
        val rawStatus = if (isLegacyManualPlaceholder) {
            WatchSessionStatus.MANUAL_COMPLETED
        } else {
            classifyPlaybackSession(
                maxPositionMs = position_ms,
                durationMs = duration_ms.takeIf { it > 0L },
                thresholdPercent = WATCHED_THRESHOLD_PERCENT,
            )
        }
        val confidence = when {
            isLegacyManualPlaceholder -> RuntimeConfidence.UNKNOWN
            rawStatus == WatchSessionStatus.ABANDONED -> RuntimeConfidence.UNKNOWN
            duration_ms > 0L || position_ms > 0L -> RuntimeConfidence.ESTIMATED
            else -> RuntimeConfidence.UNKNOWN
        }
        val counted = if (isLegacyManualPlaceholder) {
            0L
        } else {
            countedWatchTimeForStatus(
                status = rawStatus,
                maxPositionMs = position_ms,
                durationMs = duration_ms.takeIf { it > 0L },
            )
        }
        val completion = if (duration_ms > 0L) {
            (position_ms.toDouble() / duration_ms.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return WatchSession(
            id = "migrated_progress:$userId:$media_id:${season_number ?: -1}:${episode_number ?: -1}",
            userId = userId,
            mediaId = media_id,
            mediaType = mediaType,
            title = title,
            showId = if (mediaType == MediaType.SERIES) media_id else null,
            showTitle = show_title,
            seasonNumber = season_number?.toInt(),
            episodeNumber = episode_number?.toInt(),
            posterUrl = poster_url,
            backdropUrl = backdrop_url,
            tmdbId = media_id.extractTmdbIdOrNull(),
            imdbId = media_id.extractImdbIdOrNull(),
            startedAt = updated_at,
            endedAt = updated_at,
            source = WatchSessionSource.MIGRATED_HISTORY,
            status = rawStatus,
            durationMs = duration_ms.takeIf { it > 0L },
            maxPositionMs = position_ms,
            countedWatchMs = counted,
            completionPercent = completion,
            runtimeConfidence = confidence,
            createdAt = updated_at,
            updatedAt = updated_at,
        )
    }

    private fun String.toWatchMediaType(): MediaType = when (lowercase()) {
        "series", "tv" -> MediaType.SERIES
        else -> MediaType.MOVIE
    }
}
