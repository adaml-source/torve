package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaIdParsersTest {

    @Test
    fun extractTmdbId_supportsLegacyAndPrefixedFormats() {
        assertEquals(12345, "12345".extractTmdbIdOrNull())
        assertEquals(12345, "movie_12345".extractTmdbIdOrNull())
        assertEquals(12345, "tmdb:12345".extractTmdbIdOrNull())
        assertEquals(12345, "series:tmdb:12345".extractTmdbIdOrNull())
        assertEquals(12345, "movie-tmdb:12345".extractTmdbIdOrNull())
    }

    @Test
    fun extractTmdbId_rejectsUnsupportedFormats() {
        assertNull("".extractTmdbIdOrNull())
        assertNull("tt12345".extractTmdbIdOrNull())
        assertNull("series:imdb:tt12345".extractTmdbIdOrNull())
    }
}
