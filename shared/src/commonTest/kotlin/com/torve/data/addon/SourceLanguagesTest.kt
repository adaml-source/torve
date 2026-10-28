package com.torve.data.addon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceLanguagesTest {
    @Test fun subtitleLanguageLabelsNeverBecomeAudioLabels() {
        assertEquals(emptyList(), SourceLanguages.extract("Subtitle languages: German"))
        assertEquals(listOf("DE"), SourceLanguages.extractSubtitles("Subtitles: German Audio: English"))
    }
    @Test
    fun parsesAudioBadgesNamesCodesAndFlags() {
        assertEquals(listOf("DE", "EN"), StreamParser.extractLanguages("Movie\n🗣️ DE, ENG\n⚙ Indexer"))
        assertEquals(listOf("FR", "IT", "PT-BR"), SourceLanguages.extract("Audio: French / ita / PT-BR"))
        assertEquals(listOf("DE", "EN"), SourceLanguages.extract("Movie\nAudio: 🇩🇪 🇺🇸"))
        assertEquals(emptyList(), SourceLanguages.extract("Movie 🇮🇳"))
    }

    @Test
    fun torrentioUntypedFlagsDoNotQualifyAsAudioOrSubtitleTracks() {
        // Torrentio joins torrent, filename and provider language hints into an
        // untyped flag line. It is not an inventory of the file's audio tracks.
        val source = StreamParser.parse(StremioStream(
            name = "[TB+] Torrentio\n1080p",
            title = "War.of.the.Worlds.2025.1080p.WEB-DL.H264\n" +
                "👤 11 💾 4.74 GB ⚙️ Indexer\n" +
                "🇬🇧 / 🇩🇪 / 🇫🇷 / 🇪🇸 / 🇮🇹 / 🇯🇵 / 🇰🇷",
            behaviorHints = StemioBehaviorHints(filename = "War.of.the.Worlds.2025.English.1080p.mkv"),
        ))
        assertEquals(listOf("EN"), source.languages)
        assertEquals(emptyList(), source.subtitleLanguages)
        assertFalse(SourceLanguages.matchesPreference(source, "DE", false))
        assertFalse(SourceLanguages.matchesPreference(source, "DE", true))
        assertEquals("🇬🇧 English", SourceLanguages.label(source))
        assertEquals(emptyList(), SourceLanguages.extract("Movie.2025.1080p\n🇩🇪 🇺🇸"))
        assertEquals(emptyList(), SourceLanguages.extract("Movie.2025.1080p Languages: German, English"))
    }

    @Test
    fun subtitleSectionsStaySeparateEvenWithoutColonsOrOnTheSameLine() {
        for (metadata in listOf(
            "Audio: English 💬 🇩🇪 🇫🇷",
            "🗣️ English\nSubtitles German, French",
            "Subtitles: German, French Audio: English",
            "Audio: English | CC: German, French",
        )) {
            val source = StreamParser.parse(StremioStream(title = "Movie.2025.1080p\n$metadata"))
            assertEquals(listOf("EN"), source.languages, metadata)
            assertEquals(listOf("DE", "FR"), source.subtitleLanguages, metadata)
            assertFalse(SourceLanguages.matchesPreference(source, "DE", false), metadata)
            assertTrue(SourceLanguages.matchesPreference(source, "DE", true), metadata)
        }
    }

    @Test
    fun releaseSubtitleListsDoNotLeakIntoAudioTags() {
        for (release in listOf(
            "Movie.2025.English.1080p.[German,French.Subs].mkv",
            "Movie.2025.English.1080p.Subs.German.French.mkv",
        )) {
            assertEquals(listOf("EN"), SourceLanguages.extract(release), release)
            assertEquals(setOf("DE", "FR"), SourceLanguages.extractSubtitles(release).toSet(), release)
        }
    }

    @Test
    fun readsActualFilenameEvenWhenAddonProvidesAFriendlyTitle() {
        val source = StreamParser.parse(StremioStream(
            title = "Example Movie", behaviorHints = StemioBehaviorHints(
                filename = "Example.Movie.2025.German.English.1080p.WEB-DL.mkv")))
        assertEquals(listOf("DE", "EN"), source.languages)
        assertEquals(listOf("DE", "EN"), SourceLanguages.extract("Movie.S01E02.1080p.[DE+EN].mkv"))
    }

    @Test
    fun doesNotInventAudioFromTitlesSubtitlesOrMulti() {
        for (release in listOf("The.English.Patient.1996.1080p.mkv", "French.Kiss.1995.720p.mkv",
            "Movie.2025.1080p.German.Subs.mkv", "Movie.2025.1080p.EnglishSubbed.mkv",
            "Movie.2025.1080p.MULTI.mkv", "Movie.2025.VOSTFR.1080p.mkv",
            "Movie.2025.1080p\nSubtitles: DE, EN")) {
            assertEquals(emptyList(), SourceLanguages.extract(release), release)
        }
        assertEquals(listOf("JA"), SourceLanguages.extract("Movie.2025.1080p\nAudio: JPN\nSubtitles: German, English"))
    }

    @Test
    fun languageSelectionIncludesMultilanguageMatchesAndKeepsUnknownExplicit() {
        val bilingual = source("Movie.2025.German.English.1080p.mkv")
        val french = source("Movie.2025.French.1080p.mkv")
        val unknown = source("Movie.2025.MULTI.1080p.mkv")
        assertEquals(listOf("English", "French", "German"), SourceLanguages.options(listOf(bilingual, french, unknown)).map { it.name })
        assertTrue(SourceLanguages.matches(bilingual, "DE"))
        assertTrue(SourceLanguages.matches(bilingual, "EN"))
        assertFalse(SourceLanguages.matches(french, "DE"))
        assertFalse(SourceLanguages.matches(unknown, "EN"))
        assertTrue(SourceLanguages.matches(unknown, "UNKNOWN"))
        assertTrue(SourceLanguages.matches(unknown, ""))
        assertEquals("Multiple languages · unspecified", SourceLanguages.label(unknown))
        assertEquals("🇩🇪 German · 🇬🇧 English", SourceLanguages.label(bilingual))
        assertEquals(listOf("EN", "DE"), SourceLanguages.codes(source("Example").copy(languages = listOf("en-US", "deu", "ENG"))))
    }

    private fun source(title: String) = ParsedStream(addonName = "Provider", quality = "1080p", title = title)

    @Test fun subtitlesQualifyOnlyWhenExplicitlyReportedAndFallbackIsEnabled() {
        val korean = StreamParser.parse(StremioStream(title = "Movie.2025.Korean.1080p\nSubtitles: German, English"))
        assertEquals(listOf("KO"), korean.languages)
        assertEquals(listOf("DE", "EN"), korean.subtitleLanguages)
        assertFalse(SourceLanguages.matchesPreference(korean, "DE", false))
        assertTrue(SourceLanguages.matchesPreference(korean, "DE", true))
        assertFalse(SourceLanguages.matchesPreference(korean, "FR", true))
        assertFalse(SourceLanguages.matchesPreference(source("Movie.2025.MULTI.1080p"), "DE", true))
        assertEquals(listOf("DE"), SourceLanguages.extractSubtitles("Movie.2025.1080p.German.Subs.mkv"))
        assertEquals(listOf("FR"), SourceLanguages.extractSubtitles("Movie.2025.VOSTFR.1080p.mkv"))
    }
}
