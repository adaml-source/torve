package com.torve.presentation.tvhome

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.lanlibrary.PlaybackRoutePreference

/**
 * Build a TV-friendly source picker around the locked
 * [PlaybackRoutePreference] order:
 *   LocalFile > LanDesktopStream > ProviderStream > ReDownload.
 *
 * The caller (a Compose source-picker sheet) renders [options]
 * vertically — first item is the best, the rest are fallbacks.
 * D-pad UP / DOWN walks the list; OK starts playback. The state model
 * is platform-clean so the picker logic is unit-testable without
 * Compose.
 */
object TvSourcePicker {

    /**
     * Build the picker from optional candidates. Honors the
     * cellular + wifi-only guard the same way
     * `PlaybackRoutePreference.of` does.
     */
    fun build(
        localFile: PlaybackRoute.LocalFile? = null,
        lanStream: PlaybackRoute.LanDesktopStream? = null,
        providerStream: PlaybackRoute.ProviderStream? = null,
        networkMode: NetworkMode = NetworkMode.UNKNOWN,
        wifiOnlyForLan: Boolean = true,
        providerIssue: String? = null,
        /**
         * When false, suppress the LAN tier — the active player engine
         * cannot stage the `X-Torve-Lan-Auth` header that the desktop
         * hub requires, so picking LAN would 401 before any byte
         * arrives. Default true preserves existing behaviour for
         * engines that DO support headers (ExoPlayer, MPV).
         *
         * Drives Prompt 24's "if engine cannot honor headers, LAN
         * option should be hidden or marked unavailable, not allowed
         * to 401" requirement.
         */
        engineSupportsLanHeaders: Boolean = true,
    ): TvSourcePickerState {
        val cellularBlocked = wifiOnlyForLan && networkMode == NetworkMode.CELLULAR
        val effectiveLan = if (cellularBlocked || !engineSupportsLanHeaders) null else lanStream
        val opts = mutableListOf<TvSourcePickerOption>()
        localFile?.let {
            opts += TvSourcePickerOption(
                label = "Downloaded",
                hint = "Plays from your local file. Fastest, offline.",
                route = it,
                tier = TvSourceTier.BEST,
            )
        }
        effectiveLan?.let {
            opts += TvSourcePickerOption(
                label = "On desktop (LAN)",
                hint = "Streams over your home network from another Torve device.",
                route = it,
                tier = if (opts.isEmpty()) TvSourceTier.BEST else TvSourceTier.FALLBACK,
            )
        }
        providerStream?.let {
            opts += TvSourcePickerOption(
                label = "Provider",
                hint = "Streams from your debrid / addon source.",
                route = it,
                tier = if (opts.isEmpty()) TvSourceTier.BEST else TvSourceTier.FALLBACK,
            )
        }
        if (opts.isEmpty()) {
            opts += TvSourcePickerOption(
                label = "Download to play",
                hint = "No source is ready right now. Download to add a local copy.",
                route = PlaybackRoute.ReDownload,
                tier = TvSourceTier.RE_DOWNLOAD,
            )
        }
        return TvSourcePickerState(
            options = opts,
            providerIssue = providerIssue,
            networkMode = networkMode,
            wifiOnlyForLan = wifiOnlyForLan,
        )
    }

    /**
     * Deterministic "what should autoplay when the user taps OK on a
     * tile from Home" route. Returns null when nothing is playable
     * (i.e. the picker would land on `ReDownload`) — caller falls back
     * to the detail screen instead of trying to launch the player.
     */
    fun autoPlayBest(state: TvSourcePickerState): PlaybackRoute? {
        val first = state.options.firstOrNull() ?: return null
        if (first.tier == TvSourceTier.RE_DOWNLOAD) return null
        return first.route
    }

    /**
     * Build the next-best route after the current one fails. Keeps the
     * fallback chain predictable: drop the failing option, return the
     * next-tier option's route. UI surfaces the chosen fallback with
     * an explanatory hint copy.
     */
    fun fallbackAfter(
        state: TvSourcePickerState,
        failing: PlaybackRoute,
    ): TvSourcePickerOption? {
        val idx = state.options.indexOfFirst { it.route::class == failing::class && it.route == failing }
        if (idx < 0) return null
        val remaining = state.options.drop(idx + 1).filter { it.tier != TvSourceTier.RE_DOWNLOAD }
        return remaining.firstOrNull()
    }
}

/**
 * One source the user can pick. UI sorts these from best (top) to
 * worst, aligned with the locked [PlaybackRoutePreference] order.
 */
data class TvSourcePickerOption(
    val label: String,
    val hint: String,
    val route: PlaybackRoute,
    val tier: TvSourceTier,
)

enum class TvSourceTier { BEST, FALLBACK, RE_DOWNLOAD }

data class TvSourcePickerState(
    val options: List<TvSourcePickerOption>,
    val providerIssue: String?,
    val networkMode: NetworkMode,
    val wifiOnlyForLan: Boolean,
) {
    /** True iff the best option will start playback immediately. */
    val canAutoPlay: Boolean
        get() = options.firstOrNull()?.tier?.let { it != TvSourceTier.RE_DOWNLOAD } == true
}
