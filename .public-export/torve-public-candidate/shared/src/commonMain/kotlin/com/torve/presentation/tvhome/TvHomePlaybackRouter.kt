package com.torve.presentation.tvhome

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityRecord

/**
 * One-click playback router for TV-Home tiles (Prompt 11B).
 *
 * Maps a tile tap into one of three outcomes:
 *   - [TvHomePlaybackDecision.AutoplayLocal] — a fully-resolved local
 *     file path. Caller hands this directly to the player engine.
 *   - [TvHomePlaybackDecision.AutoplayLan] — a LAN match exists for
 *     this title. Caller mints the per-item token via
 *     `LanLibraryConsumer.findLanRoute(...)` and then launches the
 *     player. Token-minting is deliberately *not* done here so the
 *     router stays fast (no IO) and unit-testable from common.
 *   - [TvHomePlaybackDecision.OpenDetail] — anything else. Provider
 *     streams need source disambiguation (resolution, container,
 *     unrestrict step) the user should see consciously, so we punt to
 *     the detail screen.
 *
 * **Cellular guard**: respects [wifiOnlyForLan]. If the device is on
 * cellular and wifi-only LAN is enabled, LAN never autoplays — we open
 * the detail screen instead so the user sees a labeled choice rather
 * than a silently-dropped LAN candidate.
 *
 * Pure presentation logic — DI takes only [DownloadRepository] (for the
 * local-file path lookup); everything else flows in as a snapshot.
 */
class TvHomePlaybackRouter(
    private val downloadRepository: DownloadRepository,
) {

    suspend fun resolve(
        item: MediaItem,
        availability: Map<Int, SourceAvailabilityRecord>,
        lanTitlesLowercase: Set<String>,
        networkMode: NetworkMode,
        wifiOnlyForLan: Boolean,
    ): TvHomePlaybackDecision {
        // 1. Local file — fastest, always wins. Use the download repo's
        // by-mediaId lookup so we don't have to scan the catalog.
        val tmdbId = item.tmdbId
        if (tmdbId != null) {
            val download = runCatching {
                downloadRepository.getDownloadByMediaId(tmdbId.toString())
            }.getOrNull()
            val path = download?.takeIf { it.status == DownloadStatus.COMPLETED }?.filePath
            if (!path.isNullOrBlank()) {
                return TvHomePlaybackDecision.AutoplayLocal(path)
            }
        }

        // 2. LAN — fall through unless cellular guard suppresses it.
        val canUseLan = !(wifiOnlyForLan && networkMode == NetworkMode.CELLULAR)
        val titleKey = item.title.trim().lowercase()
        val lanLooksReady = canUseLan && titleKey.isNotEmpty() && titleKey in lanTitlesLowercase
        if (lanLooksReady) {
            return TvHomePlaybackDecision.AutoplayLan(item.title)
        }

        // 3. Anything else — let the detail screen run the full picker.
        // We don't autoplay providers from the home tile because they
        // need source disambiguation (resolution, container, hardlink
        // vs unrestrict, etc.) the user should see.
        return TvHomePlaybackDecision.OpenDetail
    }

    /** True iff [item] has at least one playable signal (excluding watch-history). */
    fun hasPlayablePath(item: MediaItem, availability: Map<Int, SourceAvailabilityRecord>): Boolean {
        val tmdbId = item.tmdbId ?: return false
        val record = availability[tmdbId] ?: return false
        return record.signals.any { it.kind != SourceAvailabilityKind.WATCH_HISTORY }
    }
}

/**
 * Helper: given a pre-resolved [PlaybackRoute.LocalFile] (test injection
 * helper), wrap it as an autoplay decision. Lets call sites that already
 * have the route avoid going back through the router.
 */
fun PlaybackRoute.LocalFile.toAutoplayDecision(): TvHomePlaybackDecision.AutoplayLocal =
    TvHomePlaybackDecision.AutoplayLocal(this.absolutePath)

/**
 * One-click outcome from a TV-Home tile tap. The TV layer consumes
 * this and either launches the player directly (Autoplay variants) or
 * navigates to the detail screen for explicit source picking.
 */
sealed interface TvHomePlaybackDecision {
    /** Local file at [absolutePath] — caller wraps in PlaybackRoute.LocalFile. */
    data class AutoplayLocal(val absolutePath: String) : TvHomePlaybackDecision

    /**
     * LAN match exists for this title. Caller calls
     * `LanLibraryConsumer.findLanRoute(title, seasonNumber, episodeNumber)`
     * to mint a token and launch the player.
     */
    data class AutoplayLan(
        val title: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    ) : TvHomePlaybackDecision

    /** Open the detail screen — let the user pick a source consciously. */
    data object OpenDetail : TvHomePlaybackDecision
}
