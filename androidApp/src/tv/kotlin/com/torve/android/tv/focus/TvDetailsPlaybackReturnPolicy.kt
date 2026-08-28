package com.torve.android.tv.focus

internal sealed interface TvDetailsPlaybackReturnTarget {
    data object PrimaryAction : TvDetailsPlaybackReturnTarget
    data class Episode(val season: Int, val episode: Int) : TvDetailsPlaybackReturnTarget
}

internal fun resolveTvDetailsPlaybackReturnTarget(
    isSeries: Boolean,
    originSeason: Int,
    originEpisode: Int,
): TvDetailsPlaybackReturnTarget {
    return if (isSeries && originSeason > 0 && originEpisode > 0) {
        TvDetailsPlaybackReturnTarget.Episode(originSeason, originEpisode)
    } else {
        TvDetailsPlaybackReturnTarget.PrimaryAction
    }
}
