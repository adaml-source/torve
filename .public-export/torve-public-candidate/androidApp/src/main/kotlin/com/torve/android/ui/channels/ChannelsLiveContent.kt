package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel
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
) {
    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Amber,
                strokeWidth = 3.dp,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // ── Search Bar + Actions ──
        item(key = "search_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Custom search field
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
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(20.dp),
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
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(16.dp),
                        )
                    }
                }

                // View mode toggle
                IconButton(
                    onClick = onToggleViewMode,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (viewMode == ChannelsViewMode.LIST) Icons.Rounded.GridView
                        else Icons.AutoMirrored.Rounded.ViewList,
                        contentDescription = "Toggle view",
                        tint = Torve.colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Filter
                IconButton(
                    onClick = onFilterClick,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = "Filter & Sort",
                        tint = Torve.colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ── Search Results Mode ──
        if (searchQuery.length >= 2) {
            if (searchResults.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
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
                items(searchResults, key = { "search_${it.playlistId}_${it.url}" }) { channel ->
                    ChannelRow(
                        enriched = EnrichedChannel(channel),
                        onPlay = { onChannelPlay(channel) },
                        onFavorite = { onChannelFavorite(channel) },
                    )
                }
            }
            return@LazyColumn
        }

        // ── Collapsible Category List ──
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
                if (viewMode == ChannelsViewMode.LIST) {
                    items(
                        category.channels,
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
                                category.channels,
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
