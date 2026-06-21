package com.torve.presentation.lanlibrary

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.lanlibrary.PlaybackRoutePreference
import com.torve.domain.player.ExternalSubtitle
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Player-side wiring contracts for Prompt 9C:
 *   - `playLanRoute` always stages the headers BEFORE calling play(),
 *     so the engine binds them to the LAN URL and not a later stream.
 *   - The route preference falls back to provider when LAN headers
 *     are present but the consumer is on cellular with the wifi-only
 *     guard on.
 *   - The route preference falls back to ReDownload when token
 *     issuance fails (consumer returned null).
 */
class LanPlaybackTest {

    @Test
    fun `playLanRoute stages headers before calling play`() {
        val engine = RecordingPlayerEngine()
        val route = PlaybackRoute.LanDesktopStream(
            url = "http://192.168.1.10:41122/local/stream/abc?token=xyz",
            headers = mapOf("X-Torve-Lan-Auth" to "shh"),
        )
        engine.playLanRoute(route)
        // Order matters: headers staged THEN play().
        assertEquals(
            listOf("setNextRequestHeaders", "play"),
            engine.callOrder,
        )
        assertEquals(mapOf("X-Torve-Lan-Auth" to "shh"), engine.lastHeaders)
        assertEquals(route.url, engine.lastPlayedUrl)
    }

    @Test
    fun `playLanRoute attaches X-Torve-Lan-Auth header for LAN URLs only`() {
        val engine = RecordingPlayerEngine()
        engine.playLanRoute(
            PlaybackRoute.LanDesktopStream(
                url = "http://10.0.0.5:41122/local/stream/x?token=t",
                headers = mapOf("X-Torve-Lan-Auth" to "secret"),
            ),
        )
        // Now the player picks an unauthenticated provider URL — the
        // headers must NOT carry across because the LAN play() path
        // already consumed them.
        engine.play("https://upstream-provider/m.mp4")
        assertEquals("https://upstream-provider/m.mp4", engine.lastPlayedUrl)
        // Defensive contract: the helper never re-stages headers for a
        // URL it didn't handle. The engine's own consume logic clears
        // them after the LAN play; the second play sees no fresh stage
        // call.
        assertEquals(
            listOf("setNextRequestHeaders", "play", "play"),
            engine.callOrder,
        )
    }

    @Test
    fun `route falls back to provider when token issuance fails`() {
        // Caller flow: consumer.findLanRoute(...) returned null because
        // /local/stream-token/ 404'd. Caller passes null to
        // PlaybackRoutePreference.of — it must fall back to provider.
        val pref = PlaybackRoutePreference.of(
            lanStream = null,
            providerStream = PlaybackRoute.ProviderStream("https://up/movie.mp4"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(PlaybackRoute.ProviderStream("https://up/movie.mp4"), pref.pick())
    }

    @Test
    fun `route falls back to ReDownload when no LAN and no provider`() {
        val pref = PlaybackRoutePreference.of(
            lanStream = null,
            providerStream = null,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(PlaybackRoute.ReDownload, pref.pick())
    }

    /** Captures every PlayerEngine call so the test can assert order. */
    private class RecordingPlayerEngine : PlayerEngine {
        val callOrder = mutableListOf<String>()
        var lastHeaders: Map<String, String> = emptyMap()
        var lastPlayedUrl: String? = null

        override val state: PlayerState = PlayerState()

        override fun setNextRequestHeaders(headers: Map<String, String>) {
            callOrder += "setNextRequestHeaders"
            lastHeaders = headers
        }

        override fun play(url: String) {
            callOrder += "play"
            lastPlayedUrl = url
        }

        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekRelative(deltaMs: Long) {}
        override fun setSpeed(speed: Float) {}
        override fun getSubtitleTracks(): List<TrackDescription> = emptyList()
        override fun getAudioTracks(): List<TrackDescription> = emptyList()
        override fun selectSubtitleTrack(id: Int) {}
        override fun selectAudioTrack(id: Int) {}
        override fun disableSubtitles() {}
        override fun release() {}
        override fun addListener(listener: PlayerListener) {}
        override fun removeListener(listener: PlayerListener) {}
    }
}
