package com.torve.desktop.lanlibrary

import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaEntry
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Prompt 9C stream-token endpoint shape:
 *   - POST /local/stream-token/{id}, hub auth required.
 *   - On a registered id: returns 200 with {"path","token","expires_at_epoch_ms"}.
 *   - On an unknown id OR missing token: uniform 404.
 *   - Wrong auth header: 401, no body.
 *   - GET on the same path: 405.
 *   - The returned `path` must hit the streaming endpoint and 200 the
 *     real bytes when consumed (full round-trip).
 */
class Prompt9CStreamTokenTest {

    private var server: LocalLanHttpServer? = null

    @After
    fun stopServer() {
        server?.stop()
        server = null
    }

    private fun tmpDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    private fun writeFile(parent: File, name: String, size: Int): File {
        val f = File(parent, name)
        f.writeBytes(ByteArray(size) { (it and 0xFF).toByte() })
        f.deleteOnExit()
        return f
    }

    private fun fixedManifest(entries: List<LanMediaEntry>): () -> LanLibraryManifest = {
        LanLibraryManifest(
            version = MANIFEST_VERSION,
            publisherId = "pub",
            generatedAtEpochMs = 0L,
            entries = entries,
        )
    }

    private fun start(rootForAllowlist: File): Triple<LocalLanHttpServer, LanMediaTokenTable, DownloadFolderAllowlist> {
        val tokens = LanMediaTokenTable()
        val allowlist = DownloadFolderAllowlist { listOf(rootForAllowlist) }
        val s = LocalLanHttpServer(
            manifestProvider = fixedManifest(emptyList()),
            tokenTable = tokens,
            allowlist = allowlist,
        )
        s.start(desiredPort = 0, bindToLan = false)
        server = s
        return Triple(s, tokens, allowlist)
    }

    private fun req(
        url: String,
        method: String,
        secret: String? = null,
    ): Triple<Int, ByteArray, Map<String, String>> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        if (secret != null) conn.setRequestProperty("X-Torve-Lan-Auth", secret)
        if (method == "POST") {
            conn.doOutput = true
            // Empty body is fine — the server doesn't read it.
            conn.outputStream.use { /* close to send 0-byte body */ }
        }
        conn.connectTimeout = 1500
        conn.readTimeout = 5000
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes() } ?: ByteArray(0)
        val headers = conn.headerFields
            .filter { it.key != null }
            .mapValues { it.value.firstOrNull().orEmpty() }
            .mapKeys { (k, _) -> k!! }
        conn.disconnect()
        return Triple(status, body, headers)
    }

    @Test
    fun `stream-token issues a token for a registered id`() {
        val root = tmpDir("torve-stoken-ok")
        val file = writeFile(root, "movie.mp4", 256)
        val (s, tokens, _) = start(root)
        tokens.registerEntry("entry-A", file)

        val (status, body, headers) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/entry-A",
            method = "POST",
            secret = s.secret,
        )
        assertEquals(200, status)
        val text = body.decodeToString()
        assertTrue("\"token\":" in text)
        assertTrue("\"path\":\"/local/stream/entry-A?token=" in text, "got $text")
        assertTrue("\"expires_at_epoch_ms\":" in text)
        // Case-insensitive lookup — JDK's HttpURLConnection lowercases
        // some header names depending on platform.
        val cacheControl = headers.entries.firstOrNull { it.key.equals("Cache-Control", ignoreCase = true) }?.value
        assertTrue(cacheControl?.contains("no-store") == true, "expected no-store in Cache-Control: headers=$headers")
    }

    @Test
    fun `stream-token round-trips into a successful streaming GET`() {
        val root = tmpDir("torve-stoken-roundtrip")
        val file = writeFile(root, "movie.mp4", 1024)
        val (s, tokens, _) = start(root)
        tokens.registerEntry("entry-B", file)

        val (_, body, _) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/entry-B",
            method = "POST",
            secret = s.secret,
        )
        // Pull the path out manually — keeps the test light.
        val text = body.decodeToString()
        val pathStart = text.indexOf("\"path\":\"") + 8
        val pathEnd = text.indexOf("\"", pathStart)
        val streamPath = text.substring(pathStart, pathEnd)
        val (status, bytes, _) = req(
            "http://127.0.0.1:${s.port}$streamPath",
            method = "GET",
            secret = s.secret,
        )
        assertEquals(200, status)
        assertEquals(1024, bytes.size)
    }

    @Test
    fun `stream-token returns 404 for an unregistered id`() {
        val root = tmpDir("torve-stoken-unknown")
        val (s, _, _) = start(root)
        val (status, body, _) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/who-knows",
            method = "POST",
            secret = s.secret,
        )
        assertEquals(404, status)
        assertEquals(0, body.size, "404 response must carry no body")
    }

    @Test
    fun `stream-token rejects requests without the auth secret`() {
        val root = tmpDir("torve-stoken-401")
        val file = writeFile(root, "movie.mp4", 64)
        val (s, tokens, _) = start(root)
        tokens.registerEntry("entry-C", file)

        val (unauth, _, _) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/entry-C",
            method = "POST",
            secret = null,
        )
        assertEquals(401, unauth)

        val (wrong, _, _) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/entry-C",
            method = "POST",
            secret = "WRONG",
        )
        assertEquals(401, wrong)
    }

    @Test
    fun `stream-token rejects GET with 405`() {
        val root = tmpDir("torve-stoken-405")
        val file = writeFile(root, "movie.mp4", 16)
        val (s, tokens, _) = start(root)
        tokens.registerEntry("entry-D", file)

        val (status, _, _) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/entry-D",
            method = "GET",
            secret = s.secret,
        )
        assertEquals(405, status)
    }

    @Test
    fun `stream-token response carries no filesystem path`() {
        val root = tmpDir("torve-stoken-no-path")
        val file = writeFile(root, "movie.mp4", 16)
        val (s, tokens, _) = start(root)
        tokens.registerEntry("entry-E", file)

        val (_, body, _) = req(
            "http://127.0.0.1:${s.port}/local/stream-token/entry-E",
            method = "POST",
            secret = s.secret,
        )
        val text = body.decodeToString()
        val rootPath = root.absolutePath
        // The response body must NEVER include the canonical filesystem
        // path or the file name on disk.
        assertTrue(rootPath !in text, "response body leaked filesystem root: $text")
        assertTrue("movie.mp4" !in text, "response body leaked file name: $text")
    }
}
