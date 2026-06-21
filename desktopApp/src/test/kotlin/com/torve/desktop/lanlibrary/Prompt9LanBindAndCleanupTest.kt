package com.torve.desktop.lanlibrary

import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaEntry
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.runBlocking
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
 * Prompt 9 acceptance:
 *   - LAN bind toggle actually binds to a non-loopback address.
 *   - Auth + range still apply on LAN-bound mode.
 *   - Storage cleanup never deletes files outside the allowlist.
 */
class Prompt9LanBindAndCleanupTest {

    private var server: LocalLanHttpServer? = null

    @After
    fun stopServer() {
        server?.stop()
        server = null
    }

    private fun tmpDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    private fun fixedManifest(entries: List<LanMediaEntry>): () -> LanLibraryManifest = {
        LanLibraryManifest(
            version = MANIFEST_VERSION,
            publisherId = "pub",
            generatedAtEpochMs = 0L,
            entries = entries,
        )
    }

    // ── LAN bind ──────────────────────────────────────────────────────

    @Test
    fun `bindToLan = false stays on loopback`() {
        val root = tmpDir("torve-lan-loop")
        val s = LocalLanHttpServer(
            manifestProvider = fixedManifest(emptyList()),
            tokenTable = LanMediaTokenTable(),
            allowlist = DownloadFolderAllowlist { listOf(root) },
        )
        s.start(desiredPort = 0, bindToLan = false)
        server = s
        val addr = s.boundAddress
        assertNotNull(addr)
        assertEquals(false, s.isBoundToLan, "loopback bind must not report LAN")
        assertTrue(addr.address.isLoopbackAddress, "expected loopback host, got ${addr.address}")
    }

    @Test
    fun `bindToLan = true reports LAN bind and serves the manifest with auth`() {
        val root = tmpDir("torve-lan-bound")
        val s = LocalLanHttpServer(
            manifestProvider = fixedManifest(emptyList()),
            tokenTable = LanMediaTokenTable(),
            allowlist = DownloadFolderAllowlist { listOf(root) },
        )
        s.start(desiredPort = 0, bindToLan = true)
        server = s
        // The wildcard bind exposes loopback too — we use that to drive
        // the test deterministically without needing a real LAN peer.
        val port = s.port
        assertTrue(port > 0)
        assertEquals(true, s.isBoundToLan, "0.0.0.0 bind must report LAN bound")

        // Manifest still requires the auth header.
        val unauthed = HttpURLConnection.HTTP_UNAUTHORIZED
        val unauthResult = get("http://127.0.0.1:$port/local/manifest", secret = null)
        assertEquals(unauthed, unauthResult.first, "expected 401 without secret")

        val authResult = get("http://127.0.0.1:$port/local/manifest", secret = s.secret)
        assertEquals(200, authResult.first, "expected 200 with secret on LAN bind")
        assertTrue(authResult.second.isNotEmpty())
    }

    @Test
    fun `LAN-bound stream rejects wrong token with 404`() {
        val root = tmpDir("torve-lan-stream")
        val file = writeFile(root, "movie.mp4", 1024)
        val tokens = LanMediaTokenTable()
        tokens.registerEntry("entry-1", file)
        val allowlist = DownloadFolderAllowlist { listOf(root) }
        val s = LocalLanHttpServer(
            manifestProvider = fixedManifest(emptyList()),
            tokenTable = tokens,
            allowlist = allowlist,
        )
        s.start(desiredPort = 0, bindToLan = true)
        server = s
        // Wrong token → 404. Right token → 200.
        val wrong = get(
            "http://127.0.0.1:${s.port}/local/stream/entry-1?token=BOGUS",
            secret = s.secret,
        )
        assertEquals(404, wrong.first)
        val ok = tokens.issueAccessToken("entry-1")
        assertNotNull(ok)
        val good = get(
            "http://127.0.0.1:${s.port}/local/stream/entry-1?token=$ok",
            secret = s.secret,
        )
        assertEquals(200, good.first)
        assertEquals(1024, good.second.size)
    }

