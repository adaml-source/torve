package com.torve.data.stats

import com.torve.domain.model.MediaType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WATCHED_THRESHOLD_PERCENT
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsRepository
import com.torve.domain.stats.classifyPlaybackSession
import com.torve.domain.stats.countedWatchTimeForStatus

data class WatchSessionMediaIdentity(
    val mediaId: String,
    val mediaType: MediaType,
    val title: String,
    val showId: String? = null,
    val showTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
)

class WatchSessionRecorder(
    private val watchStatsRepository: WatchStatsRepository,
    private val currentUserId: () -> String,
) {
    suspend fun startPlayerSession(
        identity: WatchSessionMediaIdentity,
        startedAt: Long,
    ): String {
        val userId = currentUserId()
        val id = buildSessionId(
            userId = userId,
            source = WatchSessionSource.TORVE_PLAYER,
            identity = identity,
            eventDiscriminator = startedAt.toString(),
        )
        watchStatsRepository.upsertSession(
            identity.toSession(
                id = id,
                userId = userId,
                startedAt = startedAt,
                endedAt = null,
                source = WatchSessionSource.TORVE_PLAYER,
                status = WatchSessionStatus.STARTED,
                durationMs = null,
                maxPositionMs = 0L,
                countedWatchMs = 0L,
                completionPercent = 0.0,
                runtimeConfidence = RuntimeConfidence.UNKNOWN,
                createdAt = startedAt,
                updatedAt = startedAt,
            ),
        )
        return id
    }

    suspend fun updatePlayerSessionProgress(
        sessionId: String?,
        positionMs: Long,
        durationMs: Long?,
        updatedAt: Long,
    ) {
        if (sessionId.isNullOrBlank()) return
        val duration = durationMs?.takeIf { it > 0L }
        val maxPosition = positionMs.coerceAtLeast(0L)
        val completion = completionPercent(maxPosition, duration)
        val progressStatus = when {
            maxPosition <= 0L -> WatchSessionStatus.STARTED
            duration != null && completion >= WATCHED_THRESHOLD_PERCENT -> WatchSessionStatus.COMPLETED
            maxPosition >= 2 * 60 * 1000L -> WatchSessionStatus.PARTIAL
            else -> WatchSessionStatus.STARTED
        }
        watchStatsRepository.updateSessionProgress(
            id = sessionId,
            endedAt = null,
            status = progressStatus,
            durationMs = duration,
            maxPositionMs = maxPosition,
            countedWatchMs = 0L,
            completionPercent = completion,
            runtimeConfidence = if (duration != null && maxPosition > 0L) {
                RuntimeConfidence.MEASURED
            } else {
                RuntimeConfidence.UNKNOWN
            },
            updatedAt = updatedAt,
        )
    }

    suspend fun finishPlayerSession(
        sessionId: String?,
        positionMs: Long,
        durationMs: Long?,
        endedAt: Long,
    ) {
        if (sessionId.isNullOrBlank()) return
        val duration = durationMs?.takeIf { it > 0L }
        val maxPosition = positionMs.coerceAtLeast(0L)
        val status = classifyPlaybackSession(
            maxPositionMs = maxPosition,
            durationMs = duration,
            thresholdPercent = WATCHED_THRESHOLD_PERCENT,
        )
        watchStatsRepository.updateSessionProgress(
            id = sessionId,
            endedAt = endedAt,
            status = status,
            durationMs = duration,
            maxPositionMs = maxPosition,
            countedWatchMs = countedWatchTimeForStatus(status, maxPosition, duration),
            completionPercent = completionPercent(maxPosition, duration),
            runtimeConfidence = if (duration != null && maxPosition > 0L) {
                RuntimeConfidence.MEASURED
            } else {
                RuntimeConfidence.UNKNOWN
            },
            updatedAt = endedAt,
        )
    }

    suspend fun recordManualCompleted(
        identity: WatchSessionMediaIdentity,
        eventAt: Long,
        runtimeMs: Long?,
    ): String {
        return recordCompletedSession(
            identity = identity,
            source = WatchSessionSource.MANUAL,
            status = WatchSessionStatus.MANUAL_COMPLETED,
            eventAt = eventAt,
            eventDiscriminator = eventAt.toString(),
            runtimeMs = runtimeMs,
        )
    }

    suspend fun recordImportedCompleted(
        identity: WatchSessionMediaIdentity,
        source: WatchSessionSource,
        eventAt: Long,
        importDiscriminator: String,
        runtimeMs: Long?,
    ): String {
        require(source == WatchSessionSource.TRAKT || source == WatchSessionSource.SIMKL) {
            "Imported sessions must use TRAKT or SIMKL source"
        }
        return recordCompletedSession(
            identity = identity,
            source = source,
            status = WatchSessionStatus.IMPORTED_COMPLETED,
            eventAt = eventAt,
            eventDiscriminator = importDiscriminator,
            runtimeMs = runtimeMs,
        )
    }

    private suspend fun recordCompletedSession(
        identity: WatchSessionMediaIdentity,
        source: WatchSessionSource,
        status: WatchSessionStatus,
        eventAt: Long,
        eventDiscriminator: String,
        runtimeMs: Long?,
    ): String {
        val userId = currentUserId()
        val duration = runtimeMs?.takeIf { it > 0L }
        val id = buildSessionId(userId, source, identity, eventDiscriminator)
        watchStatsRepository.upsertSession(
            identity.toSession(
                id = id,
                userId = userId,
                startedAt = eventAt,
                endedAt = eventAt,
                source = source,
                status = status,
                durationMs = duration,
                maxPositionMs = duration ?: 0L,
                countedWatchMs = duration ?: 0L,
                completionPercent = if (duration != null) 1.0 else 0.0,
                runtimeConfidence = if (duration != null) RuntimeConfidence.ESTIMATED else RuntimeConfidence.UNKNOWN,
                createdAt = eventAt,
                updatedAt = eventAt,
            ),
        )
        return id
    }

    private fun WatchSessionMediaIdentity.toSession(
        id: String,
        userId: String,
        startedAt: Long,
        endedAt: Long?,
        source: WatchSessionSource,
        status: WatchSessionStatus,
        durationMs: Long?,
        maxPositionMs: Long,
        countedWatchMs: Long,
        completionPercent: Double,
        runtimeConfidence: RuntimeConfidence,
        createdAt: Long,
        updatedAt: Long,
    ): WatchSession =
        WatchSession(
            id = id,
            userId = userId,
            mediaId = mediaId,
            mediaType = mediaType,
            title = title,
            showId = showId,
            showTitle = showTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            tmdbId = tmdbId ?: mediaId.extractTmdbIdOrNull(),
            imdbId = imdbId ?: mediaId.extractImdbIdOrNull(),
            startedAt = startedAt,
            endedAt = endedAt,
            source = source,
            status = status,
            durationMs = durationMs,
            maxPositionMs = maxPositionMs,
            countedWatchMs = countedWatchMs,
            completionPercent = completionPercent,
            runtimeConfidence = runtimeConfidence,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun buildSessionId(
        userId: String,
        source: WatchSessionSource,
        identity: WatchSessionMediaIdentity,
        eventDiscriminator: String,
    ): String {
        val mediaKey = identity.showId ?: identity.mediaId
        return listOf(
            "watch_session",
            userId,
            source.name,
            mediaKey,
            "s${identity.seasonNumber ?: -1}",
            "e${identity.episodeNumber ?: -1}",
            eventDiscriminator,
        ).joinToString(":") { it.sanitizeIdPart() }
    }

    private fun completionPercent(positionMs: Long, durationMs: Long?): Double {
        val duration = durationMs?.takeIf { it > 0L } ?: return 0.0
        return (positionMs.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun String.sanitizeIdPart(): String =
        trim().ifEmpty { "_" }.replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
}
