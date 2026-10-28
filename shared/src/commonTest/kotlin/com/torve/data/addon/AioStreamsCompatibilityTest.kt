package com.torve.data.addon

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AioStreamsCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun descriptionMetadataIsReadWithoutReplacingFilenameOrPlaybackUrl() {
        val raw = json.decodeFromString<StremioStream>("""{
          "name":"[TB⏳] Newznab 2160p",
          "description":"🎥 WEB-DL 🎞️ HEVC\n📺 HDR | DV\n📦 11.8 GB 🔍 Example Indexer\nAudio: English | German\nSubtitles: French",
          "url":"https://addon.example/play/opaque-token",
          "behaviorHints":{"filename":"Example.2026.S01E01.2160p.WEB-DL.H265","videoSize":11800000000}
        }""")
        val result = StreamParser.parse(raw, "AIOStreams", "https://addon.example")
        assertEquals("Example.2026.S01E01.2160p.WEB-DL.H265", result.title)
        assertEquals("https://addon.example/play/opaque-token", result.directUrl)
        assertEquals("4K", result.quality)
        assertEquals("11.8 GB", result.size)
        assertEquals("HEVC", result.codec)
        assertEquals("DV", result.hdr)
        assertEquals("Example Indexer", result.source)
        assertEquals(listOf("EN", "DE"), result.languages)
        assertEquals(listOf("FR"), result.subtitleLanguages)
        assertFalse(result.isCached)
        assertTrue(result.isUsenetStream())
    }

    @Test
    fun cachedLightningBadgeIsRecognisedButHourglassAndUnscopedLightningAreNot() {
        assertTrue(StreamParser.parse(StremioStream(name = "[TB⚡] Provider 1080p")).isCached)
        assertTrue(StreamParser.parse(StremioStream(name = "[RD⚡️] Provider 1080p")).isCached)
        assertFalse(StreamParser.parse(StremioStream(name = "[TB⏳] Provider 1080p")).isCached)
        assertFalse(StreamParser.parse(StremioStream(name = "⚡ Fast Provider")).isCached)
    }

    @Test
    fun explicitServiceCacheStatusOverridesTextAndDoesNotBorrowOtherServiceStatus() {
        val raw = StremioStream(name = "[TB+] Provider", behaviorHints = StemioBehaviorHints(service = "torbox", cached = false))
        assertFalse(StreamParser.parse(raw).isCached)
        assertTrue(StreamParser.parse(raw.copy(name = "Provider", behaviorHints = raw.behaviorHints?.copy(cached = true))).isCached)
        assertFalse(StreamParser.parse(raw.copy(name = "Provider", behaviorHints = StemioBehaviorHints(cached = true))).isCached)
    }

    @Test
    fun descriptionOnlyAndStructuredHintsRemainUsable() {
        val result = StreamParser.parse(StremioStream(name = "Provider 720p", description = "Example episode\nAudio: German",
            behaviorHints = StemioBehaviorHints(videoSize = 750000000, indexer = "Indexer", seeders = 42)))
        assertEquals("Example episode", result.title)
        assertEquals("720p", result.quality)
        assertEquals("750 MB", result.size)
        assertEquals("Indexer", result.source)
        assertEquals(42, result.seeds)
        assertEquals(listOf("DE"), result.languages)
    }
}
