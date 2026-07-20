package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.EnrichedChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvGuideSourceResolverTest {
    @Test
    fun category_selection_uses_category_channels_without_viewport_cap() {
        val categoryChannels = (1..12).map { index ->
            EnrichedChannel(channel("p1", "GENERAL HD", "General $index", "general.$index"))
        }
        val state = ChannelsUiState(
            selectedPlaylistId = "p1",
            selectedGroup = "GENERAL HD",
            categoryChannels = categoryChannels,
            guideChannels = categoryChannels.take(4),
        )

        val resolved = LiveTvGuideSourceResolver.resolve(state)

        assertEquals(12, resolved.size)
        assertEquals("General 12", resolved.last().channel.name)
    }

    @Test
    fun search_selection_uses_search_results_and_enriches_from_loaded_pool() {
        val zdf = channel("p1", "GENERAL HD", "ZDF HD", "zdf.hd")
        val ard = channel("p1", "GENERAL HD", "ARD HD", "ard.hd")
        val state = ChannelsUiState(
            selectedPlaylistId = "p1",
            channels = listOf(EnrichedChannel(zdf), EnrichedChannel(ard)),
            searchQuery = "zdf",
            searchResults = listOf(zdf),
            guideChannels = listOf(EnrichedChannel(ard)),
        )

        val resolved = LiveTvGuideSourceResolver.resolve(state)

        assertEquals(listOf("ZDF HD"), resolved.map { it.channel.name })
    }

    @Test
    fun favorites_selection_uses_favorite_channels() {
        val favorite = channel("p1", "Sports", "Sports One", "sports.one")
        val other = channel("p1", "Sports", "Sports Two", "sports.two")
        val state = ChannelsUiState(
            selectedPlaylistId = "p1",
            selectedSubTab = ChannelsSubTab.FAVOURITES,
            channels = listOf(EnrichedChannel(favorite), EnrichedChannel(other)),
            favorites = listOf(favorite),
        )

        val resolved = LiveTvGuideSourceResolver.resolve(state)

        assertEquals(1, resolved.size)
        assertEquals("Sports One", resolved.first().channel.name)
    }

    @Test
    fun vod_channels_are_not_included_in_live_guide() {
        val live = channel("p1", "Live", "News One", "news.one")
        val vodByType = channel(
            "p1",
            "VOD: Movies",
            "Movie One",
            "movie.one",
            url = "https://example.com/movie/1.ts",
            contentType = ChannelContentType.VOD_MOVIE,
        )
        val vodByUrl = channel(
            "p1",
            "Live",
            "Archive Clip",
            "archive.clip",
            url = "https://example.com/vod/archive.mp4",
            contentType = ChannelContentType.UNKNOWN,
        )
        val state = ChannelsUiState(
            selectedPlaylistId = "p1",
            channels = listOf(EnrichedChannel(live), EnrichedChannel(vodByType), EnrichedChannel(vodByUrl)),
        )

        val resolved = LiveTvGuideSourceResolver.resolve(state)

        assertEquals(listOf("News One"), resolved.map { it.channel.name })
        assertTrue(resolved.all { it.channel.contentType != ChannelContentType.VOD_MOVIE })
        assertFalse(resolved.any { it.channel.url.endsWith(".mp4") })
    }

    private fun channel(
        playlistId: String,
        group: String,
        name: String,
        tvgId: String,
        url: String = "https://example.com/live/$tvgId.m3u8",
        contentType: ChannelContentType = ChannelContentType.LIVE,
    ) = Channel(
        name = name,
        url = url,
        tvgId = tvgId,
        groupTitle = group,
        playlistId = playlistId,
        contentType = contentType,
    )
}
