package com.torve.domain.lanlibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRouteAndRangeTest {

    // ── PlaybackRoutePreference ────────────────────────────────────

    @Test
    fun `pick prefers local over LAN over provider`() {
        val pref = PlaybackRoutePreference(
            candidates = listOf(
                PlaybackRoute.ProviderStream("https://upstream/a"),
                PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
                PlaybackRoute.LocalFile("/disk/movie.mkv"),
            ),
        )
        assertEquals(PlaybackRoute.LocalFile("/disk/movie.mkv"), pref.pick())
    }

    @Test
    fun `pick falls through when local missing`() {
        val pref = PlaybackRoutePreference(
            candidates = listOf(
                PlaybackRoute.ProviderStream("https://upstream/a"),
                PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
            ),
        )
        assertEquals(PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"), pref.pick())
    }

    @Test
    fun `pick returns ReDownload when nothing playable`() {
        val pref = PlaybackRoutePreference(candidates = emptyList())
        assertEquals(PlaybackRoute.ReDownload, pref.pick())
    }

    @Test
    fun ofDropsNullsAndOrdersByPriority() {
        val pref = PlaybackRoutePreference.of(
            providerStream = PlaybackRoute.ProviderStream("p"),
            localFile = PlaybackRoute.LocalFile("/x"),
        )
        assertEquals(PlaybackRoute.LocalFile("/x"), pref.pick())
    }

    @Test
    fun ofEmptyInputsReturnsReDownloadPreference() {
        val pref = PlaybackRoutePreference.of()
        assertEquals(listOf(PlaybackRoute.ReDownload), pref.candidates)
        assertEquals(PlaybackRoute.ReDownload, pref.pick())
    }

    // ── Mobile-data guard (Prompt 9) ──────────────────────────────

    @Test
    fun `mobile-data guard hides LAN stream on cellular when wifiOnly is true`() {
        val pref = PlaybackRoutePreference.of(
            lanStream = PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
            providerStream = PlaybackRoute.ProviderStream("https://up/a"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        assertEquals(PlaybackRoute.ProviderStream("https://up/a"), pref.pick())
    }

    @Test
    fun `mobile-data guard preserves LAN stream on Wi-Fi`() {
        val pref = PlaybackRoutePreference.of(
            lanStream = PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
            providerStream = PlaybackRoute.ProviderStream("https://up/a"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"), pref.pick())
    }

    @Test
    fun `mobile-data guard preserves LAN stream on cellular when wifiOnly is false`() {
        val pref = PlaybackRoutePreference.of(
            lanStream = PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = false,
        )
        assertEquals(PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"), pref.pick())
    }

    @Test
    fun `mobile-data guard falls through to ReDownload when only LAN was offered`() {
        val pref = PlaybackRoutePreference.of(
            lanStream = PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        assertEquals(PlaybackRoute.ReDownload, pref.pick())
    }

    @Test
    fun `mobile-data guard does not affect local file priority`() {
        val pref = PlaybackRoutePreference.of(
            localFile = PlaybackRoute.LocalFile("/disk/movie.mkv"),
            lanStream = PlaybackRoute.LanDesktopStream("http://lan/local/stream/x"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        // Local file always wins regardless of network mode.
        assertEquals(PlaybackRoute.LocalFile("/disk/movie.mkv"), pref.pick())
    }

    // ── RangeRequest.parse ─────────────────────────────────────────

    @Test
    fun `null header is NoRange`() {
        assertEquals(RangeParseResult.NoRange, RangeRequest.parse(null, 100))
        assertEquals(RangeParseResult.NoRange, RangeRequest.parse("", 100))
        assertEquals(RangeParseResult.NoRange, RangeRequest.parse("   ", 100))
    }

    @Test
    fun `simple bytes range parses inclusive`() {
        val r = assertIs<RangeParseResult.Satisfiable>(RangeRequest.parse("bytes=0-99", 1000))
        assertEquals(0L, r.start)
        assertEquals(99L, r.endInclusive)
    }

    @Test
    fun `open-ended range fills last byte`() {
        val r = assertIs<RangeParseResult.Satisfiable>(RangeRequest.parse("bytes=500-", 1000))
        assertEquals(500L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `suffix range returns last N bytes`() {
        val r = assertIs<RangeParseResult.Satisfiable>(RangeRequest.parse("bytes=-200", 1000))
        assertEquals(800L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `suffix larger than total clamps to start of file`() {
        val r = assertIs<RangeParseResult.Satisfiable>(RangeRequest.parse("bytes=-5000", 1000))
        assertEquals(0L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `range past end clamps end to last byte`() {
        val r = assertIs<RangeParseResult.Satisfiable>(RangeRequest.parse("bytes=100-9999", 1000))
        assertEquals(100L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `start past end is NotSatisfiable`() {
        assertEquals(RangeParseResult.NotSatisfiable, RangeRequest.parse("bytes=2000-", 1000))
    }

    @Test
    fun `range against zero-size resource is NotSatisfiable`() {
        assertEquals(RangeParseResult.NotSatisfiable, RangeRequest.parse("bytes=0-0", 0))
    }

    @Test
    fun `multi-range is Unsupported`() {
        assertIs<RangeParseResult.Unsupported>(RangeRequest.parse("bytes=0-99,200-299", 1000))
    }

    @Test
    fun `wrong unit is Unsupported`() {
        assertIs<RangeParseResult.Unsupported>(RangeRequest.parse("seconds=0-10", 1000))
    }

    @Test
    fun `malformed ranges are Malformed`() {
        assertIs<RangeParseResult.Malformed>(RangeRequest.parse("bytes=", 1000))
        assertIs<RangeParseResult.Malformed>(RangeRequest.parse("bytes=abc-def", 1000))
        assertIs<RangeParseResult.Malformed>(RangeRequest.parse("bytes=10", 1000))
        assertIs<RangeParseResult.Malformed>(RangeRequest.parse("bytes=50-10", 1000))
    }

    // ── RangeRequest.buildResponse ─────────────────────────────────

    @Test
    fun `noRange builds 200 with full body`() {
        val resp = RangeRequest.buildResponse(RangeParseResult.NoRange, 1000, "video/mp4")
        assertEquals(200, resp.status)
        assertEquals(1000L, resp.contentLength)
        assertNull(resp.contentRangeHeader)
        assertEquals("bytes", resp.acceptRangesHeader)
    }

    @Test
    fun `satisfiable builds 206 with content-range`() {
        val resp = RangeRequest.buildResponse(
            RangeParseResult.Satisfiable(0, 99),
            1000, "video/mp4",
        )
        assertEquals(206, resp.status)
        assertEquals(100L, resp.contentLength)
        assertEquals("bytes 0-99/1000", resp.contentRangeHeader)
    }

    @Test
    fun `notSatisfiable builds 416`() {
        val resp = RangeRequest.buildResponse(RangeParseResult.NotSatisfiable, 1000, "video/mp4")
        assertEquals(416, resp.status)
        assertEquals(0L, resp.contentLength)
        assertEquals("bytes */1000", resp.contentRangeHeader)
    }

    @Test
    fun `unsupported builds 416 too`() {
        val resp = RangeRequest.buildResponse(
            RangeParseResult.Unsupported("multi-range"),
            500, "video/mp4",
        )
        assertEquals(416, resp.status)
    }

    @Test
    fun `malformed builds 400`() {
        val resp = RangeRequest.buildResponse(
            RangeParseResult.Malformed("bad"),
            500, "video/mp4",
        )
        assertEquals(400, resp.status)
        assertNull(resp.contentRangeHeader)
    }

    // ── Manifest types are pure data ───────────────────────────────

    @Test
    fun `LanMediaEntry never has a path field`() {
        // Compile-time guard: instantiating LanMediaEntry has no path
        // field, just metadata. If a future change adds one, this test
        // file won't compile.
        val e = LanMediaEntry(
            id = "abc",
            title = "Movie",
            mediaType = LanMediaType.MOVIE,
            sizeBytes = 1024,
            containerExtension = "mkv",
            mimeType = "video/x-matroska",
        )
        assertTrue(e.title == "Movie")
        // No `e.path` reference — would fail to compile.
    }
}
