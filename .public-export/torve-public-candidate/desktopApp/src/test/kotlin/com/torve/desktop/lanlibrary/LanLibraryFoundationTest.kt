package com.torve.desktop.lanlibrary

import com.torve.domain.lanlibrary.LanMediaType
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DownloadRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanLibraryFoundationTest {

    private fun tmpDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    private fun writeFile(parent: File, name: String, size: Int = 1024): File {
        val f = File(parent, name)
        f.writeBytes(ByteArray(size) { (it and 0xFF).toByte() })
        f.deleteOnExit()
        return f
    }

    // ── DownloadFolderAllowlist ────────────────────────────────────

    @Test
    fun `allowlist accepts file under root`() {
        val root = tmpDir("torve-allow-")
        val allowed = writeFile(root, "movie.mkv")
        val list = DownloadFolderAllowlist { listOf(root) }
        assertTrue(list.isAllowed(allowed))
    }

    @Test
    fun `allowlist rejects file outside root`() {
        val root = tmpDir("torve-allow-")
        val outside = tmpDir("torve-outside-").let { writeFile(it, "secret.bin") }
        val list = DownloadFolderAllowlist { listOf(root) }
        assertFalse(list.isAllowed(outside))
    }

    @Test
    fun `allowlist rejects parent traversal attempts`() {
        val root = tmpDir("torve-allow-")
        val sub = File(root, "sub").apply { mkdirs(); deleteOnExit() }
        // Build a path string that traverses out: <root>/sub/../../escape.txt
        val traversed = File(sub, "../../escape.txt")
        val list = DownloadFolderAllowlist { listOf(root) }
        assertFalse(list.isAllowed(traversed))
    }

    @Test
    fun `allowlist rejects directories and missing files`() {
        val root = tmpDir("torve-allow-")
        val list = DownloadFolderAllowlist { listOf(root) }
        assertFalse(list.isAllowed(root))                       // directory
        assertFalse(list.isAllowed(File(root, "nope.bin")))     // missing
        assertFalse(list.isAllowed(""))                         // empty string
    }

    @Test
    fun `allowlist rejects everything when no roots configured`() {
        val root = tmpDir("torve-allow-")
        val file = writeFile(root, "x.mkv")
        val list = DownloadFolderAllowlist { emptyList() }
        assertFalse(list.isAllowed(file))
    }

    // ── LanMediaTokenTable ─────────────────────────────────────────

    @Test
    fun `token table issues and resolves`() {
        val root = tmpDir("torve-tok-")
        val file = writeFile(root, "v.mp4")
        val now = arrayOf(1000L)
        val table = LanMediaTokenTable(nowMs = { now[0] })
        table.registerEntry("entry-1", file)
        val token = assertNotNull(table.issueAccessToken("entry-1"))
        val resolved = assertNotNull(table.resolveTokenForId("entry-1", token))
        assertEquals(file.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun `token table rejects unknown id`() {
        val table = LanMediaTokenTable()
        assertNull(table.issueAccessToken("ghost"))
        assertNull(table.resolveTokenForId("ghost", "anything"))
    }

    @Test
    fun `token table rejects expired token`() {
        val root = tmpDir("torve-tok-")
        val file = writeFile(root, "v.mp4")
        val now = arrayOf(1000L)
        val table = LanMediaTokenTable(nowMs = { now[0] }, tokenTtlMs = 100L)
        table.registerEntry("e", file)
        val token = assertNotNull(table.issueAccessToken("e"))
        now[0] = 1101L  // past expiry
        assertNull(table.resolveTokenForId("e", token))
    }

    @Test
    fun `clearAll drops every registration and token`() {
        val root = tmpDir("torve-tok-")
        val file = writeFile(root, "v.mp4")
        val table = LanMediaTokenTable()
        table.registerEntry("e", file)
        val token = assertNotNull(table.issueAccessToken("e"))
        assertEquals(1, table.registeredEntryCount())
        table.clearAll()
        assertEquals(0, table.registeredEntryCount())
        assertNull(table.resolveTokenForId("e", token))
    }

    // ── LanLibraryManifestBuilder ──────────────────────────────────

    @Test
    fun `manifest contains zero filesystem paths`() {
        val root = tmpDir("torve-mf-")
        val movie = writeFile(root, "Movie 2020.mkv", size = 4096)
        val episode = writeFile(root, "Show S01E02.mp4", size = 2048)
        val outside = tmpDir("torve-out-").let { writeFile(it, "leaked.bin") }
        val allowlist = DownloadFolderAllowlist { listOf(root) }
        val tokens = LanMediaTokenTable()
        val builder = LanLibraryManifestBuilder(
            publisherIdProvider = { "desktop-pub-1" },
            allowlist = allowlist,
            tokenTable = tokens,
            nowMs = { 42L },
        )
        val manifest = builder.build(
            inputs = listOf(
                MediaEntryInput(file = movie, title = "Movie 2020", mediaType = LanMediaType.MOVIE),
                MediaEntryInput(file = episode, title = "Show", mediaType = LanMediaType.SHOW_EPISODE,
                    seasonNumber = 1, episodeNumber = 2),
                MediaEntryInput(file = outside, title = "Should not appear", mediaType = LanMediaType.OTHER),
            ),
        )
        assertEquals(2, manifest.entries.size, "out-of-allowlist entry must be dropped")
        assertEquals(42L, manifest.generatedAtEpochMs)
        assertEquals("desktop-pub-1", manifest.publisherId)

        // The manifest body — when rendered as JSON or string — must
        // never carry a real path or an outside-allowlist filename.
        val rendered = manifest.toString()
        assertFalse(rendered.contains(root.absolutePath), "manifest leaked root path")
        assertFalse(rendered.contains(movie.absolutePath), "manifest leaked file path")
        assertFalse(rendered.contains("leaked.bin"))

        // Tokens are issued only after registration.
        val any = manifest.entries.first()
        val token = assertNotNull(tokens.issueAccessToken(any.id))
        assertNotNull(tokens.resolveTokenForId(any.id, token))
    }

    @Test
    fun `manifest ids are stable across rebuilds`() {
        val root = tmpDir("torve-mf-")
        val movie = writeFile(root, "Movie.mkv", size = 1024)
        val allowlist = DownloadFolderAllowlist { listOf(root) }
        val tokens1 = LanMediaTokenTable()
        val tokens2 = LanMediaTokenTable()
        fun build(table: LanMediaTokenTable) = LanLibraryManifestBuilder(
            publisherIdProvider = { "pub" },
            allowlist = allowlist,
            tokenTable = table,
            nowMs = { 0L },
        ).build(listOf(MediaEntryInput(movie, "Movie", LanMediaType.MOVIE)))
        val a = build(tokens1).entries.single().id
        val b = build(tokens2).entries.single().id
        assertEquals(a, b)
    }

    // ── LocalFirstPlaybackRouter ──────────────────────────────────

    private class FakeDownloadRepository(private val rows: List<Download>) : DownloadRepository {
        override suspend fun enqueueDownload(download: Download): Download = error("not used")
        override suspend fun getAllDownloads(): List<Download> = rows
        override suspend fun getPendingDownloads(): List<Download> = rows.filter { it.status != DownloadStatus.COMPLETED }
        override suspend fun getCompletedDownloads(): List<Download> = rows.filter { it.status == DownloadStatus.COMPLETED }
        override suspend fun getDownload(id: String): Download? = rows.firstOrNull { it.id == id }
        override suspend fun getDownloadByMediaId(mediaId: String): Download? =
            rows.firstOrNull { it.mediaId == mediaId }
        override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) {}
        override suspend fun markCompleted(id: String, filePath: String) {}
        override suspend fun updateFileSize(id: String, fileSizeBytes: Long) {}
        override suspend fun deleteDownload(id: String) {}
        override suspend fun pauseDownload(id: String) {}
        override suspend fun resumeDownload(id: String) {}
    }

    @Test
    fun `router prefers local when allowlisted file exists`(): Unit = runBlocking {
        val root = tmpDir("torve-route-")
        val file = writeFile(root, "movie.mkv")
        val repo = FakeDownloadRepository(listOf(
            Download(id = "d1", mediaId = "tmdb:1", mediaType = MediaType.MOVIE,
                title = "M", streamUrl = "https://upstream",
                filePath = file.absolutePath, status = DownloadStatus.COMPLETED),
        ))
        val router = LocalFirstPlaybackRouter(
            downloadRepository = repo,
            allowlist = DownloadFolderAllowlist { listOf(root) },
        )
        val pref = router.route("tmdb:1", providerStreamUrl = "https://upstream")
        val pick = pref.pick()
        val local = assertIs<PlaybackRoute.LocalFile>(pick)
        assertEquals(file.canonicalFile.absolutePath, local.absolutePath)
    }

    @Test
    fun `router falls back to provider when no completed download`(): Unit = runBlocking {
        val repo = FakeDownloadRepository(emptyList())
        val router = LocalFirstPlaybackRouter(
            downloadRepository = repo,
            allowlist = DownloadFolderAllowlist { emptyList() },
        )
        val pref = router.route("tmdb:9", providerStreamUrl = "https://upstream/9")
        assertIs<PlaybackRoute.ProviderStream>(pref.pick())
    }

    @Test
    fun `router falls back when local path is outside allowlist`(): Unit = runBlocking {
        val root = tmpDir("torve-route-")
        val outside = tmpDir("torve-out-").let { writeFile(it, "leaked.mkv") }
        val repo = FakeDownloadRepository(listOf(
            Download(id = "d1", mediaId = "tmdb:1", mediaType = MediaType.MOVIE,
                title = "M", streamUrl = "https://upstream",
                filePath = outside.absolutePath, status = DownloadStatus.COMPLETED),
        ))
        val router = LocalFirstPlaybackRouter(
            downloadRepository = repo,
            allowlist = DownloadFolderAllowlist { listOf(root) },
        )
        val pref = router.route("tmdb:1", providerStreamUrl = "https://upstream")
        assertIs<PlaybackRoute.ProviderStream>(pref.pick())
    }

    @Test
    fun `router returns ReDownload when nothing playable`(): Unit = runBlocking {
        val repo = FakeDownloadRepository(emptyList())
        val router = LocalFirstPlaybackRouter(
            downloadRepository = repo,
            allowlist = DownloadFolderAllowlist { emptyList() },
        )
        val pref = router.route("tmdb:9", providerStreamUrl = null)
        assertEquals(PlaybackRoute.ReDownload, pref.pick())
    }

    @Test
    fun `router ignores in-progress downloads`(): Unit = runBlocking {
        val root = tmpDir("torve-route-")
        val file = writeFile(root, "movie.mkv")
        val repo = FakeDownloadRepository(listOf(
            Download(id = "d1", mediaId = "tmdb:1", mediaType = MediaType.MOVIE,
                title = "M", streamUrl = "https://u",
                filePath = file.absolutePath, status = DownloadStatus.DOWNLOADING),
        ))
        val router = LocalFirstPlaybackRouter(
            downloadRepository = repo,
            allowlist = DownloadFolderAllowlist { listOf(root) },
        )
        val pref = router.route("tmdb:1", providerStreamUrl = "https://upstream")
        assertIs<PlaybackRoute.ProviderStream>(pref.pick())
    }
}
