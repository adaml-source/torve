package com.streamvault.android.ui.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.IptvChannel

@Composable
fun IptvFavouritesContent(
    favorites: List<IptvChannel>,
    onChannelPlay: (IptvChannel) -> Unit,
    onChannelFavorite: (IptvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = StreamVault.colors.textHint,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No favourites yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = StreamVault.colors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap the heart on any channel to add it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textTertiary,
                )
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(favorites, key = { "${it.playlistId}_${it.url}" }) { channel ->
                ChannelRow(
                    enriched = EnrichedChannel(channel = channel.copy(isFavorite = true)),
                    onPlay = { onChannelPlay(channel) },
                    onFavorite = { onChannelFavorite(channel) },
                )
            }
        }
    }
}
