package com.torve.data.history

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.torve.data.auth.UserIdProvider
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.stats.WatchSessionMediaIdentity
import com.torve.data.stats.WatchSessionRecorder
import com.torve.data.trakt.TraktWatchedShowResponse
import com.torve.data.trakt.episodeMarkers
import com.torve.data.trakt.latestEpisodeMarker
import com.torve.data.trakt.shouldApplyRemoteProgress
import com.torve.data.trakt.traktWatchedHistoryId
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.stats.WatchSessionSource
import com.torve.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class WatchHistoryRepositoryImpl(
    private val database: TorveDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val tmdbClient: TmdbApiClient,
    private val traktSyncRepo: TraktSyncRepository,
    private val simklClient: SimklClient,
    private val integrationSecretStore: IntegrationSecretStore,
    private val userIdProvider: UserIdProvider,
    private val watchSessionRecorder: WatchSessionRecorder? = null,
    private val clock: Clock = Clock.System,
) : WatchHistoryRepository {
    private val queries get() = database.torveQueries
    private val seriesRefreshMutex = Mutex()
    private val lastSeriesRefreshAttempt = mutableMapOf<Triple<String, String, String>, Long>()

    override suspend fun getRecent(limit: Int): List<WatchHistoryEntry> = withContext(ioDispatcher) {
        queries.getRecentHistory(
            userId = userIdProvider.currentDataUserId(),
            limit = limit.toLong(),
        ).executeAsList().map { it.toDomain() }
    }

    override suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry> = withContext(ioDispatcher) {
        queries.getHistoryByDate(
            userId = userIdProvider.currentDataUserId(),
            startMs = startMs,
            endMs = endMs,
        ).executeAsList().map { it.toDomain() }
    }

    override suspend fun getAll(): List<WatchHistoryEntry> = withContext(ioDispatcher) {
        queries.getAllHistory(userId = userIdProvider.currentDataUserId())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getForMedia(mediaId: String): List<WatchHistoryEntry> = withContext(ioDispatcher) {
        queries.getHistoryForMedia(
            userId = userIdProvider.currentDataUserId(),
            mediaId = mediaId,
        ).executeAsList().map { it.toDomain() }
    }

    override fun observeForMedia(mediaId: String): Flow<List<WatchHistoryEntry>> = flow {
        val userId = userIdProvider.currentDataUserId()
        emitAll(
            queries.getHistoryForMedia(userId = userId, mediaId = mediaId)
                .asFlow().mapToList(ioDispatcher).map { rows ->
                    if (userId == userIdProvider.currentDataUserId()) rows.map { it.toDomain() } else emptyList()
                }.distinctUntilChanged(),
        )
    }

    override suspend fun refreshSeries(item: MediaItem) = withContext(ioDispatcher) {
        if (item.type != MediaType.SERIES) return@withContext
        val userId = userIdProvider.currentDataUserId()
        val connection = traktApi.watchedRefreshScope() ?: run {
            println("EpisodeWatchSync result=not_connected")
            return@withContext
        }
        val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@withContext
        seriesRefreshMutex.withLock {
            val key = Triple(userId, connection, item.id)
            val now = clock.now().toEpochMilliseconds()
            val lastAttempt = lastSeriesRefreshAttempt[key]
            if (lastAttempt != null && now - lastAttempt < SERIES_REFRESH_INTERVAL_MS) return@withLock
            if (userId != userIdProvider.currentDataUserId() || connection != traktApi.watchedRefreshScope()) return@withLock
            if (lastSeriesRefreshAttempt.size >= 128) lastSeriesRefreshAttempt.clear()
            lastSeriesRefreshAttempt[key] = now
            val imdbId = item.imdbId?.extractImdbIdOrNull()
                ?: resolveImdbId(item.id, isMovie = false, tmdbId = tmdbId)
                ?: return@withLock

            // Push durable local changes before reading the other devices' state.
            traktSyncRepo.flushPendingWrites()
            val localBeforeFetch = queries.getHistoryForMedia(userId = userId, mediaId = item.id).executeAsList()
            val progress = traktApi.getShowWatchedProgress(imdbId)
            if (userId != userIdProvider.currentDataUserId() || connection != traktApi.watchedRefreshScope()) return@withLock
            val pending = traktSyncRepo.pendingEpisodeHistoryChanges(tmdbId)
            val markers = progress.episodeMarkers().filter { pending[it.season to it.episode] != false }
            queries.transaction {
                // A user action or another sync during the request wins over
                // this older snapshot. Retry on the next bounded refresh.
                if (queries.getHistoryForMedia(userId = userId, mediaId = item.id).executeAsList() != localBeforeFetch) {
                    return@transaction
                }
                queries.clearImportedTraktSeriesHistoryForMedia(userId = userId, mediaId = item.id)
                for (marker in markers) {
                    queries.insertHistory(
                        user_id = userId,
                        id = traktWatchedHistoryId(item.id, marker.season, marker.episode),
                        media_id = item.id,
                        media_type = "series",
                        title = item.title,
                        poster_url = item.posterUrl,
                        backdrop_url = item.backdropUrl,
                        watched_at = marker.watchedAtMs,
                        duration_watched_ms = 0,
                        season_number = marker.season.toLong(),
                        episode_number = marker.episode.toLong(),
                        show_title = item.title,
                    )
                }
                println("EpisodeWatchSync media=$tmdbId result=applied watched=${markers.size} pending=${pending.size}")
            }
        }
    }

    override suspend fun record(entry: WatchHistoryEntry) = withContext(ioDispatcher +
        com.torve.data.auth.capturePersonalDataScope(userIdProvider)) {
        val tmdbId = entry.mediaId.extractTmdbIdOrNull()
        val isMovie = entry.mediaType.equals("movie", ignoreCase = true)
        val isEpisode = !isMovie && entry.seasonNumber != null && entry.episodeNumber != null
        // Queue first, including offline writes. A process exit must not leave
        // an accepted local episode marking with no durable cloud retry.
        if (isEpisode && tmdbId != null) {
            traktSyncRepo.enqueueEpisodeHistoryAdd(
                tmdbId = tmdbId,
                imdbId = entry.mediaId.extractImdbIdOrNull(),
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
            )
        }
        // This path records an explicit user marking. Clear only its matching resume item.
        val person = userIdProvider.currentDataUserId()
        val resume = queries.getProgress(person, entry.mediaId).executeAsOneOrNull()
        if (resume != null && (isMovie || (resume.season_number == entry.seasonNumber?.toLong() &&
                    resume.episode_number == entry.episodeNumber?.toLong()))) {
            queries.deleteProgressByMediaId(person, entry.mediaId)
        }
        if (isEpisode) queries.deleteEpisodePlaybackCoverage(person, entry.mediaId, entry.seasonNumber!!.toLong(), entry.episodeNumber!!.toLong())
        else queries.deletePlaybackCoverage(person, entry.mediaId)
        queries.insertHistory(
            user_id = person,
            id = entry.id,
            media_id = entry.mediaId,
            media_type = entry.mediaType,
            title = entry.title,
            poster_url = entry.posterUrl,
            backdrop_url = entry.backdropUrl,
            watched_at = entry.watchedAt,
            duration_watched_ms = entry.durationWatchedMs,
            season_number = entry.seasonNumber?.toLong(),
            episode_number = entry.episodeNumber?.toLong(),
            show_title = entry.showTitle,
        )
        if (tmdbId == null) return@withContext
        if (isEpisode) {
            try {
                traktSyncRepo.flushPendingWrites()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            runCatching {
                integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }?.let { token ->
                    simklClient.addToHistory(token, com.torve.data.simkl.simklEpisodeBody(
                        SimklIds(tmdb = tmdbId), entry.seasonNumber!!, entry.episodeNumber!!,
                        Instant.fromEpochMilliseconds(entry.watchedAt).toString(),
                    ))
                }
            }.onFailure { if (it is CancellationException) throw it }
            return@withContext
        }
        val imdbId = resolveImdbId(entry.mediaId, isMovie, tmdbId)
        runCatching {
            if (isMovie) {
                traktSyncRepo.enqueueHistoryAdd(
                    tmdbId = tmdbId,
                    mediaType = MediaType.MOVIE,
                    imdbId = imdbId,
                )
                traktSyncRepo.flushPendingWrites()
            }
        }.onFailure {
            runCatching {
                if (isMovie) {
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
            if (!token.isNullOrBlank() && isMovie) {
                val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
                val body = SimklSyncBody(movies = listOf(SimklSyncItem(ids)))
                simklClient.addToHistory(token, body)
            }
        }
        Unit
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        queries.deleteHistory(
            userId = userIdProvider.currentDataUserId(),
            historyId = id,
        )
    }

    override suspend fun clearAll() = withContext(ioDispatcher) {
        queries.clearAllHistory(userId = userIdProvider.currentDataUserId())
    }

    override suspend fun getCount(): Long = withContext(ioDispatcher) {
        queries.getHistoryCount(userId = userIdProvider.currentDataUserId()).executeAsOne()
    }

    override suspend fun deleteEpisode(mediaId: String, season: Int, episode: Int) = withContext(ioDispatcher +
        com.torve.data.auth.capturePersonalDataScope(userIdProvider)) {
        val person = userIdProvider.currentDataUserId()
        queries.transaction {
            queries.deleteEpisodePlaybackCoverage(person, mediaId, season.toLong(), episode.toLong())
            queries.deleteEpisodeHistory(
                userId = person,
                mediaId = mediaId,
                season = season.toLong(),
                episode = episode.toLong(),
            )
            // Undo the estimated completion alongside its watched mark. Actual
            // player sessions retain measured watch time and recoverable bookmarks.
            queries.deleteEstimatedWatchSessionsForEpisode(
                userId = person,
                mediaId = mediaId,
                season = season.toLong(),
                episode = episode.toLong(),
            )
        }
        val tmdb = mediaId.extractTmdbIdOrNull()
        if (tmdb != null) runCatching {
            integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }?.let { token ->
                simklClient.removeFromHistory(token, com.torve.data.simkl.simklEpisodeBody(SimklIds(tmdb = tmdb), season, episode))
            }
        }.onFailure { if (it is CancellationException) throw it }
        Unit
    }

    override suspend fun syncFromTrakt() = withContext(ioDispatcher) {
        val userId = userIdProvider.currentDataUserId()
        val connection = traktApi.watchedRefreshScope() ?: return@withContext
        traktSyncRepo.flushPendingWrites()
        val historyItems = traktApi.getHistory(limit = 100)
        if (userId != userIdProvider.currentDataUserId() || connection != traktApi.watchedRefreshScope()) return@withContext
        val localIds = queries.getAllHistory(userId = userId).executeAsList()
            .map { it.id }
            .toSet()
        val episodeRuntimeCache = mutableMapOf<Pair<Int, Int>, Map<Int, Long>>()

        for (item in historyItems) {
                val traktId = "trakt_${item.id}"
                val alreadyImported = traktId in localIds

                val isMovie = item.type == "movie"
                val media = if (isMovie) item.movie else item.show
                val ids = media?.ids ?: continue
                val tmdbId = ids.tmdb ?: continue
                val mediaId = tmdbId.toString()
                val mediaType = if (isMovie) "movie" else "series"

                val watchedAt = try {
                    Instant.parse(item.watchedAt).toEpochMilliseconds()
                } catch (_: Exception) {
                    continue
                }

                // Fetch lightweight display identity from TMDB. Movie runtime
                // is available here and can be counted as estimated import time.
                var posterUrl: String? = null
                var backdropUrl: String? = null
                var runtimeMs: Long? = null
                var imdbId: String? = ids.imdb
                try {
                    if (isMovie) {
                        val detail = tmdbClient.getMovieDetail(tmdbId)
                        posterUrl = TmdbMappers.posterUrl(detail.posterPath)
                        backdropUrl = TmdbMappers.backdropUrl(detail.backdropPath)
                        runtimeMs = detail.runtime?.takeIf { it > 0 }?.toLong()?.times(60_000L)
                        imdbId = detail.imdbId ?: imdbId
                    } else {
                        val detail = tmdbClient.getTvDetail(tmdbId)
                        posterUrl = TmdbMappers.posterUrl(detail.posterPath)
                        backdropUrl = TmdbMappers.backdropUrl(detail.backdropPath)
                        imdbId = item.episode?.ids?.imdb ?: detail.externalIds?.imdbId ?: imdbId
                        runtimeMs = resolveEpisodeRuntimeMs(
                            tmdbId = tmdbId,
                            seasonNumber = item.episode?.season,
                            episodeNumber = item.episode?.number,
                            showFallbackRuntimes = detail.episodeRunTime,
                            cache = episodeRuntimeCache,
                        )
                    }
                } catch (_: Exception) { /* non-critical */ }

                val title = if (isMovie) media.title else (item.episode?.title ?: media.title)
                if (!alreadyImported) {
                    queries.insertHistory(
                        user_id = userId,
                        id = traktId,
                        media_id = mediaId,
                        media_type = mediaType,
                        title = title,
                        poster_url = posterUrl,
                        backdrop_url = backdropUrl,
                        watched_at = watchedAt,
                        duration_watched_ms = 0,
                        season_number = item.episode?.season?.toLong(),
                        episode_number = item.episode?.number?.toLong(),
                        show_title = if (!isMovie) media.title else null,
                    )
                }
                watchSessionRecorder?.recordImportedCompleted(
                    identity = WatchSessionMediaIdentity(
                        mediaId = mediaId,
                        mediaType = if (isMovie) MediaType.MOVIE else MediaType.SERIES,
                        title = title,
                        showId = if (!isMovie) mediaId else null,
                        showTitle = if (!isMovie) media.title else null,
                        seasonNumber = item.episode?.season,
                        episodeNumber = item.episode?.number,
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl,
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                    ),
                    source = WatchSessionSource.TRAKT,
                    eventAt = watchedAt,
                    importDiscriminator = item.id.takeIf { it > 0L }?.toString() ?: watchedAt.toString(),
                    runtimeMs = runtimeMs,
                )
        }

        // `/sync/history` is event based and intentionally bounded above.
        // It cannot rebuild a long-running show's complete watched state.
        // The paginated watched endpoint supplies the authoritative set.
        val watchedShows = traktApi.getWatchedShows()
        if (userId != userIdProvider.currentDataUserId() || connection != traktApi.watchedRefreshScope()) return@withContext
        val pending = watchedShows.mapNotNull { it.show?.ids?.tmdb }.distinct().associateWith { tmdbId ->
            traktSyncRepo.pendingEpisodeHistoryChanges(tmdbId)
        }
        importWatchedSeriesState(userId, watchedShows, pending)
    }

    private fun importWatchedSeriesState(
        userId: String,
        watchedShows: List<TraktWatchedShowResponse>,
        pending: Map<Int, Map<Pair<Int, Int>, Boolean>>,
    ) {
        queries.transaction {
            // These rows are a materialized view of Trakt's authoritative
            // watched set. Rebuild it after a complete successful fetch so
            // removals and progress resets also propagate to this device.
            queries.clearImportedTraktSeriesHistory(userId = userId)

            for (watchedShow in watchedShows) {
                val show = watchedShow.show ?: continue
                val ids = show.ids ?: continue
                val tmdbId = ids.tmdb ?: continue
                val mediaId = tmdbId.toString()
                val markers = watchedShow.episodeMarkers()
                for (marker in markers) {
                    if (pending[tmdbId]?.get(marker.season to marker.episode) == false) continue
                    queries.insertHistory(
                        user_id = userId,
                        id = traktWatchedHistoryId(
                            showIdentity = ids.trakt?.toString() ?: mediaId,
                            season = marker.season,
                            episode = marker.episode,
                        ),
                        media_id = mediaId,
                        media_type = "series",
                        title = show.title,
                        poster_url = null,
                        backdrop_url = null,
                        watched_at = marker.watchedAtMs,
                        duration_watched_ms = 0,
                        season_number = marker.season.toLong(),
                        episode_number = marker.episode.toLong(),
                        show_title = show.title,
                    )
                }

                val latest = watchedShow.latestEpisodeMarker() ?: continue
                val local = queries.getProgress(userId = userId, mediaId = mediaId).executeAsOneOrNull()
                if (!shouldApplyRemoteProgress(local?.updated_at, latest.watchedAtMs)) continue
                val durationMs = local?.duration_ms?.takeIf { it > 0L }
                    ?: show.runtime?.takeIf { it > 0 }?.toLong()?.times(60_000L)
                    ?: DEFAULT_IMPORTED_EPISODE_DURATION_MS
                queries.upsertProgress(
                    user_id = userId,
                    media_id = mediaId,
                    media_type = "series",
                    title = show.title.ifBlank { local?.title.orEmpty() },
                    poster_url = local?.poster_url,
                    backdrop_url = local?.backdrop_url,
                    position_ms = durationMs,
                    duration_ms = durationMs,
                    season_number = latest.season.toLong(),
                    episode_number = latest.episode.toLong(),
                    show_title = show.title.ifBlank { local?.show_title },
                    updated_at = latest.watchedAtMs,
                )
            }
        }
    }

    private fun com.torve.db.Watch_history.toDomain() = WatchHistoryEntry(
        id = id,
        mediaId = media_id,
        mediaType = media_type,
        title = title,
        posterUrl = poster_url,
        backdropUrl = backdrop_url,
        watchedAt = watched_at,
        durationWatchedMs = duration_watched_ms,
        seasonNumber = season_number?.toInt(),
        episodeNumber = episode_number?.toInt(),
        showTitle = show_title,
    )

    private suspend fun resolveImdbId(
        mediaId: String,
        isMovie: Boolean,
        tmdbId: Int,
    ): String? {
        mediaId.extractImdbIdOrNull()?.let { return it }
        return runCatching {
            if (isMovie) {
                tmdbClient.getMovieDetail(tmdbId).imdbId
            } else {
                tmdbClient.getTvDetail(tmdbId).externalIds?.imdbId
            }
        }.getOrNull()
    }

    private suspend fun resolveEpisodeRuntimeMs(
        tmdbId: Int,
        seasonNumber: Int?,
        episodeNumber: Int?,
        showFallbackRuntimes: List<Int>,
        cache: MutableMap<Pair<Int, Int>, Map<Int, Long>>,
    ): Long? {
        val season = seasonNumber?.takeIf { it > 0 }
        val episode = episodeNumber?.takeIf { it > 0 }
        if (season != null && episode != null) {
            val seasonRuntimeByEpisode = cache.getOrPut(tmdbId to season) {
                runCatching {
                    tmdbClient.getTvSeasonDetail(tmdbId, season)
                        .episodes
                        .mapNotNull { ep ->
                            val runtime = ep.runtime?.takeIf { it > 0 } ?: return@mapNotNull null
                            ep.episodeNumber to runtime.toLong().times(60_000L)
                        }
                        .toMap()
                }.getOrDefault(emptyMap())
            }
            seasonRuntimeByEpisode[episode]?.let { return it }
        }
        return showFallbackRuntimes
            .firstOrNull { it > 0 }
            ?.toLong()
            ?.times(60_000L)
    }

    private companion object {
        const val SERIES_REFRESH_INTERVAL_MS = 60_000L
        const val DEFAULT_IMPORTED_EPISODE_DURATION_MS = 45L * 60L * 1_000L
    }
}
