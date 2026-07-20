package com.torve.data.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the redaction contract for any string the app forwards to an AI
 * provider. Acceptance criterion (Prompt 8): AI payloads exclude
 * secrets and source URLs with credentials.
 *
 * The sanitizer is a privacy backstop, not the only line of defense —
 * but it is the only line that future-proofs the prompt against
 * accidental leakage when callers start enriching the prompt with
 * availability hints.
 */
class AiPayloadSanitizerTest {

    @Test
    fun `bearer tokens are redacted`() {
        val out = AiPayloadSanitizer.sanitize(
            "play me with Authorization: Bearer abc.def-ghi_123",
        )
        assertFalse("abc.def-ghi_123" in out, "bearer payload leaked: $out")
        assertTrue("[redacted]" in out)
    }

    @Test
    fun `api_key query params are redacted in any URL`() {
        val out = AiPayloadSanitizer.sanitize(
            "open https://example.com/feed.xml?api_key=ABCD1234EFGH and play",
        )
        assertFalse("ABCD1234EFGH" in out, "api_key leaked: $out")
        assertTrue("[redacted]" in out)
    }

    @Test
    fun `tokenized panda URLs are wholly redacted`() {
        val malicious = "stream from https://torve.app/u/abc-123-def/movies/xyz.mkv now"
        val out = AiPayloadSanitizer.sanitize(malicious)
        assertFalse("abc-123-def" in out, "panda token leaked: $out")
        assertFalse("/u/" in out, "panda URL prefix leaked: $out")
    }

    @Test
    fun `provider key prefixes are redacted`() {
        val out = AiPayloadSanitizer.sanitize(
            "use my key sk-ant-api03-AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH",
        )
        assertFalse("AAAA" in out, "claude key leaked: $out")
        assertFalse("sk-ant" in out, "key prefix leaked: $out")
    }

    @Test
    fun `OpenAI-style sk- keys are redacted`() {
        val out = AiPayloadSanitizer.sanitize(
            "key sk-Z9abcdefghijklmnopqrstuvwxyz1234",
        )
        assertFalse("Z9abcdefghijklmnopqrstuvwxyz1234" in out, "openai key leaked: $out")
    }

    @Test
    fun `local filesystem paths are redacted`() {
        val mac = AiPayloadSanitizer.sanitize("file at /Users/me/Movies/secret.mkv")
        assertFalse("/Users/me/Movies/secret.mkv" in mac)

        val linux = AiPayloadSanitizer.sanitize("see /home/me/movies/x.mkv")
        assertFalse("/home/me/movies" in linux)

        val win = AiPayloadSanitizer.sanitize("read C:\\Users\\me\\Downloads\\film.mkv")
        assertFalse("C:\\Users\\me" in win, "windows path leaked: $win")
    }

    @Test
    fun `file URLs are redacted`() {
        val out = AiPayloadSanitizer.sanitize("local file://wsl/home/me/movie.mkv now")
        assertFalse("file://wsl" in out, "file url leaked: $out")
    }

    @Test
    fun `clean phrases are passed through unchanged`() {
        val phrase = "Adam Sandler movies on the beach"
        assertEquals(phrase, AiPayloadSanitizer.sanitize(phrase))
    }

    @Test
    fun `sanitizer is idempotent`() {
        val once = AiPayloadSanitizer.sanitize(
            "Bearer abc.def-ghi/play?api_key=DEADBEEF on /home/me/x.mkv",
        )
        val twice = AiPayloadSanitizer.sanitize(once)
        assertEquals(once, twice)
    }
}
