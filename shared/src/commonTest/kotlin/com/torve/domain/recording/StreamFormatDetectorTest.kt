package com.torve.domain.recording

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the IPTV recorder's "do I follow a manifest, or pull bytes?"
 * decision. Detection has to handle:
 *   - URLs with query strings (some providers append `?token=...`).
 *   - Mixed-case extensions.
 *   - Missing extension + explicit Content-Type from the server.
 *   - Extension and MIME disagreeing — MIME wins.
 */
class StreamFormatDetectorTest {

    @Test
    fun `m3u8 extension classifies as HLS`() {
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify("https://example.com/live/master.m3u8"),
        )
    }

    @Test
    fun `ts extension classifies as TS`() {
        assertEquals(
            StreamFormat.TS,
            StreamFormatDetector.classify("https://example.com/live/feed.ts"),
        )
    }

    @Test
    fun `query string is ignored when sniffing extension`() {
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify("https://example.com/live/master.m3u8?token=abc&exp=1700000000"),
        )
    }

    @Test
    fun `mixed case extension still classifies`() {
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify("https://example.com/live/MASTER.M3U8"),
        )
        assertEquals(
            StreamFormat.TS,
            StreamFormatDetector.classify("https://example.com/live/Feed.TS"),
        )
    }

    @Test
    fun `extensionless URL with HLS MIME is classified as HLS`() {
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify(
                url = "https://example.com/live/sparrow",
                contentType = "application/vnd.apple.mpegurl",
            ),
        )
    }

    @Test
    fun `extensionless URL with TS MIME is classified as TS`() {
        assertEquals(
            StreamFormat.TS,
            StreamFormatDetector.classify(
                url = "https://example.com/live/sparrow",
                contentType = "video/mp2t",
            ),
        )
    }

    @Test
    fun `MIME wins when extension and MIME disagree`() {
        // Provider serves HLS from a `.bin` filename; trust the MIME.
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify(
                url = "https://example.com/live/feed.bin",
                contentType = "application/x-mpegurl",
            ),
        )
    }

    @Test
    fun `MIME with charset suffix still parses cleanly`() {
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify(
                url = "https://example.com/live/x",
                contentType = "application/vnd.apple.mpegurl; charset=utf-8",
            ),
        )
    }

    @Test
    fun `unknown extension and no MIME falls back to UNKNOWN`() {
        assertEquals(
            StreamFormat.UNKNOWN,
            StreamFormatDetector.classify("https://example.com/live/sparrow"),
        )
    }

    @Test
    fun `fragment is stripped before extension check`() {
        assertEquals(
            StreamFormat.HLS,
            StreamFormatDetector.classify("https://example.com/live/master.m3u8#preview"),
        )
    }
}
