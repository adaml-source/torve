package com.torve.android.player

import com.torve.domain.player.PlaybackLanguagePolicy
import com.torve.domain.player.TrackDescription
import org.junit.Assert.*
import org.junit.Test

class PlaybackLanguageSelectionTest {
    private fun track(id: Int, language: String) = TrackDescription(id, language, language)
    private fun select(language: String, audio: List<TrackDescription>, subtitles: List<TrackDescription> = emptyList(),
        fallback: Boolean = false, manualAudio: Boolean = false, manualSubtitle: Boolean = false) =
        playbackLanguageSelection(audio, subtitles, language, fallback, "en", "en", "en", false, manualAudio, manualSubtitle)

    @Test fun selectedAudioOverridesOldEnglishChoiceForEverySupportedLanguage() {
        for (option in com.torve.data.addon.SourceLanguages.allOptions()) {
            val result = select(option.code, listOf(track(1, "und"), track(2, option.code.lowercase())))
            assertEquals(option.code, 2, result.audioId)
        }
        assertEquals(2, select("DE", listOf(track(1, "eng"), track(2, "ger"))).audioId)
        assertEquals(2, select("FR", listOf(track(1, "eng"), track(2, "fra"))).audioId)
    }
    @Test fun subtitleFallbackIsOptInAndUsesSelectedLanguage() {
        val audio = listOf(track(1, "eng"))
        val subs = listOf(track(2, "eng"), track(3, "deu"))
        assertNull(select("DE", audio, subs).subtitleId)
        val fallback = select("DE", audio, subs, fallback = true)
        assertEquals(3, fallback.subtitleId)
        assertFalse(fallback.disableSubtitles)
    }
    @Test fun matchingAudioDoesNotForceFallbackSubtitles() {
        val choice = select("DE", listOf(track(1, "deu")), listOf(track(2, "deu")), fallback = true)
        assertEquals(1, choice.audioId)
        assertNull(choice.subtitleId)
    }
    @Test fun manualTrackChoicesSurviveLaterMetadataUpdates() {
        val choice = select("DE", listOf(track(1, "eng")), listOf(track(2, "deu")), true, true, true)
        assertNull(choice.audioId)
        assertNull(choice.subtitleId)
        assertFalse(choice.disableSubtitles)
    }
    @Test fun appLanguageAndAnyLanguageAreResolvedExplicitly() {
        assertEquals("DE", PlaybackLanguagePolicy.preferredLanguage("", "de"))
        assertEquals("FR", PlaybackLanguagePolicy.preferredLanguage("system", "fr"))
        assertEquals("", PlaybackLanguagePolicy.preferredLanguage("any", "de"))
    }
}
