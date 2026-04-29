package com.torve.desktop.diagnostics

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each test asserts both that the secret is gone AND that the surrounding
 * shape (key name, scheme, host) is preserved. Over-redacting is fine; the
 * only failure is leaking a real value.
 */
class DiagnosticsRedactorTest {

    @Test
    fun `redacts windows user paths`() {
        val out = DiagnosticsRedactor.redact("Path: C:\\Users\\Alice\\Documents\\file.txt")
        assertFalse(out.contains("Alice"))
        assertTrue(out.contains("<redacted>"))
        assertTrue(out.contains("C:\\Users\\"))
    }

    @Test
    fun `redacts macOS user paths`() {
        val out = DiagnosticsRedactor.redact("Path: /Users/alice/Library/Caches/torve")
        assertFalse(out.contains("alice"))
        assertTrue(out.contains("/Users/<redacted>/"))
    }

    @Test
    fun `redacts linux user paths`() {
        val out = DiagnosticsRedactor.redact("Path: /home/bob/.config/torve")
        assertFalse(out.contains("bob"))
        assertTrue(out.contains("/home/<redacted>/"))
    }

    @Test
    fun `redacts URL basic auth`() {
        val out = DiagnosticsRedactor.redact("https://alice:hunter2@iptv.example.com/playlist.m3u")
        assertFalse(out.contains("alice"))
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("iptv.example.com"))
    }

    @Test
    fun `redacts addon manifest token URL`() {
        val out = DiagnosticsRedactor.redact("https://addons.example.com/u/abcDEF123/manifest.json")
        assertFalse(out.contains("abcDEF123"))
        assertTrue(out.contains("/u/<redacted>/manifest.json"))
    }

    @Test
    fun `redacts panda manifest token URL`() {
        val out = DiagnosticsRedactor.redact("https://panda.torve.app/p/sometoken_42-A/manifest.json")
        assertFalse(out.contains("sometoken_42-A"))
        assertTrue(out.contains("/p/<redacted>/manifest.json"))
    }

    @Test
    fun `redacts api_key query param`() {
        val out = DiagnosticsRedactor.redact("GET /api/v1/movie?api_key=ABCDEF12345&page=1")
        assertFalse(out.contains("ABCDEF12345"))
        assertTrue(out.contains("api_key=<redacted>"))
        assertTrue(out.contains("page=1"))
    }

    @Test
    fun `redacts iptv username and password query`() {
        val out = DiagnosticsRedactor.redact(
            "https://iptv.example.com/get.php?username=foo&password=bar&type=m3u"
        )
        assertFalse(out.contains("foo"))
        assertFalse(out.contains("bar"))
        assertTrue(out.contains("username=<redacted>"))
        assertTrue(out.contains("password=<redacted>"))
        assertTrue(out.contains("type=m3u"))
    }

    @Test
    fun `redacts bearer token in authorization header`() {
        val out = DiagnosticsRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(out.contains("Authorization: Bearer <redacted>"))
    }

    @Test
    fun `redacts basic authorization header`() {
        val out = DiagnosticsRedactor.redact("Authorization: Basic dXNlcjpwYXNz")
        assertFalse(out.contains("dXNlcjpwYXNz"))
        assertTrue(out.contains("Authorization: Basic <redacted>"))
    }

    @Test
    fun `redacts json api key field`() {
        val out = DiagnosticsRedactor.redact("""{"api_key": "sk-abcdef123", "page": 2}""")
        assertFalse(out.contains("sk-abcdef123"))
        assertTrue(out.contains(""""api_key": "<redacted>""""))
        assertTrue(out.contains(""""page": 2"""))
    }

    @Test
    fun `redacts json camelCase debrid key`() {
        val out = DiagnosticsRedactor.redact("""{"debridApiKey": "rd_xxxxxxxxxxxxxxx"}""")
        assertFalse(out.contains("rd_xxxxxxxxxxxxxxx"))
        assertTrue(out.contains(""""debridApiKey": "<redacted>""""))
    }

    @Test
    fun `redacts panda token fields`() {
        val out = DiagnosticsRedactor.redact(
            """{"panda_token": "pt-abc", "management_token": "mt-xyz", "manifest_token": "ft-q"}"""
        )
        assertFalse(out.contains("pt-abc"))
        assertFalse(out.contains("mt-xyz"))
        assertFalse(out.contains("ft-q"))
        assertTrue(out.contains(""""panda_token": "<redacted>""""))
        assertTrue(out.contains(""""management_token": "<redacted>""""))
        assertTrue(out.contains(""""manifest_token": "<redacted>""""))
    }

    @Test
    fun `redacts ai provider keys`() {
        val text = """
            {
              "openai_api_key": "sk-openai-secret",
              "anthropic_api_key": "sk-ant-secret",
              "gemini_api_key": "AIzaGemini",
              "perplexity_api_key": "pplx-secret",
              "deepseek_api_key": "ds-secret",
              "mdblist_api_key": "mdb-secret",
              "omdb_api_key": "omdb-secret"
            }
        """.trimIndent()
        val out = DiagnosticsRedactor.redact(text)
        listOf(
            "sk-openai-secret", "sk-ant-secret", "AIzaGemini",
            "pplx-secret", "ds-secret", "mdb-secret", "omdb-secret",
        ).forEach { secret ->
            assertFalse(out.contains(secret), "leaked: $secret")
        }
    }

    @Test
    fun `redacts properties-style key equals value`() {
        val out = DiagnosticsRedactor.redact("debrid_api_key=rd_realsecret\nuser=alice")
        assertFalse(out.contains("rd_realsecret"))
        assertTrue(out.contains("debrid_api_key=<redacted>"))
    }

    @Test
    fun `is idempotent`() {
        val once = DiagnosticsRedactor.redact("api_key=ABC&token=DEF")
        val twice = DiagnosticsRedactor.redact(once)
        assertEquals(once, twice)
    }

    @Test
    fun `null and empty pass through`() {
        assertEquals("", DiagnosticsRedactor.redact(null))
        assertEquals("", DiagnosticsRedactor.redact(""))
    }

    @Test
    fun `non-secret content is preserved`() {
        val original = "Build version 1.0.6, JVM 21, OS Windows 11."
        assertEquals(original, DiagnosticsRedactor.redact(original))
    }
}
