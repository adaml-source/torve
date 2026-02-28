package com.streamvault.data.progress

import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.data.metadata.TmdbMappers
import com.streamvault.data.trakt.api.TraktAuthorizedApi
import com.streamvault.data.trakt.repo.TraktSyncRepository
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.repository.WatchProgressRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class WatchProgressRepositoryImpl(
    private val database: StreamVaultDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val tmdbClient: TmdbApiClient,
    private val traktSyncRepo: TraktSyncRepository,
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

        // Treat near-complete playback as watched; enqueue for eventual Trakt sync.
        val ratio = if (progress.durationMs > 0) {
            progress.positionMs.toDouble() / progress.durationMs.toDouble()
        } else {
            0.0
        }
        if (ratio >= 0.9) {
            val tmdbId = progress.mediaId.toIntOrNull() ?: return
            runCatching {
                traktSyncRepo.enqueueHistoryAdd(
                    tmdbId = tmdbId,
                    mediaType = progress.mediaType,
                    imdbId = null,
                )
            }
        }
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

    override suspend fun clearAllProgress() {
        database.streamVaultQueries.clearAllProgress()
    }

    override suspend fun syncFromTrakt() {
        try {
            val playbackItems = traktApi.getPlaybackProgress()
            if (playbackItems.isEmpty()) return

            val localIds = database.streamVaultQueries.getAllProgress().executeAsList()
                .map { it.media_id }
                .toSet()

            for (item in playbackItems) {
                val media = if (item.type == "movie") item.movie else item.show
                val ids = media?.ids ?: continue
                val tmdbId = ids.tmdb ?: continue
                val mediaId = tmdbId.toString()

                if (mediaId in localIds) continue

                // Estimate position/duration from Trakt progress percentage
                // Use a standard duration estimate (120min for movies, 45min for episodes)
                val isMovie = item.type == "movie"
                val estimatedDurationMs = if (isMovie) 120L * 60 * 1000 else 45L * 60 * 1000
                val positionMs = (item.progress / 100.0 * estimatedDurationMs).toLong()

                val updatedAt = try {
                    Instant.parse(item.pausedAt).toEpochMilliseconds()
                } catch (_: Exception) {
                    Clock.System.now().toEpochMilliseconds()
                }

                // Fetch poster from TMDB
                var posterUrl: String? = null
                var backdropUrl: String? = null
                try {
                    if (isMovie) {
                        val detail = tmdbClient.getMovieDetail(tmdbId)
                        posterUrl = TmdbMappers.posterUrl(detail.posterPath)
                        backdropUrl = TmdbMappers.backdropUrl(detail.backdropPath)
                    } else {
                        val detail = tmdbClient.getTvDetail(tmdbId)
                        posterUrl = TmdbMappers.posterUrl(detail.posterPath)
                        backdropUrl = TmdbMappers.backdropUrl(detail.backdropPath)
                    }
                } catch (_: Exception) { /* non-critical */ }

                val mediaType = if (isMovie) "movie" else "series"
                database.streamVaultQueries.upsertProgress(
                    media_id = mediaId,
                    media_type = mediaType,
                    title = media.title,
                    poster_url = posterUrl,
                    backdrop_url = backdropUrl,
                    position_ms = positionMs,
                    duration_ms = estimatedDurationMs,
                    season_number = item.episode?.season?.toLong(),
                    episode_number = item.episode?.number?.toLong(),
                    show_title = if (!isMovie) media.title else null,
                    updated_at = updatedAt,
                )
            }
        } catch (_: Exception) {
            // Non-critical — don't block UI
        }
    }
}
