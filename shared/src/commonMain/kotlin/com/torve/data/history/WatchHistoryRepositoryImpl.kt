package com.torve.data.history

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
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.WatchHistoryRepository
import kotlinx.datetime.Instant

class WatchHistoryRepositoryImpl(
    private val database: TorveDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val tmdbClient: TmdbApiClient,
    private val traktSyncRepo: TraktSyncRepository,
    private val simklClient: SimklClient,
    private val integrationSecretStore: IntegrationSecretStore,
    private val userIdProvider: UserIdProvider,
) : WatchHistoryRepository {
    private val queries get() = database.torveQueries

    override suspend fun getRecent(limit: Int): List<WatchHistoryEntry> {
        return queries.getRecentHistory(
            userId = userIdProvider.currentUserId(),
            limit = limit.toLong(),
        ).executeAsList().map { it.toDomain() }
    }

    override suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry> {
        return queries.getHistoryByDate(
            userId = userIdProvider.currentUserId(),
            startMs = startMs,
            endMs = endMs,
        ).executeAsList().map { it.toDomain() }
    }

    override suspend fun getAll(): List<WatchHistoryEntry> {
        return queries.getAllHistory(userId = userIdProvider.currentUserId())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getForMedia(mediaId: String): List<WatchHistoryEntry> {
        return queries.getHistoryForMedia(
            userId = userIdProvider.currentUserId(),
            mediaId = mediaId,
        ).executeAsList().map { it.toDomain() }
    }

    override suspend fun record(entry: WatchHistoryEntry) {
        queries.insertHistory(
            user_id = userIdProvider.currentUserId(),
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
        val tmdbId = entry.mediaId.extractTmdbIdOrNull() ?: return
        val isMovie = entry.mediaType.equals("movie", ignoreCase = true)
        val imdbId = resolveImdbId(entry.mediaId, isMovie, tmdbId)
        runCatching {
            if (!isMovie && entry.seasonNumber != null && entry.episodeNumber != null) {
                traktApi.addToHistory(
                    TraktHistoryBody(
                        shows = listOf(
                            TraktHistoryShow(
                                ids = TraktIds(tmdb = tmdbId, imdb = imdbId),
                                seasons = listOf(
                                    TraktHistorySeasonEntry(
                                        number = entry.seasonNumber,
                                        episodes = listOf(TraktHistoryEpisodeEntry(number = entry.episodeNumber)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            } else if (isMovie) {
                traktSyncRepo.enqueueHistoryAdd(
                    tmdbId = tmdbId,
                    mediaType = MediaType.MOVIE,
                    imdbId = imdbId,
                )
                traktSyncRepo.flushPendingWrites()
            }
        }.onFailure {
            runCatching {
                if (!isMovie && entry.seasonNumber != null && entry.episodeNumber != null) {
                    traktSyncRepo.enqueueEpisodeHistoryAdd(
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                        season = entry.seasonNumber,
                        episode = entry.episodeNumber,
                    )
                } else if (isMovie) {
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
    }

    override suspend fun delete(id: String) {
        queries.deleteHistory(
            userId = userIdProvider.currentUserId(),
            historyId = id,
        )
    }

    override suspend fun clearAll() {
        queries.clearAllHistory(userId = userIdProvider.currentUserId())
    }

    override suspend fun getCount(): Long {
        return queries.getHistoryCount(userId = userIdProvider.currentUserId()).executeAsOne()
    }

    override suspend fun syncFromTrakt() {
        try {
            val historyItems = traktApi.getHistory(limit = 100)
            if (historyItems.isEmpty()) return

            val userId = userIdProvider.currentUserId()
            val localIds = queries.getAllHistory(userId = userId).executeAsList()
                .map { it.id }
                .toSet()

            for (item in historyItems) {
                val traktId = "trakt_${item.id}"
                if (traktId in localIds) continue

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

                queries.insertHistory(
                    user_id = userId,
                    id = traktId,
                    media_id = mediaId,
                    media_type = mediaType,
                    title = if (isMovie) media.title else (item.episode?.title ?: media.title),
                    poster_url = posterUrl,
                    backdrop_url = backdropUrl,
                    watched_at = watchedAt,
                    duration_watched_ms = 0,
                    season_number = item.episode?.season?.toLong(),
                    episode_number = item.episode?.number?.toLong(),
                    show_title = if (!isMovie) media.title else null,
                )
            }
        } catch (_: Exception) {
            // Non-critical
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
}
