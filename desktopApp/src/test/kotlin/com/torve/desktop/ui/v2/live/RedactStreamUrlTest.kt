package com.torve.desktop.ui.v2.live

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the credential-stripping rules for [redactStreamUrlForDisplay]:
 *  - Xtream path-style creds (`/live/<u>/<p>/<id>.ts`) are masked.
 *  - movie / series path variants are masked the same way.
 *  - Query-string creds (`?username=…&password=…`) are masked.
 *  - URLs with no creds pass through untouched.
 *  - Empty / blank input is a no-op.
 *
 * Regression catch: bug found in user's screen recording (May 2026)
 * where the channel-detail pane printed the raw URL exposing username
 * + password in plain text on the live channel detail.
 */
class RedactStreamUrlTest {

    @Test
    fun xtreamLivePathRedactsUsernameAndPassword() {
        assertEquals(
            "http://smatv.pro/live/***/***/1834131.ts",
            redactStreamUrlForDisplay("http://smatv.pro/live/c55e6450464d/53ec13d581/1834131.ts"),
        )
    }

    @Test
    fun xtreamMoviePathIsRedacted() {
        assertEquals(
            "http://x.example/movie/***/***/abc.mkv",
            redactStreamUrlForDisplay("http://x.example/movie/myuser/mypass/abc.mkv"),
        )
    }

    @Test
    fun xtreamSeriesPathIsRedacted() {
        assertEquals(
            "https://x.example/series/***/***/ep.ts",
            redactStreamUrlForDisplay("https://x.example/series/u1/p1/ep.ts"),
        )
    }

    @Test
    fun queryStringUsernameAndPasswordAreRedacted() {
        assertEquals(
            "https://provider/api?username=***&password=***&action=list",
            redactStreamUrlForDisplay("https://provider/api?username=alice&password=secret123&action=list"),
        )
    }

    @Test
    fun queryStringTokenAndApiKeyAreRedacted() {
        assertEquals(
            "https://api.example/get?token=***&apikey=***",
            redactStreamUrlForDisplay("https://api.example/get?token=ey9.abc&apikey=12345"),
        )
    }

    @Test
    fun urlWithNoCredentialsPassesThrough() {
        val clean = "https://cdn.example.com/live/master.m3u8"
        assertEquals(clean, redactStreamUrlForDisplay(clean))
    }

    @Test
    fun blankInputIsNoOp() {
        assertEquals("", redactStreamUrlForDisplay(""))
    }
}
