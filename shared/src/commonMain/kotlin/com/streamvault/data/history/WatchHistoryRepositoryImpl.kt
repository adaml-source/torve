package com.streamvault.data.history

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.WatchHistoryEntry
import com.streamvault.domain.repository.WatchHistoryRepository

class WatchHistoryRepositoryImpl(
    private val database: StreamVaultDatabase,
) : WatchHistoryRepository {
    private val queries get() = database.streamVaultQueries

    override suspend fun getRecent(limit: Int): List<WatchHistoryEntry> {
        return queries.getRecentHistory(limit.toLong()).executeAsList().map { it.toDomain() }
    }

    override suspend fun getByDateRange(startMs: Long, endMs: Long): List<WatchHistoryEntry> {
        return queries.getHistoryByDate(startMs, endMs).executeAsList().map { it.toDomain() }
    }

    override suspend fun getAll(): List<WatchHistoryEntry> {
        return queries.getAllHistory().executeAsList().map { it.toDomain() }
    }

    override suspend fun record(entry: WatchHistoryEntry) {
        queries.insertHistory(
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
    }

    override suspend fun delete(id: String) {
        queries.deleteHistory(id)
    }

    override suspend fun clearAll() {
        queries.clearAllHistory()
    }

    override suspend fun getCount(): Long {
        return queries.getHistoryCount().executeAsOne()
    }

    private fun com.streamvault.db.Watch_history.toDomain() = WatchHistoryEntry(
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
}
