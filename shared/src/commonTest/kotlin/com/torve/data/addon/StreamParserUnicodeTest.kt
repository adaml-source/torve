package com.torve.data.addon

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamParserUnicodeTest {
    @Test
    fun separatorEscapedReleaseTitlesAreRecoveredWithoutChangingPlayableUrls() {
        val url = "https://stream.example/Bayern.M.C3.BCnchen.mkv"
        val stream = StreamParser.parse(StremioStream(
            name = "Provider\n1080p",
            title = "Bayern.M.C3.BCnchen.vs.Fenerbah.C3.A7e\n💾 8.5 GB",
            url = url,
        ))
        assertEquals("Bayern.München.vs.Fenerbahçe", stream.title)
        assertEquals("8.5 GB", stream.size)
        assertEquals("1080p", stream.quality)
        assertEquals(url, stream.directUrl)
    }

    @Test
    fun displayTextIsDecodedWhilePlayableUrlsAndStreamMetadataStayValid() {
        val url = "https://stream.example/Bayern%20M%C3%BCnchen.mkv?token=a%2Bb"
        val stream = StreamParser.parse(StremioStream(
            name = "M%C3%BCnchen%20Addon\n1080p",
            title = "Bayern.M%C3%BCnchen.vs.Fenerbah&ccedil;e\n💾 8.5 GB\n🗣️ DE, EN\n⚙️ S&atilde;o Paulo",
            url = url,
        ))
        assertEquals("München Addon", stream.addonName)
        assertEquals("Bayern.München.vs.Fenerbahçe", stream.title)
        assertEquals("São Paulo", stream.source)
        assertEquals("8.5 GB", stream.size)
        assertEquals(listOf("DE", "EN"), stream.languages)
        assertEquals("1080p", stream.quality)
        assertEquals(url, stream.directUrl)
    }

    @Test
    fun filenameFallbackHandlesEncodedNamesAndPreservesLiteralPlusSigns() {
        val stream = StreamParser.parse(StremioStream(
            behaviorHints = StemioBehaviorHints(filename = "%E6%9D%B1%E4%BA%AC+A%2BB.2160p.mkv"),
        ))
        assertEquals("東京+A+B.2160p.mkv", stream.title)
        assertEquals("4K", stream.quality)
    }
}
