package com.torve.desktop.playback

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins behaviour of [scanSiblingSubtitles] (Sprint 5 #3 — local-file
 * sibling subtitle scan). All tests use a real `TemporaryFolder` so they
 * actually exercise the JVM filesystem behaviour on the host platform.
 */
class SiblingSubtitleScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(parent: File, name: String): File {
        val f = File(parent, name)
        f.writeText("")
        return f
    }

    @Test
    fun `exact stem match picks up srt`() {
        val movie = touch(tmp.root, "Movie.mkv")
        touch(tmp.root, "Movie.srt")
        val results = scanSiblingSubtitles(movie)
        assertEquals(1, results.size)
        assertEquals("Movie.srt", results[0].label)
    }

    @Test
    fun `case-insensitive stem match works for lowercased subtitle`() {
        val movie = touch(tmp.root, "Inception.2010.mkv")
        touch(tmp.root, "inception.2010.en.srt")
        val results = scanSiblingSubtitles(movie)
        assertEquals(1, results.size, "stem match must be case-insensitive")
        // CRITICAL: language tag must be derived from the suffix, not from the
        // start of the filename when the case differs. Old code used
        // `removePrefix` which is case-sensitive and would have left the whole
        // name intact, producing language="inc" instead of "en".
        assertEquals("en", results[0].language)
    }

    @Test
    fun `language suffix variants extracted correctly for two-letter codes`() {
        val movie = touch(tmp.root, "Show.S01E01.mkv")
        touch(tmp.root, "Show.S01E01.de.srt")
        touch(tmp.root, "Show.S01E01.en.srt")
        val results = scanSiblingSubtitles(movie).associateBy { it.label }
        assertEquals("de", results["Show.S01E01.de.srt"]!!.language)
        assertEquals("en", results["Show.S01E01.en.srt"]!!.language)
    }

    @Test
    fun `subtitle with no language suffix gets fallback tag`() {
        val movie = touch(tmp.root, "Movie.mkv")
        touch(tmp.root, "Movie.srt")
        val results = scanSiblingSubtitles(movie)
        assertEquals(1, results.size)
        assertEquals("sub", results[0].language)
    }

    @Test
    fun `all five extensions are recognised`() {
        val movie = touch(tmp.root, "Reel.mkv")
        touch(tmp.root, "Reel.srt")
        touch(tmp.root, "Reel.vtt")
        touch(tmp.root, "Reel.ass")
        touch(tmp.root, "Reel.ssa")
        touch(tmp.root, "Reel.sub")
        val labels = scanSiblingSubtitles(movie).map { it.label }.toSet()
        assertEquals(setOf("Reel.srt", "Reel.vtt", "Reel.ass", "Reel.ssa", "Reel.sub"), labels)
    }

    @Test
    fun `extension match is case-insensitive`() {
        val movie = touch(tmp.root, "Reel.mkv")
        touch(tmp.root, "Reel.SRT")
        val results = scanSiblingSubtitles(movie)
        assertEquals(1, results.size)
        assertEquals("Reel.SRT", results[0].label)
    }

    @Test
    fun `unrelated extension is ignored`() {
        val movie = touch(tmp.root, "Reel.mkv")
        touch(tmp.root, "Reel.txt")
        touch(tmp.root, "Reel.sup") // PGS subs not in allow-list
        val results = scanSiblingSubtitles(movie)
        assertTrue(results.isEmpty(), "should ignore non-allowlist extensions")
    }

    @Test
    fun `subtitles in different folders are not picked up`() {
        val movie = touch(tmp.root, "Movie.mkv")
        val otherFolder = File(tmp.root, "siblings").apply { mkdirs() }
        touch(otherFolder, "Movie.srt")
        val results = scanSiblingSubtitles(movie)
        assertTrue(results.isEmpty(), "only same-folder siblings count")
    }

    @Test
    fun `unrelated stem in same folder is not picked up`() {
        val movie = touch(tmp.root, "MyMovie.mkv")
        touch(tmp.root, "OtherMovie.srt")
        val results = scanSiblingSubtitles(movie)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `no match returns empty list not null`() {
        val movie = touch(tmp.root, "Lonely.mkv")
        val results = scanSiblingSubtitles(movie)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `directories matching extension are not treated as files`() {
        val movie = touch(tmp.root, "Reel.mkv")
        File(tmp.root, "Reel.srt").apply { mkdirs() }
        // It's a directory named Reel.srt — should be filtered out by isFile.
        val results = scanSiblingSubtitles(movie)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `results sorted alphabetically by stem`() {
        val movie = touch(tmp.root, "Show.mkv")
        touch(tmp.root, "Show.zh.srt")
        touch(tmp.root, "Show.en.srt")
        touch(tmp.root, "Show.de.srt")
        val labels = scanSiblingSubtitles(movie).map { it.label }
        assertEquals(listOf("Show.de.srt", "Show.en.srt", "Show.zh.srt"), labels)
    }

    @Test
    fun `parent directory missing returns empty list`() {
        val phantom = File(tmp.root, "does-not-exist/Movie.mkv")
        val results = scanSiblingSubtitles(phantom)
        assertTrue(results.isEmpty())
    }
}
