package com.torve.domain.lanlibrary

/**
 * The route preference model the player consults before opening a
 * stream. Order is locked by [PlaybackRoutePreference.pick] — local
 * first, then LAN, then provider, then a re-download prompt.
 *
 * Pure data; no IO, no platform deps.
 */
sealed interface PlaybackRoute {
    /** A real path on the local filesystem — fastest, offline-safe. */
    data class LocalFile(val absolutePath: String) : PlaybackRoute

    /**
     * An authenticated LAN URL the desktop instance is serving (or a
     * peer is serving). [url] embeds the publisher-issued per-item
     * `?token=...`; [headers] carry the hub-level
     * `X-Torve-Lan-Auth` secret that the player must attach to every
     * request. Players that can't attach headers MUST skip this
     * route — they will get 401 otherwise.
     */
    data class LanDesktopStream(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : PlaybackRoute

    /** Original provider URL — debrid, addon, IPTV, anything else. */
    data class ProviderStream(val url: String) : PlaybackRoute

    /** Nothing playable right now — UI should offer a download CTA. */
    data object ReDownload : PlaybackRoute
}

/**
 * Network mode the consumer device is currently on. Used by
 * [PlaybackRoutePreference.of] to apply the mobile-data guard:
 * a CELLULAR client with `wifiOnly = true` won't pick a LAN-stream
 * route (LAN streams travel over the local interface; the guard
 * exists in case the OS routes them through a captive proxy or
 * the user is tethered through cellular).
 */
enum class NetworkMode { WIFI, CELLULAR, ETHERNET, UNKNOWN }

/**
 * One media's set of candidate routes. Use [pick] to pull the preferred
 * route per the locked order.
 */
data class PlaybackRoutePreference(
    val candidates: List<PlaybackRoute>,
) {
    fun pick(): PlaybackRoute = pickInOrder(candidates)

    companion object {
        /**
         * Build a preference from optional inputs in source order. Nulls
         * are skipped. Callers don't have to know the priority — the
         * priority lives entirely in [pickInOrder].
         *
         * @param networkMode the consumer device's current network mode.
         * @param wifiOnlyForLan when true, refuse a LAN stream when the
         * device is on cellular. Pass false to ignore the guard.
         */
        fun of(
            localFile: PlaybackRoute.LocalFile? = null,
            lanStream: PlaybackRoute.LanDesktopStream? = null,
            providerStream: PlaybackRoute.ProviderStream? = null,
            networkMode: NetworkMode = NetworkMode.UNKNOWN,
            wifiOnlyForLan: Boolean = true,
        ): PlaybackRoutePreference {
            val effectiveLan = if (wifiOnlyForLan && networkMode == NetworkMode.CELLULAR) null else lanStream
            val items = listOfNotNull(localFile, effectiveLan, providerStream)
            return if (items.isEmpty()) {
                PlaybackRoutePreference(listOf(PlaybackRoute.ReDownload))
            } else {
                PlaybackRoutePreference(items)
            }
        }

        /**
         * Locked priority: LocalFile > LanDesktopStream > ProviderStream
         *  > ReDownload. Ties within a type fall back to insertion order.
         */
        internal fun pickInOrder(candidates: List<PlaybackRoute>): PlaybackRoute {
            candidates.firstOrNull { it is PlaybackRoute.LocalFile }?.let { return it }
            candidates.firstOrNull { it is PlaybackRoute.LanDesktopStream }?.let { return it }
            candidates.firstOrNull { it is PlaybackRoute.ProviderStream }?.let { return it }
            return PlaybackRoute.ReDownload
        }
    }
}
