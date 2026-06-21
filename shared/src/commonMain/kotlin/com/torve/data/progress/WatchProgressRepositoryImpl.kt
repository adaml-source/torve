package com.torve.data.progress

import com.torve.data.auth.UserIdProvider
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryEpisodeEntry
import com.torve.data.trakt.TraktHistorySeasonEntry
import com.torve.data.trakt.TraktHistoryShow
import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.torve.util.ioDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class WatchProgressRepositoryImpl(
    private val database: TorveDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val tmdbClient: TmdbApiClient,
    private val traktSyncRepo: TraktSyncRepository,
    private val simklClient: SimklClient,
    private val integrationSecretStore: IntegrationSecretStore,
    private val userIdProvider: UserIdProvider,
) : WatchProgressRepository {

    override suspend fun getInProgress(limit: Long): List<WatchProgress> {
        val userId = userIdProvider.currentUserId()
        val rows = database.torveQueries.getInProgress(userId = userId, limit = limit).executeAsList()
        // Return local data immediately — don't block on TMDB calls.
        val result = rows.map { row ->
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
        // Backfill missing poster/backdrop URLs from TMDB asynchronously.
        // This updates the DB so next read will have the URLs.
        val needsEnrichment = rows.filter { it.poster_url == null || it.backdrop_url == null }
        if (needsEnrichment.isNotEmpty()) {
            CoroutineScope(ioDispatcher).launch {
                for (row in needsEnrichment) {
                    val tmdbId = row.media_id.extractTmdbIdOrNull() ?: continue
                    val isMovie = row.media_type == "movie"
                    runCatching {
                        val (poster, backdrop) = if (isMovie) {
                            val d = tmdbClient.getMovieDetail(tmdbId)
                            TmdbMappers.posterUrl(d.posterPath) to TmdbMappers.backdropUrl(d.backdropPath)
                        } else {
                            val d = tmdbClient.getTvDetail(tmdbId)
                            TmdbMappers.posterUrl(d.posterPath) to TmdbMappers.backdropUrl(d.backdropPath)
                        }
                        if (poster != null || backdrop != null) {
                            database.torveQueries.upsertProgress(
                                user_id = row.user_id,
                                media_id = row.media_id,
                                media_type = row.media_type,
                                title = row.title,
                                poster_url = poster ?: row.poster_url,
                                backdrop_url = backdrop ?: row.backdrop_url,
                                position_ms = row.position_ms,
                                duration_ms = row.duration_ms,
                                season_number = row.season_number,
                                episode_number = row.episode_number,
                                show_title = row.show_title,
                                updated_at = row.updated_at,
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    override suspend fun getProgress(mediaId: String): WatchProgress? {
        val userId = userIdProvider.currentUserId()
        return database.torveQueries.getProgress(userId = userId, mediaId = mediaId).executeAsOneOrNull()?.let { row ->
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
        val userId = userIdProvider.currentUserId()
        // Preserve existing poster/backdrop URLs if the new values are null,
        // so a failed TMDB lookup or Trakt sync doesn't overwrite good data.
        val existing = runCatching { database.torveQueries.getProgress(userId = userId, mediaId = progress.mediaId).executeAsOneOrNull() }.getOrNull()
        val posterUrl = progress.posterUrl ?: existing?.poster_url
        val backdropUrl = progress.backdropUrl ?: existing?.backdrop_url
        database.torveQueries.upsertProgress(
            user_id = userId,
            media_id = progress.mediaId,
            media_type = when (progress.mediaType) {
                MediaType.MOVIE -> "movie"
                MediaType.SERIES -> "series"
            },
            title = progress.title,
            poster_url = posterUrl,
            backdrop_url = backdropUrl,
            position_ms = progress.positionMs,
            duration_ms = progress.durationMs,
            season_number = progress.seasonNumber?.toLong(),
            episode_number = progress.episodeNumber?.toLong(),
            show_title = progress.showTitle,
            updated_at = Clock.System.now().toEpochMilliseconds(),
        )

        // Treat near-complete playback as watched; enqueue for eventual Trakt sync.
        // Threshold matches Trakt's "scrobble stop" behavior at 80%+ — we use 85%
        // so a viewer who finishes the episode but skips outro credits still gets
        // marked watched and pushed to Trakt + Simkl.
        val ratio = if (progress.durationMs > 0) {
            progress.positionMs.toDouble() / progress.durationMs.toDouble()
        } else {
            0.0
        }
        if (ratio >= 0.85) {
            val tmdbId = progress.mediaId.extractTmdbIdOrNull() ?: return
            val imdbId = resolveImdbId(progress.mediaId, progress.mediaType, tmdbId)
            runCatching {
                if (
                    progress.mediaType == MediaType.SERIES &&
                    progress.seasonNumber != null &&
                    progress.episodeNumber != null
                ) {
                    traktApi.addToHistory(
                        TraktHistoryBody(
                            shows = listOf(
                                TraktHistoryShow(
                                    ids = TraktIds(tmdb = tmdbId, imdb = imdbId),
                                    seasons = listOf(
                                        TraktHistorySeasonEntry(
                                            number = progress.seasonNumber,
                                            episodes = listOf(TraktHistoryEpisodeEntry(number = progress.episodeNumber)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                } else if (progress.mediaType == MediaType.MOVIE) {
                    traktSyncRepo.enqueueHistoryAdd(
                        tmdbId = tmdbId,
                        mediaType = MediaType.MOVIE,
                        imdbId = imdbId,
                    )
                    traktSyncRepo.flushPendingWrites()
                }
            }.onFailure {
                runCatching {
                    if (
                        progress.mediaType == MediaType.SERIES &&
                        progress.seasonNumber != null &&
                        progress.episodeNumber != null
                    ) {
                        traktSyncRepo.enqueueEpisodeHistoryAdd(
                            tmdbId = tmdbId,
                            imdbId = imdbId,
                            season = progress.seasonNumber,
                            episode = progress.episodeNumber,
                        )
                    } else if (progress.mediaType == MediaType.MOVIE) {
                        traktSyncRepo.enqueueHistoryAdd(
                            tmdbId = tmdbId,
                            mediaType = MediaType.MOVIE,
                            imdbId = imdbId,
                        )
                    }
                }
            }
            runCatching {
                val token = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                if (!token.isNullOrBlank() && progress.mediaType == MediaType.MOVIE) {
                    val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
                    val body = SimklSyncBody(movies = listOf(SimklSyncItem(ids)))
                    simklClient.addToHistory(token, body)
                }
            }
        }
    }

    override suspend fun getAllProgress(): List<WatchProgress> {
        val userId = userIdProvider.currentUserId()
        return database.torveQueries.getAllProgress(userId = userId).executeAsList().map { row ->
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

    override suspend fun deleteProgress(mediaId: String) {
        database.torveQueries.deleteProgressByMediaId(
            userId = userIdProvider.currentUserId(),
            mediaId = mediaId,
        )
    }

    override suspend fun clearAllProgress() {
        database.torveQueries.clearAllProgress(userId = userIdProvider.currentUserId())
    }

    override suspend fun syncFromTrakt() {
        try {
            val playbackItems = traktApi.getPlaybackProgress()
            if (playbackItems.isEmpty()) return

            val userId = userIdProvider.currentUserId()
            val localIds = database.torveQueries.getAllProgress(userId = userId).executeAsList()
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
                database.torveQueries.upsertProgress(
                    user_id = userId,
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

    private suspend fun resolveImdbId(
        mediaId: String,
        mediaType: MediaType,
        tmdbId: Int,
    ): String? {
        mediaId.extractImdbIdOrNull()?.let { return it }
        return runCatching {
            when (mediaType) {
                MediaType.MOVIE -> tmdbClient.getMovieDetail(tmdbId).imdbId
                MediaType.SERIES -> tmdbClient.getTvDetail(tmdbId).externalIds?.imdbId
            }
        }.getOrNull()
    }
}
