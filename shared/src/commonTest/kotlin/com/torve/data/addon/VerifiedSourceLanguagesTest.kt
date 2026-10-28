package com.torve.data.addon

import kotlin.test.*

class VerifiedSourceLanguagesTest {
    private val source = ParsedStream("Provider", "1080p", "Film.2025.1080p.German.MULTI.German.Subs")

    @Test fun englishAudioGermanSubtitlesRequireFallback() {
        val verified = SourceLanguages.withVerifiedTracks(source, listOf("eng"), listOf("deu"))
        assertFalse(SourceLanguages.matchesPreference(verified, "DE", false))
        assertTrue(SourceLanguages.matchesPreference(verified, "DE", true))
        assertTrue(SourceLanguages.matchesPreference(verified, "EN", false))
        assertEquals(listOf("EN"), SourceLanguages.codes(verified))
        assertEquals(listOf("DE"), SourceLanguages.subtitleCodes(verified))
    }

    @Test fun verifiedAbsenceDoesNotFallBackToFilenameOrAdvertisedFlags() {
        val verified = SourceLanguages.withVerifiedTracks(source.copy(languages = listOf("DE"),
            subtitleLanguages = listOf("DE")), listOf("und"), emptyList())
        assertTrue(SourceLanguages.codes(verified).isEmpty())
        assertTrue(SourceLanguages.subtitleCodes(verified).isEmpty())
        assertFalse(SourceLanguages.matchesPreference(verified, "DE", true))
        assertTrue(SourceLanguages.matches(verified, "UNKNOWN"))
    }

    @Test fun realMultiAudioWithoutFilenameTagsBecomesSelectable() {
        val verified = SourceLanguages.withVerifiedTracks(source.copy(title = "Film"),
            listOf("en-US", "ger", "de-DE"), listOf("fra"))
        assertEquals(listOf("EN", "DE"), SourceLanguages.codes(verified))
        assertTrue(SourceLanguages.matchesPreference(verified, "DE", false))
        assertFalse(SourceLanguages.matchesPreference(verified, "FR", false))
        assertTrue(SourceLanguages.matchesPreference(verified, "FR", true))
        assertEquals(source.addonName, verified.addonName)
    }
}
