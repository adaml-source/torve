package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveTvChannelLogoResolverTest {
    @Test
    fun `playlist logo url is used first when it is safe`() {
        val channel = Channel(
            name = "DE: ZDF RAW amz",
            url = "https://stream.example/zdf.m3u8",
            tvgLogo = "https://images.example/zdf.png",
            contentType = ChannelContentType.LIVE,
        )

        val logo = LiveTvChannelLogoResolver.resolveLogo(
            channel = channel,
            epgChannel = EpgChannel(id = "zdf", displayName = "ZDF", iconUrl = "https://epg.example/zdf.svg"),
        )

        assertEquals("https://images.example/zdf.png", logo.url)
        assertEquals(ChannelLogoSource.PlaylistTvgLogo, logo.source)
        assertEquals(LogoConfidence.High, logo.confidence)
        assertEquals("ZDF", logo.fallbackText)
    }

    @Test
    fun `epg icon is used when playlist logo is missing`() {
        val logo = LiveTvChannelLogoResolver.resolveLogo(
            channelName = "DE: DAS ERSTE HD (LOW BIT)",
            epgIconUrl = "https://epg.example/daserste.png",
        )

        assertEquals("https://epg.example/daserste.png", logo.url)
        assertEquals(ChannelLogoSource.EpgIcon, logo.source)
        assertEquals("1", logo.fallbackText)
    }

    @Test
    fun `invalid and local logo urls fall back safely`() {
        val invalidSchemes = listOf(
            "file:///tmp/zdf.png",
            "data:image/png;base64,abc",
            "javascript:alert(1)",
            "http://localhost/zdf.png",
            "https://127.0.0.1/zdf.png",
            "https://192.168.1.4/zdf.png",
            "https://user:password@example.com/zdf.png",
        )

        invalidSchemes.forEach { url ->
            assertNull(LiveTvChannelLogoResolver.validRemoteLogoUrl(url), url)
        }
    }

    @Test
    fun `channel names normalize without quality suffixes or country prefixes`() {
        assertEquals("zdf", LiveTvChannelLogoResolver.normalizeChannelNameForLogo("DE: ZDF RAW amz"))
        assertEquals("das erste", LiveTvChannelLogoResolver.normalizeChannelNameForLogo("DE: DAS ERSTE HD (LOW BIT)"))
        assertEquals("rtl", LiveTvChannelLogoResolver.normalizeChannelNameForLogo("DE: RTL UHD 3840P"))
    }

    @Test
    fun `credential query keys are stripped from accepted logo urls`() {
        val sanitized = LiveTvChannelLogoResolver.validRemoteLogoUrl(
            "https://images.example/zdf.png?token=secret&w=256&signature=abc#mark",
        )

        assertEquals("https://images.example/zdf.png?w=256#mark", sanitized)
    }

    @Test
    fun `unknown channel does not map to an unrelated curated logo`() {
        val logo = LiveTvChannelLogoResolver.resolveLogo(channelName = "DE: Some Unknown Channel RAW")

        assertNull(logo.url)
        assertEquals(ChannelLogoSource.Fallback, logo.source)
        assertEquals("SU", logo.fallbackText)
    }
}
