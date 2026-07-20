package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaFavoriteKeyTest {

    @Test
    fun favoriteMediaKey_usesBackendWireMediaType() {
        val item = MediaItem(
            id = "123",
            tmdbId = 123,
            type = MediaType.MOVIE,
            title = "Example",
        )

        assertEquals("movie:123", item.favoriteMediaKey())
    }

    @Test
    fun matchesMediaItemFavorite_acceptsLegacyEnumKey() {
        val item = MediaItem(
            id = "123",
            tmdbId = 123,
            type = MediaType.MOVIE,
            title = "Example",
        )
        val favorite = MediaFavorite(
            mediaKey = "MOVIE:123",
            mediaType = MediaType.MOVIE,
            tmdbId = 123,
            title = "Example",
        )

        assertTrue(favorite.matchesMediaItemFavorite(item))
    }

    @Test
    fun favoriteMediaKey_normalizesTmdbPrefixedIds() {
        val item = MediaItem(
            id = "tmdb:movie:123",
            tmdbId = null,
            type = MediaType.MOVIE,
            title = "Example",
        )

        assertEquals("movie:123", item.favoriteMediaKey())
        assertEquals(123, item.toMediaFavorite().tmdbId)
    }

    @Test
    fun matchesMediaItemFavorite_acceptsTmdbPrefixedBackendKey() {
        val item = MediaItem(
            id = "123",
            tmdbId = 123,
            type = MediaType.MOVIE,
            title = "Example",
        )
        val favorite = MediaFavorite(
            mediaKey = "tmdb:movie:123",
            mediaType = MediaType.MOVIE,
            tmdbId = null,
            title = "Example",
        )

        assertTrue(favorite.matchesMediaItemFavorite(item))
    }
}
