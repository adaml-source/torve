package com.torve.desktop.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingStatus
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.DosFileAttributeView
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingPathResolverTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun tempRoot(name: String): File =
        Files.createTempDirectory(name).toFile().also { tempDirs += it }

    private fun recording(
        channelName: String = "DE: ZDF HD (LOW BIT)",
        title: String = "heute: journal / late?",
    ) = Recording(
        id = "rec-1",
        playlistId = "p1",
        channelId = "ch1",
        channelName = channelName,
        streamUrl = "http://stream",
        programmeTitle = title,
        startMs = 1_710_531_000_000L,
        endMs = 1_710_534_600_000L,
        status = RecordingStatus.SCHEDULED,
        createdAtMs = 1_710_530_000_000L,
    )

    @Test
    fun `existing base folder succeeds`() {
        val root = tempRoot("torve-existing-recordings")

        val result = RecordingFolderValidator.ensureWritableDirectory(root)

        assertTrue(result is RecordingPathResult.Ready)
    }

    @Test
    fun `windows directory read only attribute does not make existing writable folder fail`() {
        val root = tempRoot("torve-readonly-recordings")
        val view = Files.getFileAttributeView(root.toPath(), DosFileAttributeView::class.java)
            ?: return

        try {
            view.setReadOnly(true)

            val result = RecordingFolderValidator.ensureWritableDirectory(root)

            assertTrue(result is RecordingPathResult.Ready)
            val target = RecordingPathResolver.resolve(root, recording()) as RecordingPathResult.Ready
            target.file.parentFile.mkdirs()
            target.file.writeText("ok")
            assertTrue(target.file.exists())
        } finally {
            runCatching { view.setReadOnly(false) }
        }
    }

    @Test
    fun `missing base folder is created recursively`() {
        val root = File(tempRoot("torve-parent"), "user/Recordings")

        val result = RecordingFolderValidator.ensureWritableDirectory(root)

        assertTrue(result is RecordingPathResult.Ready)
        assertTrue(root.isDirectory)
    }

    @Test
    fun `output path does not append duplicate Recordings segment`() {
        val root = File(tempRoot("torve-recordings"), "Recordings").apply { mkdirs() }

        val result = RecordingPathResolver.resolve(root, recording()) as RecordingPathResult.Ready

        assertFalse("Recordings${File.separator}Recordings" in result.file.absolutePath)
        assertTrue(result.file.absolutePath.startsWith(root.absolutePath))
    }

    @Test
    fun `unsafe channel and programme names are sanitized`() {
        val root = tempRoot("torve-safe-name")

        val result = RecordingPathResolver.resolve(root, recording()) as RecordingPathResult.Ready
        val relative = result.file.relativeTo(root).path

        assertFalse(":" in relative, "got: $relative")
        assertFalse("?" in relative, "got: $relative")
        assertFalse("\\" in result.file.name, "got: $relative")
    }

    @Test
    fun `existing target file gets numeric suffix`() {
        val root = tempRoot("torve-collision")
        val first = RecordingPathResolver.resolve(root, recording()) as RecordingPathResult.Ready
        first.file.parentFile.mkdirs()
        first.file.writeText("already here")

        val second = RecordingPathResolver.resolve(root, recording()) as RecordingPathResult.Ready

        assertTrue(second.file.nameWithoutExtension.endsWith("(2)"), "got: ${second.file.name}")
    }

    @Test
    fun `file at folder path fails cleanly`() {
        val root = tempRoot("torve-file-path")
        val fileAsFolder = File(root, "Recordings").apply { writeText("not a dir") }

        val result = RecordingFolderValidator.ensureWritableDirectory(fileAsFolder)

        assertTrue(result is RecordingPathResult.Failed)
        assertEquals(RecordingFolderIssue.RECORDING_PATH_IS_FILE, result.failure.issue)
    }
}
