package com.streamvault.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.ShelfType

@Composable
fun CatalogShelf(
    title: String,
    items: List<MediaItem>,
    shelfType: ShelfType = ShelfType.POSTER,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardStyle = when (shelfType) {
        ShelfType.LANDSCAPE, ShelfType.WIDE -> CardStyle.LANDSCAPE
        else -> CardStyle.POSTER
    }

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    style = cardStyle,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}
