package com.torve.presentation.lanlibrary

import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.player.PlayerEngine

/**
 * Hand a LAN-library route to a [PlayerEngine] with the required
 * `X-Torve-Lan-Auth` header attached. Always set the headers BEFORE
 * calling `play()` so the engine consumes them on this stream and not
 * the next one.
 *
 * No-op for engines that don't honor [PlayerEngine.setNextRequestHeaders];
 * those will hit the LAN URL unauthenticated and the publisher will
 * 401 — the route chooser then falls back to the provider stream on
 * the next attempt.
 */
fun PlayerEngine.playLanRoute(route: PlaybackRoute.LanDesktopStream) {
    setNextRequestHeaders(route.headers)
    play(route.url)
}
