package com.torve.domain.model

data class WatchHistoryEntry(
    val id: String,
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val watchedAt: Long,
    val durationWatchedMs: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val showTitle: String?,
    val isContentPlaceholder: Boolean = false,
)

/** Native playback-start rows are useful history, but are not completed episodes.
 * Explicit/manual/imported marks keep their existing meaning, including zero-runtime marks. */
fun WatchHistoryEntry.countsAsWatchedEpisode(): Boolean {
    val season = seasonNumber ?: return false
    val episode = episodeNumber ?: return false
    return durationWatchedMs > 0 || id != "${mediaId}_s${season}e${episode}_${watchedAt}"
}
