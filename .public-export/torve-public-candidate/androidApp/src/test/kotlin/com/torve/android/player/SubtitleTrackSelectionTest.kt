package com.torve.android.player

import com.torve.domain.player.TrackDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three F2 subtitle gaps:
 *   - addon subtitle side-loading is exercised indirectly (the selection
 *     logic treats side-loaded tracks identically to embedded ones),
 *   - preferredSubtitleLanguage applies when no per-content tag is set,
 *   - audio-to-sub fallback enables a sub in the preferred audio language
 *     when no audio track matches.
 *
 * All tests are pure — they record which track id was requested via the
 * `selectTrack` lambda and assert behavior, never touching a real engine.
 */
class SubtitleTrackSelectionTest {

    private fun track(id: Int, language: String?, label: String = "Track $id", isSelected: Boolean = false) =
        TrackDescription(id = id, label = label, language = language, isSelected = isSelected)

    // ── languageMatches ──

    @Test
    fun languageMatches_exactSameCode() {
        assertTrue(subtitleLanguageMatches("en", "en"))
        assertTrue(subtitleLanguageMatches("fr", "fr"))
    }

    @Test
    fun languageMatches_caseInsensitive() {
        assertTrue(subtitleLanguageMatches("EN", "en"))
        assertTrue(subtitleLanguageMatches("  En  ", "en"))
    }

    @Test
    fun languageMatches_iso639_1_vs_639_2() {
        // "en" vs "eng" — common Stremio mismatch.
        assertTrue(subtitleLanguageMatches("eng", "en"))
        assertTrue(subtitleLanguageMatches("en", "eng"))
    }

    @Test
    fun languageMatches_regionalVariants() {
        assertTrue(subtitleLanguageMatches("pt-BR", "pt"))
        assertTrue(subtitleLanguageMatches("en_US", "en"))
        assertTrue(subtitleLanguageMatches("pt", "pt-BR"))
    }

    @Test
    fun languageMatches_rejectsDifferentLanguage() {
        assertFalse(subtitleLanguageMatches("en", "fr"))
        assertFalse(subtitleLanguageMatches("de", "en"))
    }

    @Test
    fun languageMatches_rejectsNullAndBlank() {
        assertFalse(subtitleLanguageMatches(null, "en"))
        assertFalse(subtitleLanguageMatches("", "en"))
        assertFalse(subtitleLanguageMatches("   ", "en"))
    }

    // ── selectPreferredSubtitle: Priority 1 (per-content tag) ──

    @Test
    fun perContentTag_winsOverLanguagePreference() {
        val tracks = listOf(
            track(id = 1, language = "en"),
            track(id = 2, language = "fr"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = "fr",
            preferredSubtitleLanguage = "en", // would win at priority 2
            audioFallbackLanguage = null,
            selectTrack = { chosen += it },
        )
        assertTrue("applied should be true", applied)
        assertEquals("Per-content tag must beat Settings preference", listOf(2), chosen)
    }

    @Test
    fun perContentTag_missingFromTracks_fallsThroughToLanguage() {
        val tracks = listOf(track(id = 1, language = "en"))
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = "xx", // stale tag from prior session
            preferredSubtitleLanguage = "en",
            audioFallbackLanguage = null,
            selectTrack = { chosen += it },
        )
        assertTrue(applied)
        assertEquals(listOf(1), chosen)
    }

    // ── selectPreferredSubtitle: Priority 2 (preferredSubtitleLanguage) ──

    @Test
    fun preferredSubtitleLanguage_applied_whenNoPerContentTag() {
        val tracks = listOf(
            track(id = 1, language = "en"),
            track(id = 2, language = "fr"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = "fr",
            audioFallbackLanguage = null,
            selectTrack = { chosen += it },
        )
        assertTrue(applied)
        assertEquals(listOf(2), chosen)
    }

    @Test
    fun preferredSubtitleLanguage_noMatch_leavesSelectionToEngine() {
        val tracks = listOf(
            track(id = 1, language = "en"),
            track(id = 2, language = "de"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = "ja",
            audioFallbackLanguage = null,
            selectTrack = { chosen += it },
        )
        assertFalse(applied)
        assertTrue("No track should be forced when no preference matches", chosen.isEmpty())
    }

    @Test
    fun preferredSubtitleLanguage_skipsIfAlreadySelected() {
        val tracks = listOf(
            track(id = 1, language = "en", isSelected = true),
            track(id = 2, language = "fr"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = "en",
            audioFallbackLanguage = null,
            selectTrack = { chosen += it },
        )
        assertTrue(applied)
        assertTrue("Already-selected track shouldn't trigger selectTrack", chosen.isEmpty())
    }

    // ── selectPreferredSubtitle: Priority 3 (audio-to-sub fallback) ──

    @Test
    fun audioFallback_enablesSubInPreferredAudioLanguage() {
        val tracks = listOf(
            track(id = 1, language = "en"),
            track(id = 2, language = "fr"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = null,
            audioFallbackLanguage = "fr",
            selectTrack = { chosen += it },
        )
        assertTrue(applied)
        assertEquals("Fallback should enable a sub in the preferred audio language", listOf(2), chosen)
    }

    @Test
    fun audioFallback_noOp_whenMatchingSubMissing() {
        val tracks = listOf(track(id = 1, language = "en"))
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = null,
            audioFallbackLanguage = "ja",
            selectTrack = { chosen += it },
        )
        assertFalse(applied)
        assertTrue("No Japanese sub available → no forced selection", chosen.isEmpty())
    }

    @Test
    fun audioFallback_doesNotFire_whenSubtitleLanguageIsSet() {
        // Priority 2 takes over before fallback can fire.
        val tracks = listOf(
            track(id = 1, language = "en"),
            track(id = 2, language = "fr"),
            track(id = 3, language = "de"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = "en",
            audioFallbackLanguage = "fr", // would fire at priority 3
            selectTrack = { chosen += it },
        )
        assertTrue(applied)
        assertEquals("Explicit sub preference must beat audio fallback", listOf(1), chosen)
    }

    // ── Side-loaded tracks (F2-A) ──

    @Test
    fun sideLoadedTracks_treatedIdenticallyToEmbedded() {
        // A TrackDescription from a side-loaded SubtitleConfiguration has
        // the same shape as an embedded track — the selection helper
        // doesn't care where it came from. Side-loaded OpenSubtitles
        // entries should match on language exactly like built-in tracks.
        val tracks = listOf(
            track(id = 10, language = "en", label = "Embedded English"),
            track(id = 11, language = "fr", label = "OpenSubtitles FR"),
        )
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = tracks,
            perContentTag = null,
            preferredSubtitleLanguage = "fr",
            audioFallbackLanguage = null,
            selectTrack = { chosen += it },
        )
        assertTrue(applied)
        assertEquals(listOf(11), chosen)
    }

    // ── Empty list safety ──

    @Test
    fun emptyTrackList_returnsFalseWithNoSideEffects() {
        val chosen = mutableListOf<Int>()
        val applied = selectPreferredSubtitle(
            subtitleTracks = emptyList(),
            perContentTag = "en",
            preferredSubtitleLanguage = "fr",
            audioFallbackLanguage = "de",
            selectTrack = { chosen += it },
        )
        assertFalse(applied)
        assertTrue(chosen.isEmpty())
    }
}
