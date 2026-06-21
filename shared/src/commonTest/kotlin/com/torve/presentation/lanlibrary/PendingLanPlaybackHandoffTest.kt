package com.torve.presentation.lanlibrary

import com.torve.domain.lanlibrary.PlaybackRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Pins the LAN-handoff bridge between the source picker / TV-Home
 * launcher and the player (Prompt 11C). The route schema can't carry
 * HTTP headers, so the handoff stores them in a process-level holder
 * the player consumes before play().
 */
class PendingLanPlaybackHandoffTest {

    private val lan = PlaybackRoute.LanDesktopStream(
        url = "http://192.168.1.10:41122/local/stream/abc?token=t",
        headers = mapOf("X-Torve-Lan-Auth" to "shh"),
    )

    @BeforeTest
    fun reset() {
        PendingLanPlaybackHandoff.clearForTest()
    }

    @AfterTest
    fun cleanup() {
        PendingLanPlaybackHandoff.clearForTest()
    }

    @Test
    fun `headers travel from stage to consume on matching URL`() {
        PendingLanPlaybackHandoff.stage(lan)
        val out = PendingLanPlaybackHandoff.consumeFor(lan.url)
        assertNotNull(out)
        assertEquals("shh", out["X-Torve-Lan-Auth"])
    }

    @Test
    fun `consume clears the holder so the next stream gets nothing`() {
        PendingLanPlaybackHandoff.stage(lan)
        val first = PendingLanPlaybackHandoff.consumeFor(lan.url)
        assertNotNull(first)
        // Second consume on the same URL must return null — a stale
        // header would leak into an unrelated LAN session.
        val second = PendingLanPlaybackHandoff.consumeFor(lan.url)
        assertNull(second)
    }

    @Test
    fun `non-matching URL returns null and leaves the holder in place`() {
        PendingLanPlaybackHandoff.stage(lan)
        val out = PendingLanPlaybackHandoff.consumeFor("https://upstream/movie.mp4")
        assertNull(out)
        // Holder is still primed for the right URL.
        val rightOne = PendingLanPlaybackHandoff.consumeFor(lan.url)
        assertNotNull(rightOne)
    }

    @Test
    fun `prefix match accepts URLs the router widened with extra query`() {
        PendingLanPlaybackHandoff.stage(lan)
        // The router may append a per-call ?profile=... etc.
        val widened = "${lan.url}&profile=tv"
        val out = PendingLanPlaybackHandoff.consumeFor(widened)
        assertNotNull(out)
        assertEquals("shh", out["X-Torve-Lan-Auth"])
    }

    @Test
    fun `last writer wins when stage is called twice`() {
        val firstRoute = PlaybackRoute.LanDesktopStream(
            url = "http://10.0.0.1:1111/stream/old",
            headers = mapOf("X-Torve-Lan-Auth" to "old-secret"),
        )
        PendingLanPlaybackHandoff.stage(firstRoute)
        // A second stage replaces — matches "next stream" semantics
        // ExoPlayerEngine itself uses for headers.
        PendingLanPlaybackHandoff.stage(lan)
        val outOld = PendingLanPlaybackHandoff.consumeFor(firstRoute.url)
        assertNull(outOld, "old route was overwritten — its URL no longer matches")
        val outNew = PendingLanPlaybackHandoff.consumeFor(lan.url)
        assertNotNull(outNew)
        assertEquals("shh", outNew["X-Torve-Lan-Auth"])
    }

    @Test
    fun `consume without stage returns null without crashing`() {
        val out = PendingLanPlaybackHandoff.consumeFor(lan.url)
        assertNull(out)
    }
}
