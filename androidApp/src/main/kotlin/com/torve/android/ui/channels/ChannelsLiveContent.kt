package com.torve.android.ui.channels

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel
import com.torve.presentation.channels.ChannelsViewMode

@Composable
fun ChannelsLiveContent(
    categories: List<ChannelCategory>,
    expandedCategories: Set<String>,
    searchQuery: String,
    searchResults: List<Channel>,
    isLoading: Boolean,
    viewMode: ChannelsViewMode,
    onToggleCategory: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onToggleViewMode: () -> Unit,
    onFilterClick: () -> Unit,
    onChannelPlay: (Channel) -> Unit,
    onChannelFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    recentChannels: List<Channel> = emptyList(),
    favoriteChannels: List<Channel> = emptyList(),
) {
    if (isLoading && categories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Amber,
                strokeWidth = 3.dp,
            )
        }
        return
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(modifier = modifier.fillMaxSize()) {
        ChannelsSearchActions(
            searchQuery = searchQuery,
            viewMode = viewMode,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onToggleViewMode = onToggleViewMode,
            onFilterClick = onFilterClick,
        )
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Amber,
                trackColor = Torve.colors.inputBackground,
            )
        }

        if (searchQuery.length >= 2) {
            ChannelsSearchResults(
                channels = searchResults.filter { it.isDisplayableIptvChannel() },
                onChannelPlay = onChannelPlay,
                onChannelFavorite = onChannelFavorite,
                modifier = Modifier.weight(1f),
            )
        } else if (isLandscape) {
            ChannelsLandscapeBrowser(
                categories = categories,
                expandedCategories = expandedCategories,
                recentChannels = recentChannels,
                favoriteChannels = favoriteChannels,
                onToggleCategory = onToggleCategory,
                onChannelPlay = onChannelPlay,
                onChannelFavorite = onChannelFavorite,
                modifier = Modifier.weight(1f),
            )
        } else {
            ChannelsPortraitBrowser(
                categories = categories,
                expandedCategories = expandedCategories,
                recentChannels = recentChannels,
                favoriteChannels = favoriteChannels,
                viewMode = viewMode,
                onToggleCategory = onToggleCategory,
                onChannelPlay = onChannelPlay,
                onChannelFavorite = onChannelFavorite,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChannelsSearchActions(
    searchQuery: String,
    viewMode: ChannelsViewMode,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onToggleViewMode: () -> Unit,
    onFilterClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Torve.colors.inputBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Torve.colors.textPrimary),
                cursorBrush = SolidColor(Amber),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search channels...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Torve.colors.textHint,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = onClearSearch,
                    modifier = Modifier.align(Alignment.CenterEnd).size(20.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_clear),
                        tint = Torve.colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Torve.colors.textTertiary,
                    modifier = Modifier.align(Alignment.CenterEnd).size(16.dp),
                )
            }
        }
        IconButton(onClick = onToggleViewMode, modifier = Modifier.size(36.dp)) {
            Icon(
                if (viewMode == ChannelsViewMode.LIST) {
                    Icons.Rounded.GridView
                } else {
                    Icons.AutoMirrored.Rounded.ViewList
                },
                contentDescription = "Toggle channel layout",
                tint = Torve.colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onFilterClick, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.FilterList,
                contentDescription = "Filter and sort channels",
                tint = Torve.colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ChannelsSearchResults(
    channels: List<Channel>,
    onChannelPlay: (Channel) -> Unit,
    onChannelFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (channels.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No channels found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textTertiary,
                    )
                }
            }
        } else {
            items(channels, key = { "search_${it.playlistId}_${it.url}" }) { channel ->
                ChannelRow(
                    enriched = EnrichedChannel(channel),
                    onPlay = { onChannelPlay(channel) },
                    onFavorite = { onChannelFavorite(channel) },
                )
            }
        }
    }
}

