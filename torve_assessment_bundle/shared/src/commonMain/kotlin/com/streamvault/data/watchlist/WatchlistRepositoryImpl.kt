package com.streamvault.data.watchlist

import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.data.metadata.TmdbMappers
import com.streamvault.data.simkl.SimklClient
import com.streamvault.data.simkl.SimklIds
import com.streamvault.data.simkl.SimklSyncBody
import com.streamvault.data.simkl.SimklSyncItem
import com.streamvault.data.trakt.api.TraktAuthorizedApi
import com.streamvault.data.trakt.repo.TraktSyncRepository
import com.streamvault.data.trakt.repo.deriveWatchlistMerge
import com.streamvault.data.trakt.TraktHistoryMovie
import com.streamvault.data.trakt.TraktHistoryShow
import com.streamvault.data.trakt.TraktIds
import com.streamvault.data.trakt.TraktWatchlistBody
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchlistItem
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class WatchlistRepositoryImpl(
    private val database: StreamVaultDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val traktSyncRepo: TraktSyncRepository,
    private val prefsRepo: PreferencesRepository,
    private val tmdbClient: TmdbApiClient,
    private val simklClient: SimklClient,
    private val integrationSecretStore: IntegrationSecretStore,
) : WatchlistRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun getAll(): List<WatchlistItem> {
        return database.streamVaultQueries.getAllWatchlist().executeAsList().map { row ->
            WatchlistItem(
                mediaId = row.media_id,
                mediaType = MediaType.fromString(row.media_type),
                tmdbId = row.tmdb_id.toInt(),
                imdbId = row.imdb_id,
                title = row.title,
                posterUrl = row.poster_url,
                backdropUrl = row.backdrop_url,
                rating = row.rating,
                year = row.year?.toInt(),
                genres = row.genres,
                addedAt = row.added_at,
                sortOrder = row.sort_order.toInt(),
            )
        }
    }

    override suspend fun getByType(mediaType: String): List<WatchlistItem> {
        return database.streamVaultQueries.getWatchlistByType(mediaType).executeAsList().map { row ->
            WatchlistItem(
                mediaId = row.media_id,
                mediaType = MediaType.fromString(row.media_type),
                tmdbId = row.tmdb_id.toInt(),
                imdbId = row.imdb_id,
                title = row.title,
                posterUrl = row.poster_url,
                backdropUrl = row.backdrop_url,
                rating = row.rating,
                year = row.year?.toInt(),
                genres = row.genres,
                addedAt = row.added_at,
                sortOrder = row.sort_order.toInt(),
            )
        }
    }

    override suspend fun isInWatchlist(mediaId: String): Boolean {
        return database.streamVaultQueries.isInWatchlist(mediaId).executeAsOne() > 0
    }

    override suspend fun add(item: WatchlistItem) {
        database.streamVaultQueries.insertWatchlistItem(
            media_id = item.mediaId,
            media_type = when (item.mediaType) {
                MediaType.MOVIE -> "movie"
                MediaType.SERIES -> "series"
            },
            tmdb_id = item.tmdbId.toLong(),
            imdb_id = item.imdbId,
            title = item.title,
            poster_url = item.posterUrl,
            backdrop_url = item.backdropUrl,
            rating = item.rating,
            year = item.year?.toLong(),
            genres = item.genres,
            added_at = item.addedAt,
            sort_order = item.sortOrder.toLong(),
        )
        syncTraktAdd(item)
        syncSimklAdd(item)
    }

    override suspend fun remove(mediaId: String) {
        // Read item before deleting so we can sync to Trakt/Simkl
        val item = database.streamVaultQueries.getAllWatchlist().executeAsList()
            .firstOrNull { it.media_id == mediaId }
        database.streamVaultQueries.removeFromWatchlist(mediaId)
        item?.let {
            syncTraktRemove(
                tmdbId = it.tmdb_id.toInt(),
                imdbId = it.imdb_id,
                isMovie = it.media_type == "movie",
            )
            syncSimklRemove(
                tmdbId = it.tmdb_id.toInt(),
                imdbId = it.imdb_id,
                isMovie = it.media_type == "movie",
            )
        }
    }

    override suspend fun clear() {
        database.streamVaultQueries.clearWatchlist()
    }

    override suspend fun syncFromTrakt() {
        try {
            val traktItems = traktApi.getWatchlist()
            val traktIds = traktItems.mapNotNull { item ->
                val media = if (item.type == "movie") item.movie else item.show
                media?.ids?.tmdb?.toString()
            }.toSet()

            // Get existing local IDs to avoid duplicating
            val localIds = database.streamVaultQueries.getAllWatchlist().executeAsList()
                .map { it.media_id }
                .toSet()
            val mergePlan = deriveWatchlistMerge(localIds, traktIds)

            for (item in traktItems) {
                val media = if (item.type == "movie") item.movie else item.show
                val ids = media?.ids ?: continue
                val tmdbId = ids.tmdb ?: continue

                val mediaType = if (item.type == "movie") "movie" else "series"
                val mediaId = tmdbId.toString()

                if (mediaId !in mergePlan.toAdd) continue

                val addedAt = try {
                    Instant.parse(item.listedAt).toEpochMilliseconds()
                } catch (_: Exception) {
                    Clock.System.now().toEpochMilliseconds()
                }

                database.streamVaultQueries.insertWatchlistItem(
                    media_id = mediaId,
                    media_type = mediaType,
                    tmdb_id = tmdbId.toLong(),
                    imdb_id = ids.imdb,
                    title = media.title,
                    poster_url = null,
                    backdrop_url = null,
                    rating = null,
                    year = media.year?.toLong(),
                    genres = null,
                    added_at = addedAt,
                    sort_order = item.rank.toLong(),
                )
            }

            // Remove local items that no longer exist in Trakt
            mergePlan.toRemove.forEach { mediaId ->
                database.streamVaultQueries.removeFromWatchlist(mediaId)
            }
        } catch (_: Exception) {
            // Non-critical - don't block UI if sync fails
        }

        // Enrich items missing poster URLs from TMDB
        enrichMissingPosters()
    }

    private suspend fun enrichMissingPosters() {
        try {
            val itemsNeedingPosters = database.streamVaultQueries.getAllWatchlist()
                .executeAsList()
                .filter { it.poster_url == null }

            for (row in itemsNeedingPosters) {
                try {
                    val tmdbId = row.tmdb_id.toInt()
                    val isMovie = row.media_type == "movie"
                    if (isMovie) {
                        val detail = tmdbClient.getMovieDetail(tmdbId)
                        database.streamVaultQueries.updateWatchlistMeta(
                            poster_url = TmdbMappers.posterUrl(detail.posterPath),
                            backdrop_url = TmdbMappers.backdropUrl(detail.backdropPath),
                            rating = detail.voteAverage,
                            genres = detail.genres?.joinToString(", ") { it.name },
                            media_id = row.media_id,
                        )
                    } else {
                        val detail = tmdbClient.getTvDetail(tmdbId)
                        database.streamVaultQueries.updateWatchlistMeta(
                            poster_url = TmdbMappers.posterUrl(detail.posterPath),
                            backdrop_url = TmdbMappers.backdropUrl(detail.backdropPath),
                            rating = detail.voteAverage,
                            genres = detail.genres?.joinToString(", ") { it.name },
                            media_id = row.media_id,
                        )
                    }
                } catch (_: Exception) {
                    // Skip individual failures, continue enriching others
                }
            }
        } catch (_: Exception) {
            // Non-critical
        }
    }

    private fun syncTraktAdd(item: WatchlistItem) {
        syncScope.launch {
            try {
                val ids = TraktIds(tmdb = item.tmdbId, imdb = item.imdbId)
                val body = if (item.mediaType == MediaType.MOVIE) {
                    TraktWatchlistBody(movies = listOf(TraktHistoryMovie(ids)))
                } else {
                    TraktWatchlistBody(shows = listOf(TraktHistoryShow(ids)))
                }
                traktApi.addToWatchlist(body)
            } catch (_: Exception) {
                traktSyncRepo.enqueueWatchlistAdd(
                    tmdbId = item.tmdbId,
                    mediaType = item.mediaType,
                    imdbId = item.imdbId,
                )
            }
        }
    }

    private fun syncTraktRemove(tmdbId: Int, imdbId: String?, isMovie: Boolean) {
        syncScope.launch {
            try {
                val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                val body = if (isMovie) {
                    TraktWatchlistBody(movies = listOf(TraktHistoryMovie(ids)))
                } else {
                    TraktWatchlistBody(shows = listOf(TraktHistoryShow(ids)))
                }
                traktApi.removeFromWatchlist(body)
            } catch (_: Exception) {
                traktSyncRepo.enqueueWatchlistRemove(
                    tmdbId = tmdbId,
                    mediaType = if (isMovie) MediaType.MOVIE else MediaType.SERIES,
                    imdbId = imdbId,
                )
            }
        }
    }

    private fun syncSimklAdd(item: WatchlistItem) {
        syncScope.launch {
            try {
                val token = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                    ?: prefsRepo.getString("simkl_access_token")
                    ?: return@launch
                if (token.isBlank()) return@launch
                val ids = SimklIds(tmdb = item.tmdbId, imdb = item.imdbId)
                val body = if (item.mediaType == MediaType.MOVIE) {
                    SimklSyncBody(movies = listOf(SimklSyncItem(ids)))
                } else {
                    SimklSyncBody(shows = listOf(SimklSyncItem(ids)))
                }
                simklClient.addToWatchlist(token, body)
            } catch (_: Exception) {
                // Fire-and-forget
            }
        }
    }

    private fun syncSimklRemove(tmdbId: Int, imdbId: String?, isMovie: Boolean) {
        syncScope.launch {
            try {
                val token = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                    ?: prefsRepo.getString("simkl_access_token")
                    ?: return@launch
                if (token.isBlank()) return@launch
                val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
                val body = if (isMovie) {
                    SimklSyncBody(movies = listOf(SimklSyncItem(ids)))
                } else {
                    SimklSyncBody(shows = listOf(SimklSyncItem(ids)))
                }
                simklClient.removeFromWatchlist(token, body)
            } catch (_: Exception) {
                // Fire-and-forget
            }
        }
    }
}

