package com.torve.desktop.adult

import kotlin.test.Test
import kotlin.test.assertEquals

class NzbUnicodeTitleParserTest {
    @Test
    fun cachedMovieReleaseNamesDecodeBeforeCatalogMatching() {
        val parsed = NzbReleaseTitleParser.parse("M%C3%BCnchen.&amp;.&Lstrok;&oacute;d&zacute;.2024.1080p.WEB-GRP")
        assertEquals("München & Łódź", parsed?.title)
        assertEquals(2024, parsed?.year)
    }

    @Test
    fun cachedSeriesNamesKeepUnicodeAndLiteralPlusSigns() {
        val parsed = NzbTvReleaseTitleParser.parse("%E6%9D%B1%E4%BA%AC+A%2BB.S01E02.1080p")
        assertEquals("東京+A+B", parsed?.showTitle)
        assertEquals(1, parsed?.seasonNumber)
        assertEquals(2, parsed?.episodeNumber)
    }
}
