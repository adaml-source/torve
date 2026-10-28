package com.torve.domain.player

import com.torve.data.addon.*
import com.torve.domain.model.*
import kotlin.test.*

class PlaybackLanguagePolicyTest {
    @Test fun appLanguageIsTheDefaultAndExplicitAudioLanguageOverridesIt() {
        assertEquals("DE", PlaybackLanguagePolicy.preferredLanguage("", "de"))
        assertEquals("DE", PlaybackLanguagePolicy.preferredLanguage("system", "de-DE"))
        assertEquals("EN", PlaybackLanguagePolicy.preferredLanguage("English", "de"))
        assertEquals("", PlaybackLanguagePolicy.preferredLanguage("any", "de"))
    }

    @Test fun germanMetadataAliasesSelectTheRightTrackOnEveryNewSource() {
        val tracks = listOf(track(0, "eng"), track(1, "ger"), track(2, "jpn"))
        assertEquals(1, PlaybackLanguagePolicy.audioId(tracks, "DE", "eng"))
        assertEquals(1, PlaybackLanguagePolicy.audioId(tracks.map { if (it.id == 1) it.copy(language = "deu") else it }, "German", null))
        assertEquals(2, PlaybackLanguagePolicy.audioId(tracks, "JA", null))
        assertTrue(PlaybackTrackSelection.languageMatches("deu", "de-DE"))
        assertFalse(PlaybackTrackSelection.languageMatches("jpn", "de"))
        assertEquals(4, PlaybackTrackSelection.subtitleId(listOf(track(3, "eng"), track(4, "deu")), null, "DE", null))
    }

    @Test fun automaticPlaybackRequiresMatchingAudioOrTheExplicitSubtitleFallback() {
        val base = StreamPreferences(cachedOnly = false, sourceFallbackPolicy = SourceFallbackPolicy.BEST_AVAILABLE)
        val strict = PlaybackLanguagePolicy.sourcePreferences(base, "system", "de", false)
        assertEquals(SourceFallbackPolicy.ASK, strict.sourceFallbackPolicy)
        val german = source("Movie.2025.German.1080p")
        val original = source("Movie.2025.Korean.1080p\nSubtitles: German")
        val unknown = source("Movie.2025.MULTI.1080p")
        val foreign = source("Movie.2025.Korean.1080p\nSubtitles: English")
        assertTrue(StreamAutoSelectionPolicy.evaluate(german, strict).eligible)
        assertFalse(StreamAutoSelectionPolicy.evaluate(original, strict).eligible)
        assertFalse(StreamAutoSelectionPolicy.evaluate(unknown, strict).eligible)
        val fallback = PlaybackLanguagePolicy.sourcePreferences(base, "DE", "en", true)
        assertTrue(StreamAutoSelectionPolicy.evaluate(original, fallback).eligible)
        assertFalse(StreamAutoSelectionPolicy.evaluate(unknown, fallback).eligible)
        assertFalse(StreamAutoSelectionPolicy.evaluate(foreign, fallback, StreamSelectionContext(activeAudioLanguage = "ko")).eligible)
        assertTrue(StreamAutoSelectionPolicy.evaluate(german, fallback).scoreAdjustment > StreamAutoSelectionPolicy.evaluate(original, fallback).scoreAdjustment)
        assertTrue(StreamAutoSelectionPolicy.evaluate(unknown, PlaybackLanguagePolicy.sourcePreferences(base, "any", "de", false)).eligible)
    }

    private fun track(id: Int, language: String) = TrackDescription(id = id, label = language, language = language, isSelected = false)

    @Test fun genericProviderFlagsCannotBypassStrictAudioOrSubtitlePreferences() {
        val base = StreamPreferences(cachedOnly = false)
        val strict = PlaybackLanguagePolicy.sourcePreferences(base, "DE", "en", false)
        val fallback = PlaybackLanguagePolicy.sourcePreferences(base, "DE", "en", true)
        val ambiguous = source("Movie.2025.English.1080p\n🇩🇪 / 🇬🇧 / 🇫🇷")
        val germanSubtitles = source("Movie.2025.1080p\nAudio: English 💬 German")
        assertFalse(StreamAutoSelectionPolicy.evaluate(ambiguous, strict).eligible)
        assertFalse(StreamAutoSelectionPolicy.evaluate(ambiguous, fallback).eligible)
        assertFalse(StreamAutoSelectionPolicy.evaluate(germanSubtitles, strict).eligible)
        assertTrue(StreamAutoSelectionPolicy.evaluate(germanSubtitles, fallback).eligible)
    }

    private fun source(title: String) = StreamParser.parse(StremioStream(title = title, url = "https://example.test/media.mkv"))
}
