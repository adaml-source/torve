package com.streamvault.data.progress

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.repository.WatchProgressRepository
import kotlinx.datetime.Clock

class WatchProgressRepositoryImpl(
    private val database: StreamVaultDatabase,
) : WatchProgressRepository {

    override suspend fun getInProgress(limit: Long): List<WatchProgress> {
        return database.streamVaultQueries.getInProgress(limit).executeAsList().map { row ->
            WatchProgress(
                mediaId = row.media_id,
                mediaType = MediaType.fromString(row.media_type),
                title = row.title,
                posterUrl = row.poster_url,
                backdropUrl = row.backdrop_url,
                positionMs = row.position_ms,
                durationMs = row.duration_ms,
                seasonNumber = row.season_number?.toInt(),
                episodeNumber = row.episode_number?.toInt(),
                showTitle = row.show_title,
                updatedAt = row.updated_at,
            )
        }
    }

    override suspend fun getProgress(mediaId: String): WatchProgress? {
        return database.streamVaultQueries.getProgress(mediaId).executeAsOneOrNull()?.let { row ->
            WatchProgress(
                mediaId = row.media_id,
                mediaType = MediaType.fromString(row.media_type),
                title = row.title,
                posterUrl = row.poster_url,
                backdropUrl = row.backdrop_url,
                positionMs = row.position_ms,
                durationMs = row.duration_ms,
                seasonNumber = row.season_number?.toInt(),
                episodeNumber = row.episode_number?.toInt(),
                showTitle = row.show_title,
                updatedAt = row.updated_at,
            )
        }
    }

    override suspend fun saveProgress(progress: WatchProgress) {
        database.streamVaultQueries.upsertProgress(
            media_id = progress.mediaId,
            media_type = when (progress.mediaType) {
                MediaType.MOVIE -> "movie"
                MediaType.SERIES -> "series"
            },
            title = progress.title,
            poster_url = progress.posterUrl,
            backdrop_url = progress.backdropUrl,
            position_ms = progress.positionMs,
            duration_ms = progress.durationMs,
            season_number = progress.seasonNumber?.toLong(),
            episode_number = progress.episodeNumber?.toLong(),
            show_title = progress.showTitle,
            updated_at = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override suspend fun getAllProgress(): List<WatchProgress> {
        return database.streamVaultQueries.getAllProgress().executeAsList().map { row ->
            WatchProgress(
                mediaId = row.media_id,
                mediaType = MediaType.fromString(row.media_type),
                title = row.title,
                posterUrl = row.poster_url,
                backdropUrl = row.backdrop_url,
                positionMs = row.position_ms,
                durationMs = row.duration_ms,
                seasonNumber = row.season_number?.toInt(),
                episodeNumber = row.episode_number?.toInt(),
                showTitle = row.show_title,
                updatedAt = row.updated_at,
            )
        }
    }
}
