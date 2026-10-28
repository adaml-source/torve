package com.torve.android.tv.screens

import com.torve.presentation.detail.NextEpisodeInfo
import com.torve.presentation.detail.NextEpisodeMode

/** Metadata never moves the series episode-return target below the fold. */
internal fun tvDetailsContentRowKeys(
    isSeries: Boolean,
    hasTagline: Boolean,
    hasGenres: Boolean,
    hasOverview: Boolean,
    hasDirector: Boolean,
    hasCast: Boolean,
): List<String> = buildList {
    add("hero")
    if (isSeries) add("episodes")
    if (hasTagline) add("tagline")
    if (hasGenres) add("genres")
    if (hasOverview) add("overview")
    if (hasDirector) add("director")
    if (hasCast) add(if (isSeries) "cast_secondary" else "cast")
}

internal fun tvDetailsRelatedReturnRow(isSeries: Boolean, hasCast: Boolean, hasDirectorLink: Boolean): String = when {
    hasCast -> if (isSeries) "cast_secondary" else "cast"
    hasDirectorLink -> "director"
    else -> "hero"
}

internal fun tvSeriesPlayActionLabel(
    nextEpisode: NextEpisodeInfo?,
    contextSeason: Int?,
    contextEpisode: Int?,
): String {
    val season = contextSeason ?: nextEpisode?.season ?: return "Watch now"
    val episode = contextEpisode ?: nextEpisode?.episode ?: return "Watch now"
    val resume = nextEpisode != null && nextEpisode.season == season && nextEpisode.episode == episode &&
        nextEpisode.mode == NextEpisodeMode.RESUME_IN_PROGRESS
    return "${if (resume) "Resume" else "Watch"} S$season E$episode"
}

internal fun tvSeriesWatchedSummary(watchedEpisodes: Set<String>): String? {
    val count = watchedEpisodes.count { it.matches(regularEpisodeKey) }
    return count.takeIf { it > 0 }?.let { "$it episode${if (it == 1) "" else "s"} watched" }
}

private val regularEpisodeKey = Regex("s[1-9][0-9]*e[1-9][0-9]*")
