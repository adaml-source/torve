package com.streamvault.data.watchlist

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchlistItem
import com.streamvault.domain.repository.WatchlistRepository

class WatchlistRepositoryImpl(
    private val database: StreamVaultDatabase,
) : WatchlistRepository {

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
    }

    override suspend fun remove(mediaId: String) {
        database.streamVaultQueries.removeFromWatchlist(mediaId)
    }

    override suspend fun clear() {
        database.streamVaultQueries.clearWatchlist()
    }
}
