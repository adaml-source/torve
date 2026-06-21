package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpgChannelKeyTest {

    @Test
    fun canonicalKey_usesPlaylistScopedTvgIdWhenPresent() {
        val channel = Channel(
            name = "News One",
            url = "https://example.com/live/news1",
            tvgId = "news.one",
            playlistId = "playlist-7",
        )

        val key = canonicalEpgChannelKey("playlist-7", channel)

        assertEquals("playlist-7::news.one", key)
    }

    @Test
    fun canonicalKey_fallsBackToNormalizedNameWhenTvgIdMissing() {
        val channel = Channel(
            name = "BBC One HD!",
            url = "https://example.com/live/bbc1",
            playlistId = "playlist-7",
        )

        val key = canonicalEpgChannelKey("playlist-7", channel)

        assertEquals("playlist-7::bbconehd", key)
    }

    @Test
    fun canonicalKey_overloadUsesChannelPlaylistId() {
        val channel = Channel(
            name = "Sky Sports",
            url = "https://example.com/live/sky-sports",
            playlistId = "playlist-9",
        )

        val key = canonicalEpgChannelKey(channel)

        assertEquals("playlist-9::skysports", key)
    }

    @Test
    fun canonicalKey_returnsNullWhenPlaylistIdBlank() {
        val channel = Channel(
            name = "Sky Sports",
            url = "https://example.com/live/sky-sports",
            playlistId = "",
        )

        val key = canonicalEpgChannelKey("", channel)

        assertNull(key)
    }
}
