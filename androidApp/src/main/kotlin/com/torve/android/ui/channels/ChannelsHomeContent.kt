package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.LiveDot
import com.torve.android.ui.components.SectionHeader
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel

@Composable
fun ChannelsHomeContent(
    recentlyViewed: List<Channel>,
    favorites: List<Channel>,
    liveCategories: List<ChannelCategory>,
    onChannelPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Recently Viewed
        if (recentlyViewed.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.channels_recently_viewed))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(recentlyViewed, key = { "${it.playlistId}_${it.url}" }) { channel ->
                    HomeChannelCard(channel = channel, onClick = { onChannelPlay(channel) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Favorites
        if (favorites.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.channels_favorites))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(favorites, key = { "${it.playlistId}_${it.url}" }) { channel ->
                    HomeChannelCard(channel = channel, onClick = { onChannelPlay(channel) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Category previews — top 5 categories
        liveCategories.take(5).forEach { category ->
            val prefix = category.countryCode?.let { "[$it] " } ?: ""
            SectionHeader(title = "$prefix${category.name} (${category.channelCount})")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    category.channels.take(10),
                    key = { "${it.channel.playlistId}_${it.channel.url}" },
                ) { enriched ->
                    HomeChannelCard(
                        channel = enriched.channel,
                        currentProgramme = enriched.currentProgramme?.title,
                        onClick = { onChannelPlay(enriched.channel) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Empty state
        if (recentlyViewed.isEmpty() && favorites.isEmpty() && liveCategories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.LiveTv,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Torve.colors.textHint,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.channels_no_content),
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.channels_add_playlist_start),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeChannelCard(
    channel: Channel,
    currentProgramme: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Torve.colors.elevatedSurface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(10.dp),
        ) {
            // Logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Torve.colors.inputBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.tvgLogo != null) {
                    AsyncImage(
                        model = channel.tvgLogo,
                        contentDescription = channel.name,
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = Amber,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = Torve.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            currentProgramme?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
