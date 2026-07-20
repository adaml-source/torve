package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.channelIdentityCandidates
import com.torve.domain.model.stableChannelId

/**
 * Resolves the channel rows that the EPG should display from the current IPTV
 * browsing state. This keeps the guide synchronized with playlist/category,
 * search, and favorites without relying on the previously-rendered viewport.
 */
object LiveTvGuideSourceResolver {
    fun resolve(state: ChannelsUiState): List<EnrichedChannel> {
        val pool = (state.categoryChannels + state.channels)
            .filter { isGuideChannel(it.channel) }
            .distinctBy { stableChannelId(it.channel) }

        val selected = when {
            state.selectedSubTab == ChannelsSubTab.FAVOURITES ||
                state.activeFilter == ChannelsFilterType.FAVORITES -> {
                enrichFavorites(state.favorites, pool)
            }

            state.searchQuery.trim().length >= 2 -> {
                val searchResults = enrichChannels(state.searchResults, pool)
                searchResults.ifEmpty {
                    val needle = state.searchQuery.trim().lowercase()
                    pool.filter { it.channel.name.lowercase().contains(needle) }
                }
            }

            state.selectedGroup != null && state.categoryChannels.isNotEmpty() -> {
                state.categoryChannels
            }

            state.channels.isNotEmpty() -> {
                state.channels
            }

            else -> {
                state.categoryChannels
            }
        }

        return selected
            .filter { isGuideChannel(it.channel) }
            .distinctBy { stableChannelId(it.channel) }
    }

    private fun enrichFavorites(
        favorites: List<Channel>,
        pool: List<EnrichedChannel>,
    ): List<EnrichedChannel> {
        if (favorites.isEmpty()) return emptyList()
        val byIdentity = buildMap<String, EnrichedChannel> {
            pool.forEach { enriched ->
                channelIdentityCandidates(enriched.channel).forEach { id -> put(id, enriched) }
            }
        }
        return favorites.map { channel ->
            channelIdentityCandidates(channel).firstNotNullOfOrNull(byIdentity::get)
                ?: EnrichedChannel(channel = channel)
        }
    }

    private fun enrichChannels(
        channels: List<Channel>,
        pool: List<EnrichedChannel>,
    ): List<EnrichedChannel> {
        if (channels.isEmpty()) return emptyList()
        val byIdentity = buildMap<String, EnrichedChannel> {
            pool.forEach { enriched ->
                channelIdentityCandidates(enriched.channel).forEach { id -> put(id, enriched) }
            }
        }
        return channels.map { channel ->
            channelIdentityCandidates(channel).firstNotNullOfOrNull(byIdentity::get)
                ?: EnrichedChannel(channel = channel)
        }
    }

    private fun isGuideChannel(channel: Channel): Boolean {
        return (channel.contentType == ChannelContentType.LIVE ||
            channel.contentType == ChannelContentType.UNKNOWN) &&
            !isVodCategoryName(channel.groupTitle.orEmpty()) &&
            !isVodUrl(channel.url)
    }

    private fun isVodCategoryName(name: String): Boolean {
        return name.startsWith("VOD:", ignoreCase = true) ||
            name.equals("VOD", ignoreCase = true)
    }

    private fun isVodUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.contains("/movie/") ||
            path.contains("/series/") ||
            path.contains("/vod/") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mp4") ||
            path.endsWith(".avi") ||
            path.endsWith(".m4v") ||
            path.endsWith(".mov") ||
            path.endsWith(".wmv") ||
            path.endsWith(".webm")
    }
}