    @Test
    fun `LAN-bound stream honors a partial Range request`() {
        val root = tmpDir("torve-lan-range")
        val file = writeFile(root, "movie.mp4", 4096)
        val tokens = LanMediaTokenTable()
        tokens.registerEntry("entry-r", file)
        val s = LocalLanHttpServer(
            manifestProvider = fixedManifest(emptyList()),
            tokenTable = tokens,
            allowlist = DownloadFolderAllowlist { listOf(root) },
        )
        s.start(desiredPort = 0, bindToLan = true)
        server = s
        val token = tokens.issueAccessToken("entry-r")!!
        val partial = get(
            "http://127.0.0.1:${s.port}/local/stream/entry-r?token=$token",
            secret = s.secret,
            rangeHeader = "bytes=100-199",
        )
        assertEquals(206, partial.first, "expected partial-content")
        assertEquals(100, partial.second.size, "expected exactly the requested 100 bytes")
    }

    // ── Storage cleanup boundary ──────────────────────────────────────

    @Test
    fun `cleanWatched skips a row whose path is outside the allowlist`() = runBlocking {
        val allowedRoot = tmpDir("torve-allowed")
        val outsideRoot = tmpDir("torve-outside")
        val outsideFile = writeFile(outsideRoot, "leaked.mp4", 16)

        val downloadRow = Download(
            id = "row-out",
            mediaId = "tmdb:111",
            mediaType = MediaType.MOVIE,
            title = "Leaked",
            streamUrl = "https://x",
            status = DownloadStatus.COMPLETED,
            filePath = outsideFile.absolutePath,
        )
        val cleanup = WatchedDownloadCleanup(
            downloadRepository = SingleRowDownloadRepo(downloadRow, deletedIds = mutableListOf()),
            watchHistoryRepository = AlwaysWatchedRepo(),
            allowlist = DownloadFolderAllowlist { listOf(allowedRoot) },
        )
        val outcomes = cleanup.cleanWatched()
        val sole = outcomes.single()
        assertTrue(sole is WatchedDownloadCleanup.CleanupOutcome.Skipped, "expected skip, got $sole")
        assertEquals(WatchedDownloadCleanup.SkipReason.OUTSIDE_ALLOWLIST, sole.reason)
        assertTrue(outsideFile.exists(), "file outside the allowlist must NOT be deleted")
    }

    @Test
    fun `cleanWatched deletes a watched in-allowlist row and reports freed bytes`() = runBlocking {
        val allowedRoot = tmpDir("torve-clean")
        val file = writeFile(allowedRoot, "watched.mp4", 2048)
        val deletedIds = mutableListOf<String>()
        val downloadRow = Download(
            id = "row-clean",
            mediaId = "tmdb:222",
            mediaType = MediaType.MOVIE,
            title = "Watched Movie",
            streamUrl = "https://x",
            status = DownloadStatus.COMPLETED,
            filePath = file.absolutePath,
        )
        val cleanup = WatchedDownloadCleanup(
            downloadRepository = SingleRowDownloadRepo(downloadRow, deletedIds = deletedIds),
            watchHistoryRepository = AlwaysWatchedRepo(),
            allowlist = DownloadFolderAllowlist { listOf(allowedRoot) },
        )
        val outcomes = cleanup.cleanWatched()
        val sole = outcomes.single()
        assertTrue(sole is WatchedDownloadCleanup.CleanupOutcome.Deleted)
        assertEquals(2048L, sole.freedBytes)
        assertEquals(false, file.exists(), "file should be deleted")
        assertEquals(listOf("row-clean"), deletedIds)
    }

