package com.streamvault.android.ui.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.Steel
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.IptvCategory
import com.streamvault.domain.model.IptvChannel
import com.streamvault.presentation.iptv.IptvViewMode

@Composable
fun IptvMovieContent(
    categories: List<IptvCategory>,
    expandedCategories: Set<String>,
    viewMode: IptvViewMode,
    onToggleCategory: (String) -> Unit,
    onChannelPlay: (IptvChannel) -> Unit,
    onChannelFavorite: (IptvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.Movie,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = StreamVault.colors.textHint,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No movies available",
                    style = MaterialTheme.typography.titleMedium,
                    color = StreamVault.colors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Movies require an Xtream Codes playlist",
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textTertiary,
                )
            }
        }
        return
    }

    CategoryList(
        categories = categories,
        expandedCategories = expandedCategories,
        viewMode = viewMode,
        keyPrefix = "movie",
        onToggleCategory = onToggleCategory,
        onChannelPlay = onChannelPlay,
        onChannelFavorite = onChannelFavorite,
        modifier = modifier,
    )
}
