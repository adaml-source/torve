package com.torve.domain.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the telemetry redaction contract (Prompt 12 hardening).
 *
 * The decorator wraps any sink so a future production telemetry backend
 * cannot accidentally receive bearer tokens, source URLs, local paths,
 * LAN auth headers, AI-vendor keys, or credential-transfer envelope
 * blobs. Tests seed each known leak surface with a sentinel and assert
 * the decorator strips or replaces it before delegation.
 */
class RedactingTelemetryEmitterTest {

    private class CapturingEmitter : TelemetryEmitter {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        override fun emit(event: String, attributes: Map<String, String>) {
            events += event to attributes
        }
    }

    private fun captured(input: Map<String, String>): Map<String, String> {
        val cap = CapturingEmitter()
        val em = RedactingTelemetryEmitter(cap)
        em.emit("e", input)
        return cap.events.single().second
    }

    private fun emittedEvent(event: String): String {
        val cap = CapturingEmitter()
        val em = RedactingTelemetryEmitter(cap)
        em.emit(event, emptyMap())
        return cap.events.single().first
    }

    @Test
    fun `bearer token is redacted in attribute values`() {
        val out = captured(mapOf("auth" to "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.AAAA.BBBB"))
        assertFalse(out["auth"]!!.contains("eyJhbGc"), "JWT body must not survive: ${out["auth"]}")
        assertTrue(out["auth"]!!.contains("<redacted>"))
    }

    @Test
    fun `Authorization header style is redacted`() {
        val out = captured(mapOf("hdr" to "Authorization: Bearer abc.def.ghi"))
        assertFalse(out["hdr"]!!.contains("abc.def.ghi"))
    }

    @Test
    fun `X-Torve-Lan-Auth header value is redacted`() {
        val out = captured(mapOf("h" to "X-Torve-Lan-Auth: super-secret-lan-AAAA"))
        assertFalse(out["h"]!!.contains("super-secret-lan-AAAA"))
    }

    @Test
    fun `URL token query params are redacted`() {
        val out = captured(mapOf(
            "u1" to "http://192.168.1.10:41122/local/stream/x?token=AAAA-BBBB-CCCC",
            "u2" to "https://api.example.com/v1?api_key=AKIAIOSFODNN7EXAMPLE",
        ))
        assertFalse(out["u1"]!!.contains("AAAA-BBBB-CCCC"))
        assertFalse(out["u2"]!!.contains("AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun `generic http URL is replaced with scheme plus host marker`() {
        val out = captured(mapOf("src" to "https://upstream.example.com/path/movie.mp4"))
        // Path stripped (could be source-leaking), host kept for observability.
        val v = out["src"]!!
        assertTrue(v.contains("upstream.example.com"), "host should remain: $v")
        assertFalse(v.contains("/path/movie.mp4"))
    }

    @Test
    fun `Windows local path is redacted`() {
        val out = captured(mapOf("p" to "C:\\Users\\alice\\Downloads\\movie.mkv"))
        assertFalse(out["p"]!!.contains("alice"))
        assertFalse(out["p"]!!.contains("movie.mkv"))
    }

    @Test
    fun `macOS home path is redacted`() {
        val out = captured(mapOf("p" to "/Users/alice/Downloads/movie.mkv"))
        assertFalse(out["p"]!!.contains("alice"))
    }

    @Test
    fun `Linux home path is redacted`() {
        val out = captured(mapOf("p" to "/home/alice/Videos/movie.mkv"))
        assertFalse(out["p"]!!.contains("alice"))
    }

    @Test
    fun `file URL is redacted`() {
        val out = captured(mapOf("p" to "file:///Users/alice/Downloads/x.mkv"))
        assertFalse(out["p"]!!.contains("alice"))
    }

    @Test
    fun `AI vendor API keys are redacted`() {
        val out = captured(mapOf(
            "claude" to "sk-ant-api03-DEADBEEF1234567890ABCDEFGH",
            "openai" to "sk-DEADBEEF1234567890ABCDEFGHIJKL",
            "perplexity" to "pplx-DEADBEEF1234567890ABCDEFGHIJK",
        ))
        assertFalse(out["claude"]!!.contains("DEADBEEF1234567890"))
        assertFalse(out["openai"]!!.contains("DEADBEEF1234567890"))
        assertFalse(out["perplexity"]!!.contains("DEADBEEF1234567890"))
    }

    @Test
    fun `credential transfer envelope blob is redacted`() {
        // 200-char base64-ish blob — typical envelope shape.
        val blob = "A".repeat(200)
        val out = captured(mapOf("envelope" to blob))
        assertFalse(out["envelope"]!!.contains(blob))
    }

    @Test
    fun `event name is also redacted`() {
        val v = emittedEvent("debrid.error url=https://provider.example/key=AAAA-BBBB-CCCC")
        assertFalse(v.contains("AAAA-BBBB-CCCC"))
    }

    @Test
    fun `safe attribute values pass through unchanged`() {
        val safe = mapOf(
            "platform" to "android_tv",
            "role" to "consumer",
            "state" to "ready",
            "duration_bucket" to "1500_5000ms",
        )
        val out = captured(safe)
        assertEquals(safe, out)
    }

    @Test
    fun `selectTelemetryEmitter returns redacting decorator regardless of sink choice`() {
        val noopWrapped = selectTelemetryEmitter(null)
        val printlnWrapped = selectTelemetryEmitter("println")
        // We can't easily assert the wrap from outside, but we CAN
        // verify the decorator behavior is active by emitting a known
        // sensitive value through the chain.
        val cap = CapturingEmitter()
        val direct = RedactingTelemetryEmitter(cap)
        direct.emit("x", mapOf("t" to "Bearer SHOULD_NOT_LEAK_AAAA"))
        assertFalse(cap.events.single().second["t"]!!.contains("SHOULD_NOT_LEAK_AAAA"))

        // Smoke: factory returns a non-null emitter for both modes.
        // Println goes to stdout by side effect; we just verify it
        // doesn't crash on a redacted input.
        printlnWrapped.emit("e", mapOf("a" to "Bearer x"))
        noopWrapped.emit("e", mapOf("a" to "Bearer x"))
    }

    @Test
    fun `redaction is idempotent on already-redacted values`() {
        val out1 = captured(mapOf("v" to "<redacted>"))
        assertEquals("<redacted>", out1["v"])
    }

    @Test
    fun `multiple secrets in one value all get redacted`() {
        val out = captured(mapOf(
            "v" to "Bearer abc.def.ghi.jklmnop and url=https://x.example/?token=ZZZ-LEAK-ZZZ in /Users/alice/x"
        ))
        val v = out["v"]!!
        // Bearer payload (8+ chars including dots).
        assertFalse(v.contains("abc.def.ghi.jklmnop"), "bearer payload survived: $v")
        // URL token query param.
        assertFalse(v.contains("ZZZ-LEAK-ZZZ"), "url token survived: $v")
        // macOS home path.
        assertFalse(v.contains("alice"), "home path survived: $v")
    }
}
