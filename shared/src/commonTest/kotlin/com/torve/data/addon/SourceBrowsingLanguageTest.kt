package com.torve.data.addon

import kotlin.test.*

class SourceBrowsingLanguageTest {
    private fun release(title: String) = ParsedStream("Newznab", "1080p", title,
        directUrl = "https://fixture.invalid/video.mkv")

    @Test fun moblandGermanReleasesRemainVisibleBeforeInspection() {
        listOf("MobLand.S01E01.German.ML.EAC3.1080p.WEB.H264-ViDEOTHEK",
            "MobLand.S01E01.German.1080p.BluRay.x264-AIDA",
            "MobLand.S01E01.GERMAN.DL.1080P.WEB.H264.INTERNAL-WAYNE").forEach {
            val stream = release(it)
            assertTrue(SourceTrackLanguagePolicy.matchesForBrowsing(stream, "DE", false), it)
            assertFalse(stream.trackLanguagesVerified)
            // Automatic selection's strict policy is deliberately unchanged.
            assertFalse(SourceTrackLanguagePolicy.matches(stream, "DE", false, readable = true))
        }
    }

    @Test fun checkedEnglishAudioOverridesTheGermanFilenameHint() {
        val stream = SourceLanguages.withVerifiedTracks(release("MobLand.S01E01.German.1080p"),
            listOf("eng"), listOf("deu"))
        assertFalse(SourceTrackLanguagePolicy.matchesForBrowsing(stream, "DE", false))
        assertTrue(SourceTrackLanguagePolicy.matchesForBrowsing(stream, "DE", true))
    }

    @Test fun subtitlesAndUnspecifiedMultiAreNotGermanAudio() {
        listOf("MobLand.S01E01.1080p.MULTI", "MobLand.S01E01.English.1080p.German.Subs").forEach {
            assertFalse(SourceTrackLanguagePolicy.matchesForBrowsing(release(it), "DE", false), it)
        }
        assertTrue(SourceTrackLanguagePolicy.matchesForBrowsing(release("MobLand.S01E01.1080p.MULTI"), "", false))
    }
}
