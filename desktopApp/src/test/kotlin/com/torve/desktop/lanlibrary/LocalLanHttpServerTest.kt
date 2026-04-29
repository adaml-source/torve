package com.torve.desktop.lanlibrary

import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaEntry
import com.torve.domain.lanlibrary.LanMediaType
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots the JDK HttpServer on an OS-assigned localhost port and drives
 * it with java.net.HttpURLConnection. Confirms auth gating, manifest
 * shape, token resolution, range responses, and the no-paths invariant
 * over the wire.
 */
class LocalLanHttpServerTest {

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

    private var server: LocalLanHttpServer? = null

    @After
    fun stopServer() {
        server?.stop()
        server = null
    }

    private fun startWith(
        rootForAllowlist: File,
        manifestEntries: List<LanMediaEntry> = emptyList(),
    ): Triple<LocalLanHttpServer, LanMediaTokenTable, DownloadFolderAllowlist> {
        val tokens = LanMediaTokenTable()
        val allowlist = DownloadFolderAllowlist { listOf(rootForAllowlist) }
        val s = LocalLanHttpServer(
            manifestProvider = fixedManifest(manifestEntries),
            tokenTable = tokens,
            allowlist = allowlist,
        )
        s.start(desiredPort = 0)
        server = s
        return Triple(s, tokens, allowlist)
    }

    private fun get(
        url: String,
        secret: String?,
        rangeHeader: String? = null,
    ): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (secret != null) conn.setRequestProperty("X-Torve-Lan-Auth", secret)
        if (rangeHeader != null) conn.setRequestProperty("Range", rangeHeader)
        conn.connectTimeout = 1500
        conn.readTimeout = 5000
        val status = conn.responseCode
        val headers = conn.headerFields
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes() }
            ?: ByteArray(0)
        conn.disconnect()
        return HttpResult(status, headers.mapValues { it.value.firstOrNull().orEmpty() }, body)
    }

    private data class HttpResult(
        val status: Int,
        val headers: Map<String?, String>,
        val body: ByteArray,
    ) {
        /** Case-insensitive lookup; JDK can store header names with mixed casing. */
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value
    }

    @Test
    fun `manifest 401 without auth header`() {
        val root = tmpDir("torve-lanhttp-")
        val (srv, _, _) = startWith(root)
        val res = get("http://127.0.0.1:${srv.port}/local/manifest", secret = null)
        assertEquals(401, res.status)
    }

    @Test
    fun `manifest 401 with wrong secret`() {
        val root = tmpDir("torve-lanhttp-")
        val (srv, _, _) = startWith(root)
        val res = get("http://127.0.0.1:${srv.port}/local/manifest", secret = "wrong")
        assertEquals(401, res.status)
    }

    @Test
    fun `manifest 200 with valid secret and never carries paths`() {
        val root = tmpDir("torve-lanhttp-")
        val file = writeFile(root, "movie.mkv", size = 1024)
        val entry = LanMediaEntry(
            id = "abc-id",
            title = "Movie",
            mediaType = LanMediaType.MOVIE,
            sizeBytes = 1024,
            containerExtension = "mkv",
            mimeType = "video/x-matroska",
        )
        val (srv, _, _) = startWith(root, listOf(entry))
        val secret = assertNotNull(srv.secret)
        val res = get("http://127.0.0.1:${srv.port}/local/manifest", secret = secret)
        assertEquals(200, res.status)
        val body = res.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"abc-id\""))
        assertTrue(body.contains("\"Movie\""))
        // No path leakage anywhere in the manifest.
        assertTrue(!body.contains(root.absolutePath), "manifest leaked root path")
        assertTrue(!body.contains(file.absolutePath), "manifest leaked file path")
    }

    @Test
    fun `stream 401 without auth`() {
        val root = tmpDir("torve-lanhttp-")
        val (srv, _, _) = startWith(root)
        val res = get("http://127.0.0.1:${srv.port}/local/stream/anything?token=x", secret = null)
        assertEquals(401, res.status)
    }

    @Test
    fun `stream 404 with auth but missing token`() {
        val root = tmpDir("torve-lanhttp-")
        val (srv, _, _) = startWith(root)
        val secret = assertNotNull(srv.secret)
        val res = get("http://127.0.0.1:${srv.port}/local/stream/anything", secret = secret)
        assertEquals(404, res.status)
    }

    @Test
    fun `stream 404 for unregistered id even with valid token format`() {
        val root = tmpDir("torve-lanhttp-")
        val (srv, _, _) = startWith(root)
        val secret = assertNotNull(srv.secret)
        val res = get(
            "http://127.0.0.1:${srv.port}/local/stream/ghost?token=ffffffff",
            secret = secret,
        )
        assertEquals(404, res.status)
    }

    @Test
    fun `stream 200 returns full body when no Range header and id+token match`() {
        val root = tmpDir("torve-lanhttp-")
        val file = writeFile(root, "movie.mkv", size = 1024)
        val (srv, tokens, _) = startWith(root)
        tokens.registerEntry("e1", file)
        val token = assertNotNull(tokens.issueAccessToken("e1"))
        val secret = assertNotNull(srv.secret)
        val res = get(
            "http://127.0.0.1:${srv.port}/local/stream/e1?token=$token",
            secret = secret,
        )
        assertEquals(200, res.status)
        assertEquals(1024, res.body.size)
        // First byte is 0x00, second 0x01, … per writeFile's pattern.
        assertEquals(0, res.body[0].toInt() and 0xFF)
        assertEquals(1, res.body[1].toInt() and 0xFF)
    }

    @Test
    fun `stream 206 honours Range header with correct Content-Range`() {
        val root = tmpDir("torve-lanhttp-")
        val file = writeFile(root, "movie.mkv", size = 1024)
        val (srv, tokens, _) = startWith(root)
        tokens.registerEntry("e1", file)
        val token = assertNotNull(tokens.issueAccessToken("e1"))
        val secret = assertNotNull(srv.secret)
        val res = get(
            "http://127.0.0.1:${srv.port}/local/stream/e1?token=$token",
            secret = secret,
            rangeHeader = "bytes=10-19",
        )
        assertEquals(206, res.status)
        assertEquals(10, res.body.size)
        assertEquals("bytes 10-19/1024", res.header("Content-Range"))
        // The bytes at offset 10..19 should match the writeFile pattern.
        for (i in 0 until 10) {
            assertEquals((10 + i).toByte(), res.body[i])
        }
    }

    @Test
    fun `stop clears secret and rejects subsequent connections`() {
        val root = tmpDir("torve-lanhttp-")
        val (srv, _, _) = startWith(root)
        val secret = assertNotNull(srv.secret)
        val before = get("http://127.0.0.1:${srv.port}/local/manifest", secret = secret)
        assertEquals(200, before.status)
        srv.stop()
        // Secret is cleared.
        assertEquals(null, srv.secret)
        // After stop, port is no longer bound — a fresh connection
        // should fail. We don't assert the exact error type because it
        // varies across JVMs, just that it doesn't return 200 on the
        // (now-defunct) endpoint.
        val res = runCatching {
            get("http://127.0.0.1:${srv.port}/local/manifest", secret = "anything")
        }
        assertNotEquals(200, res.getOrNull()?.status)
    }
}
