package com.torve.presentation.detail

import com.torve.domain.model.MediaType

/** The label and playback action must refer to the same selected episode. */
data class DetailPlaybackTarget(
    val season: Int? = null,
    val episode: Int? = null,
    val isResume: Boolean = false,
    val positionMs: Long? = null,
)

val DetailUiState.primaryPlaybackTarget: DetailPlaybackTarget
    get() {
        val series = mediaItem?.type == MediaType.SERIES
        val explicitEpisode = streamContextSeason != null && streamContextEpisode != null
        val season = if (series) {
            if (explicitEpisode) streamContextSeason else nextEpisode?.season
        } else null
        val episode = if (series) {
            if (explicitEpisode) streamContextEpisode else nextEpisode?.episode
        } else null
        val progress = watchProgress?.takeIf {
            it.positionMs > 0 && it.progressPercent > 0.02f && !it.isWatched() &&
                (!series || (season != null && episode != null &&
                    it.seasonNumber == season && it.episodeNumber == episode))
        }
        val nextIsResume = series && nextEpisode?.let {
            it.season == season && it.episode == episode && it.mode == NextEpisodeMode.RESUME_IN_PROGRESS
        } == true
        return DetailPlaybackTarget(season, episode, progress != null || nextIsResume, progress?.positionMs)
    }

fun playbackPositionLabel(positionMs: Long): String {
    val seconds = (positionMs.coerceAtLeast(0) / 1_000L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = (seconds % 60L).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$remainder"
    else "$minutes:$remainder"
}

/** A completed legacy/imported progress row uses the same 85% cutoff as the recorder.
 * A verified false value still protects against marking a seek as watched. */
internal fun preferredInProgressEpisode(item: com.torve.domain.model.MediaItem,
    progress: List<com.torve.domain.model.WatchProgress>): com.torve.domain.model.WatchProgress? = progress
    .filter { it.seasonNumber != null && it.episodeNumber != null }
    .filter { it.showTitle == item.title || it.mediaId == item.id }
    .filter { it.progressPercent > .02f && !it.isWatched() }
    .maxByOrNull { it.updatedAt }