@Composable
private fun ChannelsPortraitBrowser(
    categories: List<ChannelCategory>,
    expandedCategories: Set<String>,
    recentChannels: List<Channel>,
    favoriteChannels: List<Channel>,
    viewMode: ChannelsViewMode,
    onToggleCategory: (String) -> Unit,
    onChannelPlay: (Channel) -> Unit,
    onChannelFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (recentChannels.isNotEmpty()) {
            item(key = "recent_channels") {
                QuickChannelRail("Recently watched", recentChannels, onChannelPlay, onChannelFavorite)
            }
        }
        if (favoriteChannels.isNotEmpty()) {
            item(key = "favorite_channels") {
                QuickChannelRail("Favourites", favoriteChannels, onChannelPlay, onChannelFavorite)
            }
        }
        categories.forEach { category ->
            val isExpanded = category.name in expandedCategories
            item(key = "header_${category.name}") {
                CategoryHeader(
                    name = category.name,
                    channelCount = category.channelCount,
                    qualityTags = category.qualityTags,
                    isExpanded = isExpanded,
                    onToggle = { onToggleCategory(category.name) },
                    countryCode = category.countryCode,
                )
                HorizontalDivider(
                    color = Torve.colors.border.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (isExpanded) {
                val visibleChannels = category.channels.filter { it.channel.isDisplayableIptvChannel() }
                if (viewMode == ChannelsViewMode.LIST) {
                    items(
                        visibleChannels,
                        key = { "ch_${category.name}_${it.channel.playlistId}_${it.channel.url}" },
                    ) { enriched ->
                        ChannelRow(
                            enriched = enriched,
                            onPlay = { onChannelPlay(enriched.channel) },
                            onFavorite = { onChannelFavorite(enriched.channel) },
                        )
                    }
                } else {
                    item(key = "row_${category.name}") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                visibleChannels,
                                key = { "grid_${category.name}_${it.channel.playlistId}_${it.channel.url}" },
                            ) { enriched ->
                                ChannelGridCard(
                                    enriched = enriched,
                                    onPlay = { onChannelPlay(enriched.channel) },
                                    onFavorite = { onChannelFavorite(enriched.channel) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelsLandscapeBrowser(
    categories: List<ChannelCategory>,
    expandedCategories: Set<String>,
    recentChannels: List<Channel>,
    favoriteChannels: List<Channel>,
    onToggleCategory: (String) -> Unit,
    onChannelPlay: (Channel) -> Unit,
    onChannelFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.width(300.dp).fillMaxSize()) {
            categories.forEach { category ->
                item(key = "wide_header_${category.name}") {
                    CategoryHeader(
                        name = category.name,
                        channelCount = category.channelCount,
                        qualityTags = category.qualityTags,
                        isExpanded = category.name in expandedCategories,
                        onToggle = { onToggleCategory(category.name) },
                        countryCode = category.countryCode,
                    )
                    HorizontalDivider(
                        color = Torve.colors.border.copy(alpha = 0.3f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
        VerticalDivider(color = Torve.colors.border.copy(alpha = 0.5f), thickness = 1.dp)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (recentChannels.isNotEmpty()) {
                item(key = "wide_recent") {
                    QuickChannelRail("Recently watched", recentChannels, onChannelPlay, onChannelFavorite)
                }
            }
            if (favoriteChannels.isNotEmpty()) {
                item(key = "wide_favorites") {
                    QuickChannelRail("Favourites", favoriteChannels, onChannelPlay, onChannelFavorite)
                }
            }
            val expanded = categories.filter { it.name in expandedCategories }
            if (expanded.isEmpty() && recentChannels.isEmpty() && favoriteChannels.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Choose a category",
                            style = MaterialTheme.typography.titleMedium,
                            color = Torve.colors.textTertiary,
                        )
                    }
                }
            }
            expanded.forEach { category ->
                item(key = "wide_title_${category.name}") {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                items(
                    category.channels.filter { it.channel.isDisplayableIptvChannel() },
                    key = { "wide_ch_${category.name}_${it.channel.playlistId}_${it.channel.url}" },
                ) { enriched ->
                    ChannelRow(
                        enriched = enriched,
                        onPlay = { onChannelPlay(enriched.channel) },
                        onFavorite = { onChannelFavorite(enriched.channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChannelRail(
    title: String,
    channels: List<Channel>,
    onChannelPlay: (Channel) -> Unit,
    onChannelFavorite: (Channel) -> Unit,
) {
    val visible = channels.filter { it.isDisplayableIptvChannel() }.take(20)
    if (visible.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = Torve.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { "quick_${title}_${it.playlistId}_${it.url}" }) { channel ->
                ChannelGridCard(
                    enriched = EnrichedChannel(channel),
                    onPlay = { onChannelPlay(channel) },
                    onFavorite = { onChannelFavorite(channel) },
                )
            }
        }
    }
}

private fun Channel.isDisplayableIptvChannel(): Boolean {
    val markerCount = name.count { it == '#' || it == '=' || it == '_' }
    return name.isNotBlank() && markerCount < 4
}
