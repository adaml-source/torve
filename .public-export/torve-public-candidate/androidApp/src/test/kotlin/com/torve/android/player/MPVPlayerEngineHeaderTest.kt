package com.torve.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the [formatHttpHeaderFields] encoding mpv expects for the
 * `http-header-fields` property:
 *  - one header per `Name: value` chunk,
 *  - chunks joined by commas,
 *  - CR and LF stripped so a malformed header value cannot inject
 *    extra fields.
 *
 * Drives Prompt 19's "LAN headers through every engine" requirement -
 * the same `X-Torve-Lan-Auth` header that ExoPlayer attaches has to
 * survive the round-trip into mpv unchanged, and a header carrying a
 * stray newline must not split into two fields silently.
 */
class MPVPlayerEngineHeaderTest {

    @Test
    fun emptyMapEncodesToEmptyString() {
        assertEquals("", formatHttpHeaderFields(emptyMap()))
    }

    @Test
    fun singleHeaderEncodesAsNameColonSpaceValue() {
        assertEquals(
            "X-Torve-Lan-Auth: token-abc",
            formatHttpHeaderFields(mapOf("X-Torve-Lan-Auth" to "token-abc")),
        )
    }

    @Test
    fun multipleHeadersJoinWithComma() {
        // LinkedHashMap preserves insertion order so the assertion is
        // stable; mpv accepts headers in any order.
        val headers = linkedMapOf(
            "X-Torve-Lan-Auth" to "token-abc",
            "User-Agent" to "Torve/1.0",
        )
        assertEquals(
            "X-Torve-Lan-Auth: token-abc,User-Agent: Torve/1.0",
            formatHttpHeaderFields(headers),
        )
    }

    @Test
    fun crAndLfAreStrippedFromBothNameAndValue() {
        val headers = mapOf(
            "X-Bad-Name\r\n" to "value-with\r\nnewline",
        )
        // The injected newline would otherwise let a malicious value
        // open a second header field on mpv's parser side.
        assertEquals(
            "X-Bad-Name: value-withnewline",
            formatHttpHeaderFields(headers),
        )
    }

    @Test
    fun headerNameIsTrimmedButValueWhitespaceIsPreserved() {
        // Trim the name so a stray leading space (sometimes copied with
        // a header) doesn't produce an invalid `  Name: value` line.
        // Preserve the value as-is - internal spaces matter for tokens
        // like `Bearer abc def`.
        val headers = mapOf("  Authorization  " to "Bearer abc def")
        assertEquals(
            "Authorization: Bearer abc def",
            formatHttpHeaderFields(headers),
        )
    }
}
