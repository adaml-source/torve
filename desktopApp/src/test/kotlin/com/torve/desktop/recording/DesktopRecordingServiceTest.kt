package com.torve.desktop.recording

import com.torve.desktop.lanlibrary.DownloadFolderAllowlist
import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFailureReason
import com.torve.domain.recording.RecordingScheduler
import com.torve.domain.recording.RecordingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the desktop recording service's storage boundary:
 *   - happy path writes into the allowlisted recordings root.
 *   - misconfigured root (outside the allowlist) refuses with
 *     OUT_OF_ALLOWLIST and never opens a file outside the boundary.
 *   - missing recordings root surfaces a usable failure copy.
 *   - empty upstream produces UPSTREAM_REJECTED, not COMPLETED.
 *
 * Pure JVM — no real network. The HTTP stream is faked via the
 * service's `openConnection` seam.
 */
class DesktopRecordingServiceTest {

    private val tempDirs: MutableList<File> = mutableListOf()

    @After
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun tmpDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also {
            tempDirs += it
            it.deleteOnExit()
        }

    private fun fakeStream(payload: ByteArray): RecordingHttpStream = object : RecordingHttpStream {
        override val input: java.io.InputStream = ByteArrayInputStream(payload)
        override fun disconnect() {}
    }

    private fun mkRecording(
        id: String,
        startMs: Long,
        endMs: Long,
        streamUrl: String = "http://stream/x",
        title: String = "Sherlock",
        channelName: String = "BBC One",
    ): Recording = Recording(
        id = id,
        playlistId = "p1",
        channelId = "ch-1",
        channelName = channelName,
        streamUrl = streamUrl,
        programmeTitle = title,
        startMs = startMs,
        endMs = endMs,
        createdAtMs = startMs - 1000L,
    )

    @Test
    fun `runNow writes to the allowlisted recordings root and marks completed`() = runBlocking {
        val root = tmpDir("torve-recordings")
        val repo = com.torve.desktop.recording.FileBackedRecordingRepository(tmpDir("repo"))
        val scheduler = RecordingScheduler(repo, nowMs = { 1000L })
        val payload = ByteArray(4096) { (it and 0xFF).toByte() }
        val service = DesktopRecordingService(
            scheduler = scheduler,
            repository = repo,
            allowlist = DownloadFolderAllowlist { listOf(root) },
            recordingsRootProvider = { root },
            nowMs = { 1000L },
            openConnection = { fakeStream(payload) },
        )
        val rec = mkRecording("rec-ok", startMs = 1000L, endMs = 4000L)
        repo.upsert(rec)
        service.runNow(rec)
        // After kickOff returns the job has been launched — wait briefly
        // by polling the repo state.
        val final = waitFor(repo, "rec-ok") {
            it.status == RecordingStatus.COMPLETED || it.status == RecordingStatus.FAILED
        }
        assertEquals(RecordingStatus.COMPLETED, final.status)
        assertEquals(payload.size.toLong(), final.fileSizeBytes)
        // File must exist under the recordings root.
        val on = File(final.filePath!!)
        assertTrue(on.exists())
        assertTrue(on.canonicalPath.startsWith(root.canonicalPath))
        assertEquals(payload.size, on.length().toInt())
    }

    @Test
    fun `runNow refuses when recordings root is outside the allowlist`() = runBlocking {
        val allowedRoot = tmpDir("torve-allowed")
        val outsideRoot = tmpDir("torve-outside")
        val repo = com.torve.desktop.recording.FileBackedRecordingRepository(tmpDir("repo"))
        val scheduler = RecordingScheduler(repo, nowMs = { 1000L })
        var openCount = 0
        val service = DesktopRecordingService(
            scheduler = scheduler,
            repository = repo,
            allowlist = DownloadFolderAllowlist { listOf(allowedRoot) },
            recordingsRootProvider = { outsideRoot },
            nowMs = { 1000L },
            openConnection = {
                openCount++
                fakeStream(ByteArray(16))
            },
        )
        val rec = mkRecording("rec-bad", startMs = 1000L, endMs = 2000L)
        repo.upsert(rec)
        service.runNow(rec)
        val final = waitFor(repo, "rec-bad") {
            it.status == RecordingStatus.FAILED || it.status == RecordingStatus.COMPLETED
        }
        assertEquals(RecordingStatus.FAILED, final.status)
        assertEquals(RecordingFailureReason.OUT_OF_ALLOWLIST, final.failureReason)
        assertEquals(0, openCount, "stream connection must not be opened when allowlist refuses")
        // File MUST NOT exist outside the allowlist.
        assertTrue(!outsideRoot.walkTopDown().any { it.isFile && it.name.endsWith(".ts") })
    }

    @Test
    fun `runNow surfaces missing recordings root with actionable copy`() = runBlocking {
        val repo = com.torve.desktop.recording.FileBackedRecordingRepository(tmpDir("repo"))
        val scheduler = RecordingScheduler(repo, nowMs = { 1000L })
        val service = DesktopRecordingService(
            scheduler = scheduler,
            repository = repo,
            allowlist = DownloadFolderAllowlist { emptyList() },
            recordingsRootProvider = { null },
            nowMs = { 1000L },
            openConnection = { error("not used") },
        )
        val rec = mkRecording("rec-noroot", startMs = 1000L, endMs = 2000L)
        repo.upsert(rec)
        service.runNow(rec)
        val final = waitFor(repo, "rec-noroot") {
            it.status == RecordingStatus.FAILED || it.status == RecordingStatus.COMPLETED
        }
        assertEquals(RecordingStatus.FAILED, final.status)
        assertEquals(RecordingFailureReason.OUT_OF_ALLOWLIST, final.failureReason)
        assertNotNull(final.failureMessage)
        assertTrue(final.failureMessage!!.contains("Settings"))
    }

    @Test
    fun `empty stream produces UPSTREAM_REJECTED, not COMPLETED`() = runBlocking {
        val root = tmpDir("torve-empty")
        val repo = com.torve.desktop.recording.FileBackedRecordingRepository(tmpDir("repo"))
        val scheduler = RecordingScheduler(repo, nowMs = { 1000L })
        val service = DesktopRecordingService(
            scheduler = scheduler,
            repository = repo,
            allowlist = DownloadFolderAllowlist { listOf(root) },
            recordingsRootProvider = { root },
            nowMs = { 1000L },
            openConnection = { fakeStream(ByteArray(0)) },
        )
        val rec = mkRecording("rec-empty", startMs = 1000L, endMs = 2000L)
        repo.upsert(rec)
        service.runNow(rec)
        val final = waitFor(repo, "rec-empty") {
            it.status == RecordingStatus.FAILED || it.status == RecordingStatus.COMPLETED
        }
        assertEquals(RecordingStatus.FAILED, final.status)
        assertEquals(RecordingFailureReason.UPSTREAM_REJECTED, final.failureReason)
    }

    /**
     * Polls the repository for up to [timeoutMs] for [predicate] to
     * pass. The recording job runs on Dispatchers.IO; we need a tiny
     * wait to see the terminal status.
     */
    private fun waitFor(
        repo: com.torve.desktop.recording.FileBackedRecordingRepository,
        id: String,
        timeoutMs: Long = 5_000L,
        predicate: (Recording) -> Boolean,
    ): Recording {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = runBlocking { repo.get(id) }
            if (snap != null && predicate(snap)) return snap
            Thread.sleep(50)
        }
        error("recording $id never reached terminal status within $timeoutMs ms")
    }
}
