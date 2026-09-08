package com.torve.data.trakt

import kotlinx.datetime.Instant

internal data class TraktWatchedEpisodeMarker(
    val season: Int,
    val episode: Int,
    val watchedAtMs: Long,
)

/** Converts Trakt's show summary into a stable, reset-aware episode set. */
internal fun TraktWatchedShowResponse.episodeMarkers(): List<TraktWatchedEpisodeMarker> {
    val resetAtMs = resetAt.toEpochMillisecondsOrNull()
    val showFallbackMs = lastWatchedAt.toEpochMillisecondsOrNull()
        ?: lastUpdatedAt.toEpochMillisecondsOrNull()
        ?: return emptyList()
    return seasons.flatMap { season ->
        if (season.number < 0) return@flatMap emptyList()
        season.episodes.mapNotNull { episode ->
            if (episode.number <= 0 || episode.plays <= 0) return@mapNotNull null
            val watchedAtMs = episode.lastWatchedAt.toEpochMillisecondsOrNull() ?: showFallbackMs
            if (resetAtMs != null && watchedAtMs < resetAtMs) return@mapNotNull null
            TraktWatchedEpisodeMarker(
                season = season.number,
                episode = episode.number,
                watchedAtMs = watchedAtMs,
            )
        }
    }
}

internal fun TraktWatchedShowResponse.latestEpisodeMarker(): TraktWatchedEpisodeMarker? =
    episodeMarkers().maxWithOrNull(
        compareBy<TraktWatchedEpisodeMarker> { it.watchedAtMs }
            .thenBy { it.season }
            .thenBy { it.episode },
    )

internal fun traktWatchedHistoryId(
    showIdentity: String,
    season: Int,
    episode: Int,
): String = "trakt_watched_${showIdentity}_${season}_$episode"

internal fun shouldApplyRemoteProgress(
    localUpdatedAtMs: Long?,
    remoteUpdatedAtMs: Long,
): Boolean = localUpdatedAtMs == null || remoteUpdatedAtMs > localUpdatedAtMs

private fun String?.toEpochMillisecondsOrNull(): Long? {
    val value = this?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
}
