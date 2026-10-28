package com.torve.presentation.watching

import com.torve.domain.model.*
import com.torve.data.addon.StremioMeta
import com.torve.presentation.apple.AppleCatalogueDetail
import kotlinx.coroutines.CancellationException

internal fun watchingShowKey(show: MediaItem): String = show.tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
    ?: show.imdbId?.let { "imdb:$it" } ?: show.id

internal fun watchingShowAliases(show: MediaItem): Set<String> = buildSet {
    add(show.id)
    show.tmdbId?.takeIf { it > 0 }?.let { add("tmdb:$it") }
    show.imdbId?.let { add("imdb:$it") }
}

internal data class WatchingShowSeed(val show: MediaItem, val mediaIds: Set<String>, val activityAt: Long)

/** Keep provider IDs and bridge their saved TMDB aliases before grouping episode history. */
internal fun watchingShowSeeds(progress: List<WatchProgress>, history: List<WatchHistoryEntry>,
    watchlist: List<WatchlistItem>): List<WatchingShowSeed> {
    val saved = watchlist.filter { it.mediaType == MediaType.SERIES }
    val aliases = saved.mapNotNull { row -> (row.imdbId ?: row.mediaId.extractImdbIdOrNull())
        ?.let { it to row.tmdbId.takeIf { id -> id > 0 } } }.toMap()
    fun item(id: String, title: String, poster: String?, backdrop: String?, tmdb: Int? = null, imdb: String? = null): MediaItem {
        val external = imdb ?: id.extractImdbIdOrNull()
        val numeric = tmdb?.takeIf { it > 0 } ?: external?.let { aliases[it] } ?: id.extractTmdbIdOrNull()
        return MediaItem(id = external ?: id, type = MediaType.SERIES, title = title,
            tmdbId = numeric, imdbId = external, posterUrl = poster, backdropUrl = backdrop)
    }
    val candidates = buildList {
        progress.filter { it.mediaType == MediaType.SERIES && !it.isContentPlaceholder && it.mediaId.isCatalogPlaybackIdentity() }.forEach {
            add(WatchingShowSeed(item(it.mediaId, it.showTitle ?: it.title, it.posterUrl, it.backdropUrl), setOf(it.mediaId), it.updatedAt))
        }
        history.filter { (it.mediaType.equals("series", true) || it.mediaType.equals("tv", true)) && !it.isContentPlaceholder && it.mediaId.isCatalogPlaybackIdentity() }.forEach {
            add(WatchingShowSeed(item(it.mediaId, it.showTitle ?: it.title, it.posterUrl, it.backdropUrl), setOf(it.mediaId), it.watchedAt))
        }
        saved.filter { it.mediaId.isCatalogPlaybackIdentity() }.forEach { add(WatchingShowSeed(item(it.mediaId, it.title, it.posterUrl, it.backdropUrl, it.tmdbId, it.imdbId), setOf(it.mediaId), it.addedAt)) }
    }
    return candidates.groupBy { watchingShowKey(it.show) }.values.map { rows ->
        val latest = rows.maxBy { it.activityAt }
        latest.copy(show = latest.show.copy(
            imdbId = rows.firstNotNullOfOrNull { it.show.imdbId },
            posterUrl = rows.firstNotNullOfOrNull { it.show.posterUrl?.takeIf(String::isNotBlank) },
            backdropUrl = rows.firstNotNullOfOrNull { it.show.backdropUrl?.takeIf(String::isNotBlank) }),
            mediaIds = rows.flatMap { it.mediaIds }.toSet())
    }.sortedByDescending { it.activityAt }
}

internal data class WatchingSchedule(val episodes: List<WatchingEpisode>, val failedSeasons: Set<Int>)

/** One unavailable season must not discard a show's other schedules or resume point. */
internal suspend fun watchingSchedule(show: MediaItem, seasonDetail: suspend (Int, Int) -> Season): WatchingSchedule {
    val failures = mutableSetOf<Int>()
    val episodes = show.seasons.filter { it.seasonNumber > 0 }.flatMap { season ->
        val full = if (season.episodes.isNotEmpty()) season else try {
            val id = show.tmdbId ?: error("Schedule unavailable")
            seasonDetail(id, season.seasonNumber)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures += season.seasonNumber; null }
        full?.episodes.orEmpty().map { episode -> WatchingEpisode(show, season.seasonNumber, episode,
            episode.airDate?.let { runCatching { kotlinx.datetime.LocalDate.parse(it.take(10)) }.getOrNull() }) }
    }
    return WatchingSchedule(episodes, failures)
}

internal fun savedWatchingResume(show: MediaItem, progress: WatchProgress?, watched: Set<Pair<Int, Int>> = emptySet()): WatchingEpisode? {
    if (progress == null || progress.isWatched() || progress.positionMs <= 0) return null
    val season = progress.seasonNumber?.takeIf { it > 0 } ?: return null
    val episode = progress.episodeNumber?.takeIf { it > 0 } ?: return null
    if (season to episode in watched) return null
    return WatchingEpisode(show, season, Episode(episode), date = null, resume = true,
        positionMs = progress.positionMs, progressPercent = progress.progressPercent)
}

/** Resolve through selected metadata extensions; TMDB remains an explicit optional provider. */
internal suspend fun resolveWatchingMetadata(seed: MediaItem, addons: List<InstalledAddon>,
    fetchMeta: suspend (InstalledAddon, String, String) -> StremioMeta?,
    tmdbAllowed: Boolean, fetchTmdb: suspend (MediaItem) -> MediaItem?): MediaItem =
    AppleCatalogueDetail.resolve(seed.copy(id = seed.imdbId ?: seed.id), addons, fetchMeta,
        enrich = { if (tmdbAllowed) fetchTmdb(it) else null })
