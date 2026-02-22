package com.streamvault.addon

import com.streamvault.data.addon.StemioBehaviorHints
import com.streamvault.data.addon.StremioStream
import com.streamvault.data.addon.StreamParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamParserTest {

    @Test
    fun parse_torrentioStream() {
        val stream = StremioStream(
            name = "Torrentio\n4K",
            title = "Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.x265\n💾 15.2 GB\n👤 150\n⚙️ YTS",
            infoHash = "abc123def456",
            fileIdx = 0,
        )
        val parsed = StreamParser.parse(stream)
        assertEquals("Torrentio", parsed.addonName)
        assertEquals("4K", parsed.quality)
        assertEquals("Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.x265", parsed.title)
        assertEquals("abc123def456", parsed.infoHash)
        assertEquals(0, parsed.fileIdx)
        assertEquals("15.2 GB", parsed.size)
        assertEquals(150, parsed.seeds)
        assertEquals("HEVC", parsed.codec)
        assertEquals("YTS", parsed.source)
    }

    @Test
    fun parse_streamWithDirectUrl() {
        val stream = StremioStream(
            name = "Addon\n1080p",
            title = "Movie.1080p.BluRay",
            url = "https://example.com/video.mp4",
        )
        val parsed = StreamParser.parse(stream)
        assertEquals("Addon", parsed.addonName)
        assertEquals("1080p", parsed.quality)
        assertEquals("https://example.com/video.mp4", parsed.directUrl)
        assertNull(parsed.infoHash)
    }

    @Test
    fun parse_fallbackAddonName() {
        val stream = StremioStream(
            title = "Some movie",
        )
        val parsed = StreamParser.parse(stream, "MyAddon")
        assertEquals("MyAddon", parsed.addonName)
    }

    @Test
    fun parse_behaviorHintsFilename() {
        val stream = StremioStream(
            behaviorHints = StemioBehaviorHints(filename = "movie.mkv"),
        )
        val parsed = StreamParser.parse(stream)
        assertEquals("movie.mkv", parsed.title)
    }

    @Test
    fun extractQuality_4K() {
        assertEquals("4K", StreamParser.extractQuality("2160p.WEB-DL"))
        assertEquals("4K", StreamParser.extractQuality("4K UHD"))
        assertEquals("4K", StreamParser.extractQuality("UHD Remux"))
    }

    @Test
    fun extractQuality_1080p() {
        assertEquals("1080p", StreamParser.extractQuality("1080p.BluRay"))
        assertEquals("1080p", StreamParser.extractQuality("1080"))
    }

    @Test
    fun extractQuality_720p() {
        assertEquals("720p", StreamParser.extractQuality("720p.HDTV"))
    }

    @Test
    fun extractQuality_480p() {
        assertEquals("480p", StreamParser.extractQuality("480p.WEB"))
    }

    @Test
    fun extractQuality_default() {
        assertEquals("1080p", StreamParser.extractQuality("unknown quality"))
    }

    @Test
    fun extractCodec_hevc() {
        assertEquals("HEVC", StreamParser.extractCodec("Movie.x265.DTS"))
        assertEquals("HEVC", StreamParser.extractCodec("HEVC.10bit"))
        assertEquals("HEVC", StreamParser.extractCodec("H.265"))
        assertEquals("HEVC", StreamParser.extractCodec("H265"))
    }

    @Test
    fun extractCodec_h264() {
        assertEquals("H.264", StreamParser.extractCodec("Movie.x264"))
        assertEquals("H.264", StreamParser.extractCodec("H.264.AAC"))
        assertEquals("H.264", StreamParser.extractCodec("AVC"))
    }

    @Test
    fun extractCodec_av1() {
        assertEquals("AV1", StreamParser.extractCodec("AV1.10bit"))
    }

    @Test
    fun extractCodec_unknown() {
        assertEquals("", StreamParser.extractCodec("Movie.DTS"))
    }

    @Test
    fun parse_sizeWithMB() {
        val stream = StremioStream(
            title = "Movie 💾 750 MB rest of info",
        )
        val parsed = StreamParser.parse(stream)
        assertEquals("750 MB", parsed.size)
    }

    @Test
    fun parse_noMetadata() {
        val stream = StremioStream(
            title = "Movie Title Only",
        )
        val parsed = StreamParser.parse(stream)
        assertNull(parsed.size)
        assertNull(parsed.seeds)
        assertEquals("", parsed.codec)
        assertNull(parsed.source)
    }
}
