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
import com.torve.data.trakt.shouldApplyRemoteProgress
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.player.PlaybackViewingCoverage
import com.torve.domain.player.ViewedRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.torve.util.ioDispatcher
import kotlinx.coroutines.withContext
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

    private val _progressChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val progressChanges: Flow<Unit> = _progressChanges.asSharedFlow()

    override suspend fun getInProgress(limit: Long): List<WatchProgress> = withContext(ioDispatcher) {
        val userId = userIdProvider.currentDataUserId()
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
                verifiedWatched = completionState(row.user_id, row.media_id, row.media_type, row.season_number, row.episode_number),
            )
        }
        // Backfill missing poster/backdrop URLs from TMDB asynchronously.
        // This updates the DB so next read will have the URLs.
        val needsEnrichment = rows.filter { it.poster_url.isNullOrBlank() || it.backdrop_url.isNullOrBlank() }
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
                        if ((row.poster_url.isNullOrBlank() && !poster.isNullOrBlank()) ||
                            (row.backdrop_url.isNullOrBlank() && !backdrop.isNullOrBlank())) {
                            database.torveQueries.backfillProgressArtwork(
                                posterUrl = poster,
                                backdropUrl = backdrop,
                                userId = row.user_id,
                                mediaId = row.media_id,
                            )
                            _progressChanges.tryEmit(Unit)
                        }
                    }
                }
            }
        }
        result
    }

    override suspend fun getProgress(mediaId: String): WatchProgress? = withContext(ioDispatcher) {
        val userId = userIdProvider.currentDataUserId()
        database.torveQueries.getProgress(userId = userId, mediaId = mediaId).executeAsOneOrNull()?.let { row ->
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
                verifiedWatched = completionState(row.user_id, row.media_id, row.media_type, row.season_number, row.episode_number),
            )
        }
    }

    override suspend fun playbackCoverage(progress: WatchProgress): PlaybackViewingCoverage? = withContext(ioDispatcher +
        com.torve.data.auth.capturePersonalDataScope(userIdProvider)) {
        database.torveQueries.getPlaybackCoverage(userIdProvider.currentDataUserId(), progress.mediaId,
            if (progress.mediaType == MediaType.MOVIE) "movie" else "series",
            progress.seasonNumber?.toLong() ?: -1, progress.episodeNumber?.toLong() ?: -1).executeAsOneOrNull()?.let { row ->
            runCatching { PlaybackViewingCoverage(row.duration_ms, row.expected_runtime_ms,
                Json.decodeFromString<List<ViewedRange>>(row.ranges_json)) }.getOrNull()
        }
    }

    override suspend fun savePlaybackProgress(progress: WatchProgress, coverage: PlaybackViewingCoverage) =
        persistProgress(progress, coverage = coverage.copy(ranges = coverage.normalized()))

    override suspend fun saveProgress(progress: WatchProgress) = persistProgress(progress)
    override suspend fun saveBookmark(progress: WatchProgress) = persistProgress(progress.copy(verifiedWatched = progress.verifiedWatched ?: false), verifiedCompletion = false)

    private fun completionState(user: String, media: String, type: String, season: Long?, episode: Long?): Boolean? =
        database.torveQueries.getPlaybackCoverage(user, media, type, season ?: -1, episode ?: -1).executeAsOneOrNull()?.let { it.completed == 1L }


    override suspend fun markWatchedManually(progress: WatchProgress) = withContext(ioDispatcher +
        com.torve.data.auth.capturePersonalDataScope(userIdProvider)) {
        database.torveQueries.deletePlaybackCoverage(userIdProvider.currentDataUserId(), progress.mediaId)
        persistProgress(progress, verifiedCompletion = true)
    }

    private suspend fun persistProgress(progress: WatchProgress, verifiedCompletion: Boolean? = null,
        coverage: PlaybackViewingCoverage? = null) = withContext(ioDispatcher +
        com.torve.data.auth.capturePersonalDataScope(userIdProvider)) {
        val userId = userIdProvider.currentDataUserId()
        com.torve.data.auth.capturePersonalDataScope(userIdProvider)
        val prior = database.torveQueries.getProgress(userId, progress.mediaId).executeAsOneOrNull()
        val previousCoverage = playbackCoverage(progress)
        val completedBefore = if (coverage != null) previousCoverage?.complete == true else if (verifiedCompletion != null) false else prior != null && prior.season_number == progress.seasonNumber?.toLong() &&
            prior.episode_number == progress.episodeNumber?.toLong() && prior.duration_ms > 0 &&
            prior.position_ms.toDouble() / prior.duration_ms >= .85
        // Preserve existing poster/backdrop URLs if the new values are null,
        // so a failed TMDB lookup or Trakt sync doesn't overwrite good data.
        val existing = prior
        val posterUrl = progress.posterUrl?.takeIf { it.isNotBlank() } ?: existing?.poster_url
        val backdropUrl = progress.backdropUrl?.takeIf { it.isNotBlank() } ?: existing?.backdrop_url
        val showTitle = progress.showTitle ?: existing?.show_title
        val ratio = if (progress.durationMs > 0) progress.positionMs.toDouble() / progress.durationMs else 0.0
        // Incidental bookmark/artwork saves must never bypass decoder evidence.
        val automaticCompletion = verifiedCompletion ?: coverage?.let {
            it.complete && kotlin.math.abs(it.durationMs - progress.durationMs) <= 2_000
        } ?: (ratio >= .85 && previousCoverage == null && progress.verifiedWatched != false)
        val episode = progress.mediaType == MediaType.SERIES && progress.seasonNumber != null && progress.episodeNumber != null
        val completionId = if (episode) "${progress.mediaId}_s${progress.seasonNumber}e${progress.episodeNumber}" else "${progress.mediaId}_completed"
        database.torveQueries.transaction {
            if (coverage != null) database.torveQueries.upsertPlaybackCoverage(userId, progress.mediaId,
                if (progress.mediaType == MediaType.MOVIE) "movie" else "series",
                progress.seasonNumber?.toLong() ?: -1, progress.episodeNumber?.toLong() ?: -1,
                coverage.durationMs, coverage.expectedRuntimeMs, Json.encodeToString(coverage.ranges), if (automaticCompletion) 1 else 0)
            else if (progress.verifiedWatched == false && previousCoverage == null) database.torveQueries.upsertPlaybackCoverage(userId,
                progress.mediaId, if (progress.mediaType == MediaType.MOVIE) "movie" else "series",
                progress.seasonNumber?.toLong() ?: -1, progress.episodeNumber?.toLong() ?: -1,
                progress.durationMs, 0, "[]", 0)
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
                show_title = showTitle,
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
            if (automaticCompletion && !completedBefore) database.torveQueries.insertHistory(userId, completionId, progress.mediaId,
                if (progress.mediaType == MediaType.SERIES) "series" else "movie", progress.title, posterUrl, backdropUrl,
                Clock.System.now().toEpochMilliseconds(), coverage?.watchedMs ?: progress.positionMs,
                progress.seasonNumber?.toLong(), progress.episodeNumber?.toLong(), showTitle)
        }
        _progressChanges.tryEmit(Unit)
        if (automaticCompletion && !completedBefore) {
            val tmdbId = progress.mediaId.extractTmdbIdOrNull() ?: return@withContext
            val imdbId = resolveImdbId(progress.mediaId, progress.mediaType, tmdbId)
            com.torve.data.auth.capturePersonalDataScope(userIdProvider)
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
                if (!token.isNullOrBlank()) {
                    val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
                    val body = if (progress.mediaType == MediaType.MOVIE) SimklSyncBody(movies = listOf(SimklSyncItem(ids)))
                        else if (progress.seasonNumber != null && progress.episodeNumber != null)
                            com.torve.data.simkl.simklEpisodeBody(ids, progress.seasonNumber, progress.episodeNumber)
                        else null
                    if (body != null) simklClient.addToHistory(token, body)
                }
            }
        }
    }

    override suspend fun getAllProgress(): List<WatchProgress> = withContext(ioDispatcher) {
        val userId = userIdProvider.currentDataUserId()
        database.torveQueries.getAllProgress(userId = userId).executeAsList().map { row ->
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
                verifiedWatched = completionState(row.user_id, row.media_id, row.media_type, row.season_number, row.episode_number),
            )
        }
    }

    override suspend fun deleteProgress(mediaId: String) = withContext(ioDispatcher) {
        database.torveQueries.deletePlaybackCoverage(userIdProvider.currentDataUserId(), mediaId)
        database.torveQueries.deleteProgressByMediaId(
            userId = userIdProvider.currentDataUserId(),
            mediaId = mediaId,
        )
        _progressChanges.tryEmit(Unit)
        Unit
    }

    override suspend fun markWatchedAtMeaningfulContentEnd(progress: WatchProgress) {
        if (progress.durationMs <= 0L) return
        // Reuse the established history-sync path with a completion snapshot,
        // then restore the truthful resume position. The second write cannot
        // revoke remote watched state and avoids moving resume to EOF.
        saveProgress(progress.copy(positionMs = progress.durationMs))
        if (progress.positionMs < progress.durationMs) saveProgress(progress)
    }

    override suspend fun clearAllProgress() = withContext(ioDispatcher) {
        database.torveQueries.clearPlaybackCoverage(userIdProvider.currentDataUserId())
        database.torveQueries.clearAllProgress(userId = userIdProvider.currentDataUserId())
        _progressChanges.tryEmit(Unit)
        Unit
    }

    override suspend fun syncFromTrakt() = withContext(ioDispatcher) {
        val playbackItems = traktApi.getPlaybackProgress()
        if (playbackItems.isEmpty()) return@withContext

        val userId = userIdProvider.currentDataUserId()
        val localById = database.torveQueries.getAllProgress(userId = userId).executeAsList()
            .associateBy { it.media_id }
        val episodeRuntimeCache = mutableMapOf<Pair<Int, Int>, Map<Int, Long>>()

        for (item in playbackItems) {
            val media = if (item.type == "movie") item.movie else item.show
            val ids = media?.ids ?: continue
            val tmdbId = ids.tmdb ?: continue
            val mediaId = tmdbId.toString()
            val isMovie = item.type == "movie"
            if (item.progress <= 0.0 || item.progress >= 100.0) continue

            val updatedAt = runCatching {
                Instant.parse(item.pausedAt).toEpochMilliseconds()
            }.getOrNull() ?: continue
            val local = localById[mediaId]
            if (!shouldApplyRemoteProgress(local?.updated_at, updatedAt)) continue

            var posterUrl: String? = local?.poster_url
            var backdropUrl: String? = local?.backdrop_url
            var durationMs = local?.duration_ms?.takeIf { it > 0L }
            runCatching {
                if (isMovie) {
                    val detail = tmdbClient.getMovieDetail(tmdbId)
                    posterUrl = TmdbMappers.posterUrl(detail.posterPath) ?: posterUrl
                    backdropUrl = TmdbMappers.backdropUrl(detail.backdropPath) ?: backdropUrl
                    durationMs = detail.runtime?.takeIf { it > 0 }?.toLong()?.times(60_000L)
                        ?: durationMs
                } else {
                    val detail = tmdbClient.getTvDetail(tmdbId)
                    posterUrl = TmdbMappers.posterUrl(detail.posterPath) ?: posterUrl
                    backdropUrl = TmdbMappers.backdropUrl(detail.backdropPath) ?: backdropUrl
                    durationMs = resolveEpisodeRuntimeMs(
                        tmdbId = tmdbId,
                        seasonNumber = item.episode?.season,
                        episodeNumber = item.episode?.number,
                        showFallbackRuntimes = detail.episodeRunTime,
                        cache = episodeRuntimeCache,
                    ) ?: durationMs
                }
            }

            val resolvedDurationMs = durationMs ?: if (isMovie) {
                DEFAULT_IMPORTED_MOVIE_DURATION_MS
            } else {
                DEFAULT_IMPORTED_EPISODE_DURATION_MS
            }
            val positionMs = (item.progress.coerceIn(0.0, 100.0) / 100.0 * resolvedDurationMs)
                .toLong()
            database.torveQueries.upsertProgress(
                user_id = userId,
                media_id = mediaId,
                media_type = if (isMovie) "movie" else "series",
                title = media.title.ifBlank { local?.title.orEmpty() },
                poster_url = posterUrl,
                backdrop_url = backdropUrl,
                position_ms = positionMs,
                duration_ms = resolvedDurationMs,
                season_number = item.episode?.season?.toLong(),
                episode_number = item.episode?.number?.toLong(),
                show_title = if (!isMovie) media.title.ifBlank { local?.show_title } else null,
                updated_at = updatedAt,
            )
        }
        _progressChanges.tryEmit(Unit)
    }

    private suspend fun resolveEpisodeRuntimeMs(
        tmdbId: Int,
        seasonNumber: Int?,
        episodeNumber: Int?,
        showFallbackRuntimes: List<Int>,
        cache: MutableMap<Pair<Int, Int>, Map<Int, Long>>,
    ): Long? {
        val season = seasonNumber?.takeIf { it >= 0 }
        val episode = episodeNumber?.takeIf { it > 0 }
        if (season != null && episode != null) {
            val runtimes = cache.getOrPut(tmdbId to season) {
                runCatching {
                    tmdbClient.getTvSeasonDetail(tmdbId, season).episodes.mapNotNull { value ->
                        val runtime = value.runtime?.takeIf { it > 0 } ?: return@mapNotNull null
                        value.episodeNumber to runtime.toLong().times(60_000L)
                    }.toMap()
                }.getOrDefault(emptyMap())
            }
            runtimes[episode]?.let { return it }
        }
        return showFallbackRuntimes.firstOrNull { it > 0 }?.toLong()?.times(60_000L)
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

    private companion object {
        const val DEFAULT_IMPORTED_EPISODE_DURATION_MS = 45L * 60L * 1_000L
        const val DEFAULT_IMPORTED_MOVIE_DURATION_MS = 120L * 60L * 1_000L
    }
}
