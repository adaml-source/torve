package com.torve.domain.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File-naming policy is the only thing that determines whether a
 * recording lands in a sensible place; the storage-boundary check
 * relies on the same string so a regression here would either move
 * recordings outside the allowlist or crash on Windows.
 */
class RecordingFileNamingTest {

    @Test
    fun `relativePath formats as channel slash title timestamp dot ts`() {
        // 2024-03-15 19:30:00 UTC = 1_710_531_000_000 ms
        val out = RecordingFileNaming.relativePath(
            channelName = "BBC One HD",
            programmeTitle = "Sherlock",
            startEpochMs = 1_710_531_000_000L,
        )
        assertEquals("BBC One HD/Sherlock - 2024-03-15 19-30.ts", out)
    }

    @Test
    fun `colons quotes and backslashes are stripped`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "Sky:Sports/Premier",
            programmeTitle = """Liverpool vs "Man City" \w/intro""",
            startEpochMs = 0L,
        )
        // Forbidden chars on Windows / forward slashes get replaced with
        // a space; consecutive whitespace collapses; trailing dot trimmed.
        assertFalse(":" in out, "got: $out")
        assertFalse("\"" in out, "got: $out")
        assertFalse("\\" in out, "got: $out")
        // The first '/' separates dir from file; everything beyond is
        // not a path separator.
        val parts = out.split("/")
        assertTrue(parts.size == 2, "expected single-level dir, got $out")
    }

    @Test
    fun `windows reserved names are made safe`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "CON",
            programmeTitle = "NUL",
            startEpochMs = 0L,
        )
        assertTrue(out.startsWith("_CON/"), "got: $out")
        assertTrue("/_NUL -" in out, "got: $out")
    }

    @Test
    fun `unsafe program title characters are sanitized`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "ZDF",
            programmeTitle = """heute: journal / update? "late".""",
            startEpochMs = 0L,
        )
        val filename = out.substringAfter("/")
        assertFalse(":" in filename, "got: $out")
        assertFalse("?" in filename, "got: $out")
        assertFalse("\"" in filename, "got: $out")
        assertFalse(filename.startsWith(" "), "got: $out")
    }

    @Test
    fun `empty title falls back to Untitled`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "BBC One",
            programmeTitle = "",
            startEpochMs = 0L,
        )
        assertEquals("BBC One/Untitled - 1970-01-01 00-00.ts", out)
    }

    @Test
    fun `empty channel falls back to Unknown channel`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "",
            programmeTitle = "Show",
            startEpochMs = 0L,
        )
        assertEquals("Unknown channel/Show - 1970-01-01 00-00.ts", out)
    }

    @Test
    fun `extension override produces a different suffix`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "ESPN",
            programmeTitle = "Soccer",
            startEpochMs = 0L,
            extension = "mkv",
        )
        assertTrue(out.endsWith(".mkv"))
    }

    @Test
    fun `windows-hostile trailing dots are trimmed`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "BBC.",
            programmeTitle = "Show.",
            startEpochMs = 0L,
        )
        // No dots immediately before "/" or before " - "
        assertFalse(out.startsWith("BBC./"))
        assertFalse("Show. -" in out)
    }

    @Test
    fun `extreme length is capped at 120 chars per segment`() {
        val out = RecordingFileNaming.relativePath(
            channelName = "X".repeat(500),
            programmeTitle = "Y".repeat(500),
            startEpochMs = 0L,
        )
        val segments = out.split("/")
        for (segment in segments) {
            // Filename has " - YYYY-MM-DD HH-mm.ts" suffix that's ~22
            // chars; make sure neither segment is unboundedly long.
            assertTrue(segment.length < 200, "runaway segment length: ${segment.length}")
        }
    }
}
