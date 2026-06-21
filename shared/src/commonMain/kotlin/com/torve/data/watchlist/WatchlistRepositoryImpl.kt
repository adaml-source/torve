package com.torve.data.watchlist

import com.torve.data.auth.UserIdProvider
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.data.trakt.repo.deriveWatchlistMerge
import com.torve.data.trakt.TraktHistoryMovie
import com.torve.data.trakt.TraktHistoryShow
import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.TraktWatchlistBody
import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchlistItem
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.repository.WatchlistMutationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class WatchlistRepositoryImpl(
    private val database: TorveDatabase,
    private val traktApi: TraktAuthorizedApi,
    private val traktSyncRepo: TraktSyncRepository,
    private val prefsRepo: PreferencesRepository,
    private val tmdbClient: TmdbApiClient,
    private val simklClient: SimklClient,
    private val integrationSecretStore: IntegrationSecretStore,
    private val userIdProvider: UserIdProvider,
) : WatchlistRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun signedInUserIdOrNull(): String? = userIdProvider.currentUserIdOrNull()

    override suspend fun getAll(): List<WatchlistItem> {
        val userId = signedInUserIdOrNull() ?: return emptyList()
        return database.torveQueries.getAllWatchlist(userId = userId).executeAsList().map { row ->
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
        val userId = signedInUserIdOrNull() ?: return emptyList()
        return database.torveQueries.getWatchlistByType(userId = userId, mediaType = mediaType).executeAsList().map { row ->
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
        val userId = signedInUserIdOrNull() ?: return false
        return database.torveQueries.isInWatchlist(
            userId = userId,
            mediaId = mediaId.normalizeWatchlistMediaId(),
        ).executeAsOne() > 0
    }

    override suspend fun add(item: WatchlistItem) {
        val userId = signedInUserIdOrNull() ?: return
        insertLocal(userId, item)
        syncTraktAdd(item)
        syncSimklAdd(item)
    }

    override suspend fun add(item: WatchlistItem, syncTrakt: Boolean, syncSimkl: Boolean) {
        val userId = signedInUserIdOrNull() ?: return
        insertLocal(userId, item)
        if (syncTrakt) syncTraktAdd(item)
        if (syncSimkl) syncSimklAdd(item)
    }

    override suspend fun remove(mediaId: String) {
        val userId = signedInUserIdOrNull() ?: return
        // Read item before deleting so we can sync to Trakt/Simkl
        val item = database.torveQueries.getAllWatchlist(userId = userId).executeAsList()
            .firstOrNull { it.media_id == mediaId }
        database.torveQueries.removeFromWatchlist(userId = userId, mediaId = mediaId)
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

    override suspend fun addToTraktWatchlist(item: WatchlistItem): WatchlistMutationResult {
        val userId = signedInUserIdOrNull()
            ?: return WatchlistMutationResult.Failed(item.mediaId.normalizeWatchlistMediaId())
        val normalizedItem = item.copy(mediaId = item.mediaId.normalizeWatchlistMediaId())
        if (!normalizedItem.hasTraktMutationIds() || normalizedItem.title.isBlank()) {
            return WatchlistMutationResult.InsufficientMetadata(normalizedItem.mediaId)
        }
        return try {
            traktApi.addToWatchlist(normalizedItem.toTraktWatchlistBody())
            insertLocal(userId, normalizedItem)
            WatchlistMutationResult.Success(
                mediaId = normalizedItem.mediaId,
                isInWatchlist = true,
                item = normalizedItem,
            )
        } catch (error: Exception) {
            if (error.isMissingTraktConnection()) {
                WatchlistMutationResult.MissingTraktConnection(normalizedItem.mediaId)
            } else {
                WatchlistMutationResult.Failed(normalizedItem.mediaId)
            }
        }
    }

    override suspend fun removeFromTraktWatchlist(mediaId: String): WatchlistMutationResult {
        val userId = signedInUserIdOrNull()
            ?: return WatchlistMutationResult.Failed(mediaId.normalizeWatchlistMediaId())
        val normalizedMediaId = mediaId.normalizeWatchlistMediaId()
        val item = getLocalItem(userId, normalizedMediaId)
            ?: return WatchlistMutationResult.InsufficientMetadata(normalizedMediaId)
        if (!item.hasTraktMutationIds()) {
            return WatchlistMutationResult.InsufficientMetadata(normalizedMediaId)
        }
        return try {
            traktApi.removeFromWatchlist(item.toTraktWatchlistBody())
            database.torveQueries.removeFromWatchlist(userId = userId, mediaId = normalizedMediaId)
            WatchlistMutationResult.Success(
                mediaId = normalizedMediaId,
                isInWatchlist = false,
                item = item,
            )
        } catch (error: Exception) {
            if (error.isMissingTraktConnection()) {
                WatchlistMutationResult.MissingTraktConnection(normalizedMediaId)
            } else {
                WatchlistMutationResult.Failed(normalizedMediaId)
            }
        }
    }

    override suspend fun toggleTraktWatchlist(item: WatchlistItem): WatchlistMutationResult {
        val mediaId = item.mediaId.normalizeWatchlistMediaId()
        return if (isInWatchlist(mediaId)) {
            removeFromTraktWatchlist(mediaId)
        } else {
            addToTraktWatchlist(item.copy(mediaId = mediaId))
        }
    }

    override suspend fun clear() {
        val userId = signedInUserIdOrNull() ?: return
        database.torveQueries.clearWatchlist(userId = userId)
    }

    override suspend fun syncFromTrakt() {
        val userId = signedInUserIdOrNull() ?: return
        try {
            val traktItems = traktApi.getWatchlist()
            val traktIds = traktItems.mapNotNull { item ->
                val media = if (item.type == "movie") item.movie else item.show
                media?.ids?.tmdb?.toString()
            }.toSet()

            // Get existing local IDs to avoid duplicating
            val localIds = database.torveQueries.getAllWatchlist(userId = userId).executeAsList()
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

                database.torveQueries.insertWatchlistItem(
                    user_id = userId,
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
                database.torveQueries.removeFromWatchlist(userId = userId, mediaId = mediaId)
            }
        } catch (_: Exception) {
            // Non-critical - don't block UI if sync fails
        }

        // Enrich items missing poster URLs from TMDB
        enrichMissingPosters()
    }

    private suspend fun enrichMissingPosters() {
        try {
            val userId = signedInUserIdOrNull() ?: return
            val itemsNeedingPosters = database.torveQueries.getAllWatchlist(userId = userId)
                .executeAsList()
                .filter { it.poster_url == null }

            for (row in itemsNeedingPosters) {
                try {
                    val tmdbId = row.tmdb_id.toInt()
                    val isMovie = row.media_type == "movie"
                    if (isMovie) {
                        val detail = tmdbClient.getMovieDetail(tmdbId)
                        database.torveQueries.updateWatchlistMeta(
                            poster_url = TmdbMappers.posterUrl(detail.posterPath),
                            backdrop_url = TmdbMappers.backdropUrl(detail.backdropPath),
                            rating = detail.voteAverage,
                            genres = detail.genres?.joinToString(", ") { it.name },
                            userId = userId,
                            mediaId = row.media_id,
                        )
                    } else {
                        val detail = tmdbClient.getTvDetail(tmdbId)
                        database.torveQueries.updateWatchlistMeta(
                            poster_url = TmdbMappers.posterUrl(detail.posterPath),
                            backdrop_url = TmdbMappers.backdropUrl(detail.backdropPath),
                            rating = detail.voteAverage,
                            genres = detail.genres?.joinToString(", ") { it.name },
                            userId = userId,
                            mediaId = row.media_id,
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

    private fun insertLocal(userId: String, item: WatchlistItem) {
        database.torveQueries.insertWatchlistItem(
            user_id = userId,
            media_id = item.mediaId.normalizeWatchlistMediaId(),
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
    }

    private fun getLocalItem(userId: String, mediaId: String): WatchlistItem? {
        return database.torveQueries.getAllWatchlist(userId = userId).executeAsList()
            .firstOrNull { it.media_id.normalizeWatchlistMediaId() == mediaId.normalizeWatchlistMediaId() }
            ?.let { row ->
                WatchlistItem(
                    mediaId = row.media_id.normalizeWatchlistMediaId(),
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

    private fun WatchlistItem.hasTraktMutationIds(): Boolean =
        tmdbId > 0 || !imdbId.isNullOrBlank()

    private fun WatchlistItem.toTraktWatchlistBody(): TraktWatchlistBody {
        val ids = TraktIds(
            tmdb = tmdbId.takeIf { it > 0 },
            imdb = imdbId?.takeIf { it.isNotBlank() },
        )
        return if (mediaType == MediaType.MOVIE) {
            TraktWatchlistBody(movies = listOf(TraktHistoryMovie(ids)))
        } else {
            TraktWatchlistBody(shows = listOf(TraktHistoryShow(ids)))
        }
    }

    private fun Throwable.isMissingTraktConnection(): Boolean =
        this is TraktAuthorizationRequiredException ||
            message?.contains("Trakt not connected", ignoreCase = true) == true ||
            message?.contains("authentication required", ignoreCase = true) == true ||
            message?.contains("Reconnect Trakt", ignoreCase = true) == true

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

private fun String.normalizeWatchlistMediaId(): String {
    val parts = split(":")
    if (parts.firstOrNull()?.lowercase() != "tmdb") return this
    return parts.lastOrNull()?.takeIf { part ->
        part.isNotBlank() && part.all { it in '0'..'9' }
    } ?: this
}
