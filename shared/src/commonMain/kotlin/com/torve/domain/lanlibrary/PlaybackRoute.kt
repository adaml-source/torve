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
     * peer is serving). Token is opaque; the URL embeds whatever the
     * publisher chooses for auth.
     */
    data class LanDesktopStream(val url: String) : PlaybackRoute

    /** Original provider URL — debrid, addon, IPTV, anything else. */
    data class ProviderStream(val url: String) : PlaybackRoute

    /** Nothing playable right now — UI should offer a download CTA. */
    data object ReDownload : PlaybackRoute
}

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
         */
        fun of(
            localFile: PlaybackRoute.LocalFile? = null,
            lanStream: PlaybackRoute.LanDesktopStream? = null,
            providerStream: PlaybackRoute.ProviderStream? = null,
        ): PlaybackRoutePreference {
            val items = listOfNotNull(localFile, lanStream, providerStream)
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
