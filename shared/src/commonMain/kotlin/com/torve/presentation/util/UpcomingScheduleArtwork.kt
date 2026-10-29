package com.torve.presentation.util

import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

internal suspend fun hydrateUpcomingScheduleArtwork(
    entries: List<Pair<TraktCalendarEpisode, MediaItem>>,
    metadataRepo: MetadataRepository,
    detailLookupLimit: Int = 24,
): List<MediaItem> {
    if (entries.isEmpty()) return emptyList()

    val detailsByKey = supervisorScope {
        entries
            .distinctBy { (episode, _) -> episode.scheduleArtworkKey() }
            .take(detailLookupLimit)
            .map { (episode, _) ->
                async {
                    val detail = loadScheduleShowDetail(episode, metadataRepo) ?: return@async null
                    episode.scheduleArtworkKey() to detail
                }
            }
            .mapNotNull { deferred -> deferred.await() }
            .toMap()
    }

    if (detailsByKey.isEmpty()) return entries.map { (_, item) -> item }

    return entries.map { (episode, item) ->
        val detail = detailsByKey[episode.scheduleArtworkKey()] ?: return@map item
        item.mergeScheduleDetail(detail)
    }
}

private suspend fun loadScheduleShowDetail(
    episode: TraktCalendarEpisode,
    metadataRepo: MetadataRepository,
): MediaItem? {
    episode.showTmdbId?.let { tmdbId ->
        val detail = runCatching { metadataRepo.getDetail("tv", tmdbId) }.getOrNull()
        if (detail != null) return detail
    }

    val title = episode.showTitle.trim()
    if (title.isBlank()) return null

    val matches = runCatching {
        metadataRepo.searchMultiPaged(query = title, page = 1, type = "tv").items
    }.getOrDefault(emptyList())

    val normalizedTitle = title.normalizedScheduleTitle()
    val candidate = matches.firstOrNull {
        it.type == MediaType.SERIES && it.title.normalizedScheduleTitle() == normalizedTitle
    } ?: matches.firstOrNull { it.type == MediaType.SERIES }

    return candidate?.tmdbId?.let { tmdbId ->
        runCatching { metadataRepo.getDetail("tv", tmdbId) }.getOrNull()
    } ?: candidate
}

private fun MediaItem.mergeScheduleDetail(detail: MediaItem): MediaItem =
    copy(
        tmdbId = tmdbId ?: detail.tmdbId,
        imdbId = imdbId ?: detail.imdbId,
        adult = adult ?: detail.adult,
        year = year ?: detail.year,
        overview = overview.takeUnless { it.isNullOrBlank() } ?: detail.overview,
        posterUrl = posterUrl.takeUnless { it.isNullOrBlank() } ?: detail.posterUrl,
        backdropUrl = backdropUrl.takeUnless { it.isNullOrBlank() } ?: detail.backdropUrl,
        logoUrl = logoUrl.takeUnless { it.isNullOrBlank() } ?: detail.logoUrl,
        rating = rating ?: detail.rating,
        voteCount = voteCount ?: detail.voteCount,
        runtime = runtime ?: detail.runtime,
        genres = genres.ifEmpty { detail.genres },
        genreIds = genreIds.ifEmpty { detail.genreIds },
        cast = cast.ifEmpty { detail.cast },
        studios = studios.ifEmpty { detail.studios },
        status = status ?: detail.status,
        trailerKey = trailerKey ?: detail.trailerKey,
        seasons = seasons.ifEmpty { detail.seasons },
        tagline = tagline ?: detail.tagline,
        popularity = popularity ?: detail.popularity,
        ratings = ratings ?: detail.ratings,
    )

private fun TraktCalendarEpisode.scheduleArtworkKey(): String =
    showTmdbId?.let { "tmdb:$it" } ?: "title:${showTitle.normalizedScheduleTitle()}"

private fun String.normalizedScheduleTitle(): String =
    trim()
        .lowercase()
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .split(' ', '\t', '\n', '\r')
        .filter { it.isNotBlank() }
        .joinToString(" ")

/** A dated calendar cache can still be incomplete when an artwork provider was unavailable. */
internal fun List<MediaItem>.needsSchedulePresentationRefresh(): Boolean = any { item ->
    item.id.startsWith("trakt-calendar:") &&
        ((item.posterUrl.isNullOrBlank() && item.backdropUrl.isNullOrBlank()) ||
            (item.releaseDate.isNullOrBlank() && item.id.split(":", limit = 5).getOrNull(4).isNullOrBlank()))
}

internal fun List<MediaItem>.mergeScheduleVisualsFrom(previous: List<MediaItem>): List<MediaItem> {
        if (isEmpty() || previous.isEmpty()) return this
        val usable = previous.filter { !it.posterUrl.isNullOrBlank() || !it.backdropUrl.isNullOrBlank() }
        val previousById = usable.associateBy { it.id }
        val previousByTmdb = usable.mapNotNull { item ->
            item.tmdbId?.let { tmdbId -> tmdbId to item }
        }.toMap()
        val previousByTitle = usable.groupBy { it.title.normalizedScheduleTitle() }
        return map { item ->
            if (!item.id.startsWith("trakt-calendar:")) return@map item
            val cached = previousById[item.id]
                ?: item.tmdbId?.let { previousByTmdb[it] }
                ?: previousByTitle[item.title.substringBefore(" - S").normalizedScheduleTitle()]?.firstOrNull { candidate ->
                    candidate.type == item.type &&
                        (item.tmdbId == null || candidate.tmdbId == null || item.tmdbId == candidate.tmdbId) &&
                        (item.year == null || candidate.year == null || item.year == candidate.year)
                }
                ?: return@map item
            item.copy(
                posterUrl = item.posterUrl.takeUnless { it.isNullOrBlank() } ?: cached.posterUrl,
                backdropUrl = item.backdropUrl.takeUnless { it.isNullOrBlank() } ?: cached.backdropUrl,
                logoUrl = item.logoUrl.takeUnless { it.isNullOrBlank() } ?: cached.logoUrl,
                overview = item.overview.takeUnless { it.isNullOrBlank() } ?: cached.overview,
                rating = item.rating ?: cached.rating,
                ratings = item.ratings ?: cached.ratings,
                genres = item.genres.ifEmpty { cached.genres },
                runtime = item.runtime ?: cached.runtime,
            )
        }
    }

