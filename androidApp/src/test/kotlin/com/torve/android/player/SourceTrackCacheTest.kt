package com.torve.android.player

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.SourceTrackLanguagePolicy
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SourceTrackCacheTest {
    private val stream = ParsedStream("Fixture", "1080p", "Film.2025.German", directUrl = "https://media.example/file?token=private", languages = listOf("DE"))

    @Test fun verifiedAudioReplacesFilenameHintsAndSubtitlesRemainSeparateAfterReload() {
        val file = Files.createTempDirectory("torve-tracks").resolve("tracks.json").toFile()
        try {
            val key = SourceTrackCache.key(stream, "user")!!
            SourceTrackCache(file).save(listOf(key), listOf("eng"), listOf("deu"))
            val reloaded = SourceTrackCache(file).applying(stream, "user")
            assertEquals(listOf("EN"), reloaded.languages)
            assertEquals(listOf("DE"), reloaded.subtitleLanguages)
            assertFalse(SourceTrackLanguagePolicy.matches(reloaded, "DE", false, true))
            assertTrue(SourceTrackLanguagePolicy.matches(reloaded, "DE", true, true))
            assertFalse(file.readText().contains("private"))
            assertFalse(file.readText().contains("media.example"))
            assertFalse(file.readText().contains("user"))
            assertFalse(SourceTrackCache(file).applying(stream, "other-user").trackLanguagesVerified)
        } finally { file.delete(); file.parentFile.delete() }
    }
    @Test fun cacheIdentitySeparatesAccountsEpisodesAndCompleteUrls() {
        val pack = stream.copy(infoHash = "abcdef", fileIdx = 1)
        assertNotEquals(SourceTrackCache.key(pack, "u"), SourceTrackCache.key(pack.copy(fileIdx = 2), "u"))
        assertNotEquals(SourceTrackCache.key(pack, "u"), SourceTrackCache.key(pack, "u::profile:child"))
        assertNotEquals(SourceTrackCache.key(stream, "u"), SourceTrackCache.key(stream.copy(directUrl = stream.directUrl + "2"), "u"))
    }
    @Test fun resolvedPlaybackUpdatesOriginalSourceAndExpires() {
        val file = Files.createTempDirectory("torve-tracks").resolve("tracks.json").toFile()
        var now = 1000L
        try {
            val cache = SourceTrackCache(file) { now }
            cache.associate(stream, "https://resolved.example/file", "u")
            cache.recordPlayback("https://resolved.example/file", "u", listOf("fra"), emptyList())
            assertEquals(listOf("FR"), cache.applying(stream, "u").languages)
            now += SourceTrackCache.LIFETIME_MS
            assertFalse(cache.applying(stream, "u").trackLanguagesVerified)
        } finally { file.delete(); file.parentFile.delete() }
    }
    @Test fun uncheckedReadableSourcesCannotPassStrictAudioFilterButLazyUsenetHintsCan() {
        assertFalse(SourceTrackLanguagePolicy.matches(stream, "DE", false, readable = true))
        assertTrue(SourceTrackLanguagePolicy.matches(stream, "DE", false, readable = false))
        assertTrue(SourceTrackLanguagePolicy.matches(stream, "", false, readable = true))
        val verifiedUnknown = stream.copy(languages = emptyList(), trackLanguagesVerified = true)
        assertFalse(SourceTrackLanguagePolicy.matches(verifiedUnknown, "DE", false, readable = true))
    }

    @Test fun verifiedAudioMigratesToPersistentStorageAndSurvivesCacheCleanup() {
        val directory = Files.createTempDirectory("torve-track-migration").toFile()
        val oldFile = java.io.File(directory, "cached.json")
        val persistentFile = java.io.File(directory, "persistent.json")
        try {
            SourceTrackCache(oldFile).save(listOf(SourceTrackCache.key(stream, "u")!!), listOf("eng"), listOf("deu"))
            assertTrue(SourceTrackCache(persistentFile, fallbackFile = oldFile).applying(stream, "u").trackLanguagesVerified)
            oldFile.delete()
            val restored = SourceTrackCache(persistentFile).applying(stream, "u")
            assertEquals(listOf("EN"), restored.languages)
            assertEquals(listOf("DE"), restored.subtitleLanguages)
        } finally { oldFile.delete(); persistentFile.delete(); directory.delete() }
    }

    @Test fun partialPlaybackNotificationCannotEraseVerifiedAudio() {
        val directory = Files.createTempDirectory("torve-track-partial").toFile()
        val file = java.io.File(directory, "tracks.json")
        try {
            val cache = SourceTrackCache(file)
            cache.associate(stream, "https://resolved.example/file", "u")
            cache.recordPlayback("https://resolved.example/file", "u", listOf("eng"), listOf("deu"))
            cache.recordPlayback("https://resolved.example/file", "u", emptyList(), emptyList())
            val restored = SourceTrackCache(file).applying(stream, "u")
            assertEquals(listOf("EN"), restored.languages)
            assertEquals(listOf("DE"), restored.subtitleLanguages)
        } finally { file.delete(); directory.delete() }
    }

    @Test fun playbackTracksRemainAttachedToTheSelectedSourceOnly() {
        val directory = Files.createTempDirectory("torve-track-sources").toFile()
        val file = java.io.File(directory, "tracks.json")
        try {
            val cache = SourceTrackCache(file)
            val other = stream.copy(directUrl = "https://media.example/other-file")
            cache.associate(stream, "https://resolved.example/selected", "u")
            cache.associate(other, "https://resolved.example/other", "u")
            cache.recordPlayback("https://resolved.example/selected", "u", listOf("eng", "fra"), emptyList())
            assertEquals(listOf("EN", "FR"), SourceTrackCache(file).applying(stream, "u").languages)
            assertFalse(SourceTrackCache(file).applying(other, "u").trackLanguagesVerified)
        } finally { file.delete(); directory.delete() }
    }
}