    @Test
    fun `cleanWatched skips an unwatched row even if it's in the allowlist`() = runBlocking {
        val allowedRoot = tmpDir("torve-unwatched")
        val file = writeFile(allowedRoot, "fresh.mp4", 32)
        val cleanup = WatchedDownloadCleanup(
            downloadRepository = SingleRowDownloadRepo(
                Download(
                    id = "row-fresh",
                    mediaId = "tmdb:333",
                    mediaType = MediaType.MOVIE,
                    title = "Unwatched",
                    streamUrl = "u",
                    status = DownloadStatus.COMPLETED,
                    filePath = file.absolutePath,
                ),
                deletedIds = mutableListOf(),
            ),
            watchHistoryRepository = NeverWatchedRepo(),
            allowlist = DownloadFolderAllowlist { listOf(allowedRoot) },
        )
        val sole = cleanup.cleanWatched().single()
        assertTrue(sole is WatchedDownloadCleanup.CleanupOutcome.Skipped)
        assertEquals(WatchedDownloadCleanup.SkipReason.NOT_WATCHED, sole.reason)
        assertTrue(file.exists())
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun writeFile(parent: File, name: String, size: Int): File {
        val f = File(parent, name)
        f.writeBytes(ByteArray(size) { (it and 0xFF).toByte() })
        f.deleteOnExit()
        return f
    }

    private fun get(url: String, secret: String?, rangeHeader: String? = null): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (secret != null) conn.setRequestProperty("X-Torve-Lan-Auth", secret)
        if (rangeHeader != null) conn.setRequestProperty("Range", rangeHeader)
        conn.connectTimeout = 2000
        conn.readTimeout = 5000
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes() } ?: ByteArray(0)
        conn.disconnect()
        return status to body
    }

    private class SingleRowDownloadRepo(
        private val row: Download,
        val deletedIds: MutableList<String>,
    ) : DownloadRepository {
        override suspend fun enqueueDownload(download: Download) = error("not used")
        override suspend fun getAllDownloads() = listOf(row)
        override suspend fun getPendingDownloads() = emptyList<Download>()
        override suspend fun getCompletedDownloads(): List<Download> =
            if (row.status == DownloadStatus.COMPLETED) listOf(row) else emptyList()
        override suspend fun getDownload(id: String): Download? = if (id == row.id) row else null
        override suspend fun getDownloadByMediaId(mediaId: String) =
            if (mediaId == row.mediaId) row else null
        override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) {}
        override suspend fun markCompleted(id: String, filePath: String) {}
        override suspend fun updateFileSize(id: String, fileSizeBytes: Long) {}
        override suspend fun deleteDownload(id: String) { deletedIds += id }
        override suspend fun pauseDownload(id: String) {}
        override suspend fun resumeDownload(id: String) {}
    }

    private class AlwaysWatchedRepo : WatchHistoryRepository {
        override suspend fun getRecent(limit: Int) = emptyList<WatchHistoryEntry>()
        override suspend fun getByDateRange(startMs: Long, endMs: Long) = emptyList<WatchHistoryEntry>()
        override suspend fun getAll() = emptyList<WatchHistoryEntry>()
        override suspend fun getForMedia(mediaId: String) = listOf(
            WatchHistoryEntry(
                id = "h-$mediaId", mediaId = mediaId, mediaType = "movie",
                title = "x", posterUrl = null, backdropUrl = null,
                watchedAt = 0L, durationWatchedMs = 60_000L,
                seasonNumber = null, episodeNumber = null, showTitle = null,
            ),
        )
        override suspend fun record(entry: WatchHistoryEntry) {}
        override suspend fun delete(id: String) {}
        override suspend fun clearAll() {}
        override suspend fun getCount() = 0L
        override suspend fun syncFromTrakt() {}
    }

    private class NeverWatchedRepo : WatchHistoryRepository {
        override suspend fun getRecent(limit: Int) = emptyList<WatchHistoryEntry>()
        override suspend fun getByDateRange(startMs: Long, endMs: Long) = emptyList<WatchHistoryEntry>()
        override suspend fun getAll() = emptyList<WatchHistoryEntry>()
        override suspend fun getForMedia(mediaId: String) = emptyList<WatchHistoryEntry>()
        override suspend fun record(entry: WatchHistoryEntry) {}
        override suspend fun delete(id: String) {}
        override suspend fun clearAll() {}
        override suspend fun getCount() = 0L
        override suspend fun syncFromTrakt() {}
    }
}
