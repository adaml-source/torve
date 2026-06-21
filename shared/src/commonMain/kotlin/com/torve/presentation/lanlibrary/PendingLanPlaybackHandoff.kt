package com.torve.presentation.lanlibrary

import com.torve.domain.lanlibrary.PlaybackRoute

/**
 * Process-level holder for a one-shot LAN-route handoff (Prompt 11C).
 *
 * The Compose nav graph routes the player by URL only — it has no slot
 * for HTTP headers. LAN streams need `X-Torve-Lan-Auth` attached or the
 * publisher 401s. To bridge the gap without changing the route schema:
 *
 *   1. The caller (TV-Home tile, TV-Details source picker, …) calls
 *      [stage] BEFORE navigating to the player route.
 *   2. `PlayerScreen` calls [consumeFor] right before `engine.play(url)`.
 *      If the staged headers match the URL it's about to play, they get
 *      attached via [com.torve.domain.player.PlayerEngine.setNextRequestHeaders].
 *   3. The holder clears itself on consume so headers can never leak to
 *      a later, unrelated stream.
 *
 * The URL match is on prefix (the stage URL is the LAN base URL; the
 * actual URL the player launches with may include query strings the
 * router adds). We compare the path-and-host portion to be safe.
 *
 * Concurrency: the holder is intentionally a simple var because there
 * is exactly one player launch in flight at a time. If a second
 * stage() arrives while one is pending, last-writer wins — matches the
 * "next stream" semantics ExoPlayerEngine itself uses.
 */
object PendingLanPlaybackHandoff {

    private var staged: PlaybackRoute.LanDesktopStream? = null

    /** Stage [route]'s headers for the next [consumeFor] whose URL matches. */
    fun stage(route: PlaybackRoute.LanDesktopStream) {
        staged = route
    }

    /**
     * If the staged route's URL prefix-matches [playbackUrl], return its
     * headers and clear the holder. Returns null otherwise — the player
     * proceeds with no LAN headers, which is correct for non-LAN routes.
     */
    fun consumeFor(playbackUrl: String): Map<String, String>? {
        val pending = staged ?: return null
        if (!playbackUrl.startsWith(pending.url) && pending.url != playbackUrl) return null
        staged = null
        return pending.headers
    }

    /** Test-only: clear any staged route. */
    internal fun clearForTest() {
        staged = null
    }

    /** Test-only: peek without consuming. */
    internal fun peekForTest(): PlaybackRoute.LanDesktopStream? = staged
}
