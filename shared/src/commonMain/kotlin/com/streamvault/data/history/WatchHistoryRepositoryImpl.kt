package com.streamvault.data.history

import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.data.metadata.TmdbMappers
import com.streamvault.data.trakt.TraktClient
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.WatchHistoryEntry
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.datetime.Instant

class WatchHistoryRepositoryImpl(
    private val database: StreamVaultDatabase,
    private val traktClient: TraktClient,
    private val prefsRepo: PreferencesRepository,
    private val tmdbClient: TmdbApiClient,
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

    override suspend fun syncFromTrakt() {
        try {
            val token = prefsRepo.getString(SettingsViewModel.KEY_TRAKT_ACCESS_TOKEN) ?: return
            if (token.isBlank()) return

            val historyItems = traktClient.getHistory(token, limit = 100)
            if (historyItems.isEmpty()) return

            val localIds = queries.getAllHistory().executeAsList()
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
