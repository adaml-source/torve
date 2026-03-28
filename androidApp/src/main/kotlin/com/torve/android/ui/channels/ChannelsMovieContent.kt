package com.torve.android.ui.channels

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.Channel
import com.torve.presentation.channels.ChannelsViewMode

@Composable
fun ChannelsMovieContent(
    categories: List<ChannelCategory>,
    expandedCategories: Set<String>,
    viewMode: ChannelsViewMode,
    onToggleCategory: (String) -> Unit,
    onChannelPlay: (Channel) -> Unit,
    onChannelFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.Movie,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Torve.colors.textHint,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.channels_no_movies),
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.channels_movies_xtream),
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary,
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
