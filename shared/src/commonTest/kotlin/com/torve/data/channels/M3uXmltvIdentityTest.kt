package com.torve.data.channels

import kotlin.test.Test
import kotlin.test.assertEquals

class M3uXmltvIdentityTest {
    @Test
    fun m3u4uMetadataKeepsExternalEpgIdSeparateFromDisplayName() {
        val playlist = M3uParser().parse(
            content = """
                #EXTM3U url-tvg="https://example.invalid/xml/guide"
                #EXTINF:-1 tvg-id="bundesliga.1" tvg-name="Sky Sport Bundesliga HD" tvg-logo="https://example.invalid/logo.png" group-title="DE Sports",WOW: SKY SPORT BUNDESLIGA HD
                https://example.invalid/stream
            """.trimIndent(),
            playlistId = "playlist",
        )

        val channel = playlist.channels.single()
        assertEquals("bundesliga.1", channel.tvgId)
        assertEquals("Sky Sport Bundesliga HD", channel.tvgName)
        assertEquals("WOW: SKY SPORT BUNDESLIGA HD", channel.name)
        assertEquals("https://example.invalid/logo.png", channel.tvgLogo)
        assertEquals("DE Sports", channel.groupTitle)
        assertEquals("https://example.invalid/stream", channel.url)
    }

    @Test
    fun xmltvTimestampHonoursExplicitOffsetAndPartialPrecision() {
        val parser = EpgParser()
        assertEquals(1_787_491_800_000L, parser.parseXmltvTimestamp("20260823153000 +0200"))
        assertEquals(1_787_491_800_000L, parser.parseXmltvTimestamp("20260823153000 +02:00"))
        assertEquals(1_787_443_200_000L, parser.parseXmltvTimestamp("20260823 +0000"))
    }
}
