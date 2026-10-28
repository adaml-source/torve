package com.torve.presentation.apple

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.repository.VodCategoryTypeCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleVodCatalogPolicyTest {
    @Test
    fun mixedProviderGroupsKeepMoviesAndShowsSeparateAndRetainAllCounts() {
        val values = listOf(
            VodCategoryTypeCount("VOD: Germany", ChannelContentType.VOD_MOVIE, 250),
            VodCategoryTypeCount("VOD: Germany", ChannelContentType.VOD_SERIES, 42),
            VodCategoryTypeCount("VOD: Germany", ChannelContentType.VOD_MOVIE, 8),
            VodCategoryTypeCount("News", ChannelContentType.LIVE, 1000),
        )
        val movies = AppleVodCatalogPolicy.categories(values, ChannelContentType.VOD_MOVIE)
        val shows = AppleVodCatalogPolicy.categories(values, ChannelContentType.VOD_SERIES)
        assertEquals(listOf("VOD: Germany"), movies.map { it.name })
        assertEquals(258, movies.single().channelCount)
        assertEquals(42, shows.single().channelCount)
    }

    @Test
    fun seriesRequiresARealResolvedEpisodeBeforePlayback() {
        val series = Channel("Show", "https://provider.test/series/user/pass/123.mp4",
            contentType = ChannelContentType.VOD_SERIES, kodiProps = mapOf("vod_series_id" to "123"))
        assertFalse(AppleVodCatalogPolicy.isPlayable(series))
        assertTrue(AppleVodCatalogPolicy.isPlayable(series.copy(url = "https://provider.test/series/user/pass/456.mkv",
            kodiProps = series.kodiProps + ("vod_episode_id" to "456"))))
        assertTrue(AppleVodCatalogPolicy.isPlayable(series.copy(contentType = ChannelContentType.VOD_MOVIE)))
        assertFalse(AppleVodCatalogPolicy.isPlayable(series.copy(contentType = ChannelContentType.LIVE)))
    }
}
