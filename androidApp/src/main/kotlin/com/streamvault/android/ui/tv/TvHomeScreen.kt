package com.streamvault.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.catalog.CatalogViewModel
import org.koin.compose.koinInject

@Composable
fun TvHomeScreen(
    onMediaClick: (MediaItem) -> Unit,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val movieViewModel = remember { CatalogViewModel(metadataRepo, "movie") }
    val tvViewModel = remember { CatalogViewModel(metadataRepo, "tv") }

    val movieState by movieViewModel.state.collectAsState()
    val tvState by tvViewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 32.dp),
    ) {
        // App title
        Text(
            "StreamVault",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 48.dp),
        )

        Spacer(Modifier.height(24.dp))

        // Trending Movies row
        TvContentRow(
            title = "Trending Movies",
            items = movieState.items,
            onItemClick = onMediaClick,
        )

        Spacer(Modifier.height(24.dp))

        // Trending TV Shows row
        TvContentRow(
            title = "Trending TV Shows",
            items = tvState.items,
            onItemClick = onMediaClick,
        )
    }
}

@Composable
private fun TvContentRow(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.tmdbId ?: it.hashCode() }) { item ->
                TvMediaCard(
                    item = item,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun TvMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
) {
    var isFocused = remember { false }

    Box(
        modifier = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else Color.Transparent,
            ),
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // Gradient overlay with title
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    ),
                )
                .padding(8.dp),
        ) {
            Column {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val year = item.year
                if (year != null) {
                    Text(
                        year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // Focus border
        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            )
        }
    }
}
