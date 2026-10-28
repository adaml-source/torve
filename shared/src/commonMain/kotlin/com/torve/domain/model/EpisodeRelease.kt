package com.torve.domain.model

import kotlinx.datetime.*

data class EpisodeRelease(val showId: Int, val showTitle: String, val season: Int, val episode: Int,
    val date: LocalDate?, val firstAired: Instant? = null) {
    val key: String get() = "$showId:$season:$episode"
}

/** Air-day alerts are useful only once, only for recent, unwatched episodes. */
fun pendingEpisodeReleases(releases: List<EpisodeRelease>, watched: Set<String>, delivered: Set<String>,
    now: Instant, timeZone: TimeZone): List<EpisodeRelease> {
    val today = now.toLocalDateTime(timeZone).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)
    return releases.filter { it.showId > 0 && it.season > 0 && it.episode > 0 && it.key !in watched && it.key !in delivered }
        .filter { release -> release.firstAired?.let { it <= now && now.toEpochMilliseconds() - it.toEpochMilliseconds() <= 36 * 60 * 60_000L }
            ?: (release.date != null && release.date in yesterday..today) }
        .distinctBy { it.key }.sortedWith(compareBy<EpisodeRelease> { it.date }.thenBy { it.showTitle }.thenBy { it.season }.thenBy { it.episode })
}
