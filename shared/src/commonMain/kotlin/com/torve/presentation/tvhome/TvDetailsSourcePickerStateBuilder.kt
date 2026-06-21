package com.torve.presentation.tvhome

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute

/**
 * Pure-shared mapper that builds the [TvSourcePickerState] surface for
 * TvDetailsScreen (Prompt 11C).
 *
 * The detail screen knows three things at Play-tap time:
 *   - [localFilePath]: the finished download's absolute path on this
 *     device, or null if there's no local copy.
 *   - [lanRoute]: the LAN-library handoff (URL + headers), or null if
 *     no manifest entry matches.
 *   - [providerAvailable]: whether the existing fetchStreams flow has
 *     anything to resolve (debrid, addon, IPTV, …).
 *
 * From those it produces a `TvSourcePickerState` honoring the locked
 * [PlaybackRoute] preference order. The Provider option is included
 * only when [providerAvailable] is true so that a missing-debrid /
 * missing-addon environment doesn't surface a dead-end fallback.
 *
 * The provider URL the picker carries is a synthetic
 * `provider://fetch-streams` sentinel — the detail screen never plays
 * this URL directly. Selecting it re-enters the existing
 * `DetailViewModel.fetchStreams()` flow which has its own stream
 * picker for resolution and codec selection. The sentinel matters for
 * `TvSourcePicker.fallbackAfter(...)` matching.
 */
object TvDetailsSourcePickerStateBuilder {

    /** The synthetic URL the picker uses for the "go fetch streams" option. */
    const val PROVIDER_FETCH_SENTINEL_URL: String = "provider://fetch-streams"

    fun build(
        localFilePath: String?,
        lanRoute: PlaybackRoute.LanDesktopStream?,
        providerAvailable: Boolean,
        networkMode: NetworkMode,
        wifiOnlyForLan: Boolean,
        providerIssue: String? = null,
        /**
         * Pass-through to [TvSourcePicker.build]. Default true keeps
         * existing call sites working; the detail screens read this
         * from the active [com.torve.domain.player.PlayerEngine.supportsRequestHeaders]
         * flag and pass the live value so a header-incapable engine
         * doesn't get the LAN row dangled in front of it.
         */
        engineSupportsLanHeaders: Boolean = true,
    ): TvSourcePickerState {
        val localFile = localFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let { PlaybackRoute.LocalFile(it) }
        val provider = if (providerAvailable) {
            PlaybackRoute.ProviderStream(PROVIDER_FETCH_SENTINEL_URL)
        } else {
            null
        }
        return TvSourcePicker.build(
            localFile = localFile,
            lanStream = lanRoute,
            providerStream = provider,
            networkMode = networkMode,
            wifiOnlyForLan = wifiOnlyForLan,
            providerIssue = providerIssue,
            engineSupportsLanHeaders = engineSupportsLanHeaders,
        )
    }

    /**
     * Convenience: recognize whether [option] points to the synthetic
     * "go fetch streams" provider entry. The detail screen uses this
     * to know "the user picked Provider — re-enter fetchStreams"
     * instead of treating it as a real URL to launch.
     */
    fun isProviderFetchSentinel(option: TvSourcePickerOption): Boolean {
        val route = option.route
        return route is PlaybackRoute.ProviderStream &&
            route.url == PROVIDER_FETCH_SENTINEL_URL
    }
}
