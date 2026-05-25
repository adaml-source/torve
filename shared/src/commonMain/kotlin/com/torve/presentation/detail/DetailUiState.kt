package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.data.usenet.model.UsenetCandidateStates
import com.torve.data.usenet.model.UsenetResolvedStream
import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.Season
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.WatchProgress

/** Canonical key for episode tracking — single source of truth. */
fun episodeKey(season: Int, episode: Int): String = "s${season}e${episode}"

/**
 * UI-facing snapshot of an in-flight "preparing" session. The VM owns the
 * retry coroutine; this state is strictly what the overlay needs to paint
 * itself. Updated on each probe tick so the attempt counter advances.
 */
data class PreparingStreamState(
    /** Release title shown in the overlay header. */
    val title: String,
    /** Epoch ms at which the flow entered preparing. Drives the elapsed-time ticker. */
    val startedAt: Long,
    /** 1-indexed attempt counter — useful for diagnostics and UI progress. */
    val attempt: Int,
    /** Display name of the upstream cloud client (e.g. "TorBox"). Fallback: "Your cloud service". */
    val serviceName: String,
    /** When false, the overlay hides the Cancel action (reserved for flows we shouldn't interrupt). */
    val canCancel: Boolean = true,
)

enum class NextEpisodeMode {
    RESUME_IN_PROGRESS,
    PLAY_FIRST_UNWATCHED,
    PLAY_FROM_START,
}

data class NextEpisodeInfo(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val progressPercent: Float? = null,
    val mode: NextEpisodeMode,
) {
    val label: String
        get() = when (mode) {
            NextEpisodeMode.RESUME_IN_PROGRESS -> {
                val pct = progressPercent?.let { " (${(it * 100).toInt()}%)" } ?: ""
                "Resume S${season} E${episode}$pct"
            }
            NextEpisodeMode.PLAY_FIRST_UNWATCHED -> "Play S${season} E${episode}"
            NextEpisodeMode.PLAY_FROM_START -> "Play S${season} E${episode}"
        }
}

data class DetailUiState(
    val isLoading: Boolean = true,
    val mediaItem: MediaItem? = null,
    val similar: List<MediaItem> = emptyList(),
    val isLoadingSimilar: Boolean = false,
    val similarError: String? = null,
    val availability: AvailabilityResult? = null,
    val isLoadingAvailability: Boolean = false,
    val availabilityError: String? = null,
    val error: String? = null,
    // Streams
    val streams: List<ParsedStream> = emptyList(),
    val startupCandidates: List<StartupCandidate> = emptyList(),
    val isLoadingStreams: Boolean = false,
    val isLoadingMoreSources: Boolean = false,
    val streamsError: String? = null,
    val streamsErrorHint: String? = null,
    val streamFilterHiddenCount: Int = 0,
    val playbackStartupStatus: PlaybackStartupStatus = PlaybackStartupStatus(),
    // Resolved playback URL
    val resolvedStream: ResolvedStream? = null,
    val isResolving: Boolean = false,
    val resolveError: String? = null,
    /**
     * Non-null while an addon-hosted stream is waiting on its upstream
     * cloud download client (Panda's 504 nzb_not_ready). The VM owns a
     * retry loop that re-probes every 15s; [PreparingStreamState.attempt]
     * increments with each cycle. When the probe flips to Ready the VM
     * clears this and sets [resolvedStream], which triggers player launch.
     * While this is non-null, [resolveError] stays null — the stream is
     * not broken, just not ready.
     */
    val preparing: PreparingStreamState? = null,
    // Watch progress
    val watchProgress: WatchProgress? = null,
    // Stream picker visibility
    val showStreamPicker: Boolean = false,
    // Watched status
    val isMarkedWatched: Boolean = false,
    // Trakt user rating (1..10)
    val userRating: Int? = null,
    // Season/Episode details
    val selectedSeason: Int = 1,
    val seasonDetail: Season? = null,
    val isLoadingSeasonDetail: Boolean = false,
    val seasonDetailError: String? = null,
    // Episode tracking: keys like "s1e1", "s1e2", etc.
    val watchedEpisodes: Set<String> = emptySet(),
    val isInLibrary: Boolean = false,
    // Track what we're fetching streams for (for download labeling)
    val streamContextSeason: Int? = null,
    val streamContextEpisode: Int? = null,
    // Next episode resolution — drives play button label and action
    val nextEpisode: NextEpisodeInfo? = null,
    // Auto-play
    val autoPlayStream: ParsedStream? = null,
    val autoPlayMessage: String? = null,
    val fallbackAttempt: Int = 0,
    val autoPlayFailed: Boolean = false,
    val kodiSendResult: String? = null,
    /**
     * Sidecar map of Usenet-candidate UI state, keyed by backend
     * candidate id. The source sheet reads this alongside [streams] to
     * render the simplified Ready / Preparing / Unavailable pill for
     * rows that come from the NzbDAV resolver.
     *
     * Empty map ⇒ no Usenet candidates active for this title (default).
     */
    val usenetCandidates: UsenetCandidateStates = emptyMap(),
    /**
     * Non-null when a Usenet `/resolve` call returned `ready` and the UI
     * should launch playback immediately. The UI layer reads the opaque
     * handoff URL and calls the existing `onPlayClick(url, …)`
     * entrypoint byte-for-byte, then calls back into the VM to clear
     * this field. The handoff URL is self-authenticating under the live
     * contract — no headers, no extra staging needed.
     *
     * Cleared via [DetailViewModel.consumeUsenetPlaybackIntent].
     */
    val usenetPlaybackIntent: UsenetResolvedStream? = null,
) {
    /**
     * Play button label — derived from nextEpisode for TV shows, static for
     * movies. When the user paused mid-playback (including on another device
     * and resolved via WatchStateRemoteSource), the label includes the actual
     * timestamp so the user knows where they'll resume, not just that they'll
     * resume.
     */
    val primaryPlayLabel: String
        get() = when {
            mediaItem?.type == com.torve.domain.model.MediaType.SERIES && nextEpisode != null -> nextEpisode.label
            watchProgress != null && watchProgress.progressPercent > 0.02f && watchProgress.progressPercent < 0.9f -> {
                val stamp = formatHms(watchProgress.positionMs)
                if (stamp != null) "Resume from $stamp" else "Resume"
            }
            else -> "Play"
        }
}

/**
 * Format a position in milliseconds as `h:mm:ss` (or `m:ss` when under an
 * hour). Returns null for zero/negative inputs so the caller can fall back
 * to the plain "Resume" label.
 */
private fun formatHms(positionMs: Long): String? {
    if (positionMs <= 0L) return null
    val totalSeconds = positionMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
