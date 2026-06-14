package com.torve.data.stats

import com.torve.db.TorveQueries
import com.torve.db.Watch_session
import com.torve.domain.model.MediaType
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus

internal fun TorveQueries.insertOrReplaceWatchSession(session: WatchSession) {
    insertOrReplaceWatchSession(
        id = session.id,
        user_id = session.userId,
        media_id = session.mediaId,
        media_type = session.mediaType.name.lowercase(),
        title = session.title,
        show_id = session.showId,
        show_title = session.showTitle,
        season_number = session.seasonNumber?.toLong(),
        episode_number = session.episodeNumber?.toLong(),
        poster_url = session.posterUrl,
        backdrop_url = session.backdropUrl,
        tmdb_id = session.tmdbId?.toLong(),
        imdb_id = session.imdbId,
        started_at = session.startedAt,
        ended_at = session.endedAt,
        source = session.source.name,
        status = session.status.name,
        duration_ms = session.durationMs,
        max_position_ms = session.maxPositionMs,
        counted_watch_ms = session.countedWatchMs,
        completion_percent = session.completionPercent,
        watched_threshold_percent = session.watchedThresholdPercent,
        runtime_confidence = session.runtimeConfidence.name,
        created_at = session.createdAt,
        updated_at = session.updatedAt,
    )
}

internal fun Watch_session.toDomain(): WatchSession =
    WatchSession(
        id = id,
        userId = user_id,
        mediaId = media_id,
        mediaType = MediaType.fromString(media_type),
        title = title,
        showId = show_id,
        showTitle = show_title,
        seasonNumber = season_number?.toInt(),
        episodeNumber = episode_number?.toInt(),
        posterUrl = poster_url,
        backdropUrl = backdrop_url,
        tmdbId = tmdb_id?.toInt(),
        imdbId = imdb_id,
        startedAt = started_at,
        endedAt = ended_at,
        source = runCatching { WatchSessionSource.valueOf(source) }.getOrDefault(WatchSessionSource.MIGRATED_HISTORY),
        status = runCatching { WatchSessionStatus.valueOf(status) }.getOrDefault(WatchSessionStatus.STARTED),
        durationMs = duration_ms,
        maxPositionMs = max_position_ms,
        countedWatchMs = counted_watch_ms,
        completionPercent = completion_percent,
        watchedThresholdPercent = watched_threshold_percent,
        runtimeConfidence = runCatching { RuntimeConfidence.valueOf(runtime_confidence) }.getOrDefault(RuntimeConfidence.UNKNOWN),
        createdAt = created_at,
        updatedAt = updated_at,
    )
