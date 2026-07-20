package com.torve.data.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemotePlaylistDtoTest {
    @Test
    fun isXtreamPlaylist_matches_explicit_xtream_type() {
        val playlist = RemotePlaylistDto(
            id = "playlist_1",
            playlistId = "playlist_1",
            name = "8k",
            playlistType = "xtream",
            server = "https://example.com",
        )

        assertTrue(playlist.isXtreamPlaylist())
    }

    @Test
    fun isXtreamPlaylist_matches_xtream_id_prefix_even_if_type_is_missing() {
        val playlist = RemotePlaylistDto(
            id = "xtream_1774196816509",
            name = "8k",
            playlistType = "",
            server = "https://example.com",
        )

        assertTrue(playlist.isXtreamPlaylist())
    }

    @Test
    fun isXtreamPlaylist_does_not_match_regular_m3u_playlist() {
        val playlist = RemotePlaylistDto(
            id = "playlist_2",
            playlistId = "playlist_2",
            name = "News",
            playlistType = "m3u",
            url = "https://example.com/list.m3u",
        )

        assertFalse(playlist.isXtreamPlaylist())
    }

    @Test
    fun playlistCredentialRetryDelayMs_prefers_retry_after_header() {
        assertEquals(2_000L, playlistCredentialRetryDelayMs(retryAfterHeader = "2", attempt = 0))
    }

    @Test
    fun playlistCredentialRetryDelayMs_falls_back_to_incremental_backoff() {
        assertEquals(750L, playlistCredentialRetryDelayMs(retryAfterHeader = null, attempt = 0))
        assertEquals(1_500L, playlistCredentialRetryDelayMs(retryAfterHeader = null, attempt = 1))
    }
}
