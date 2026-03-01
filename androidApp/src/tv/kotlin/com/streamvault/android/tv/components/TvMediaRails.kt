package com.streamvault.android.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.domain.model.MediaItem

data class TvContentRail(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
)

class TvFocusMemory {
    var lastFocusedRowKey: String? by mutableStateOf(null)
    val lastFocusedIndexByRow = mutableStateMapOf<String, Int>()
}

@Composable
fun rememberTvFocusMemory(): TvFocusMemory = remember { TvFocusMemory() }

@Composable
fun TvMediaRails(
    rails: List<TvContentRail>,
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    headerFocusRequester: FocusRequester? = null,
    focusMemory: TvFocusMemory = rememberTvFocusMemory(),
    loading: Boolean = false,
    emptyMessage: String = "",
) {
    val requesterMap = remember { mutableMapOf<String, FocusRequester>() }
    val signature = remember(rails) { rails.joinToString("|") { "${it.key}:${it.items.size}" } }

    LaunchedEffect(signature) {
        if (rails.isEmpty()) return@LaunchedEffect
        val firstKey = rails.first().key
        val targetRowKey = focusMemory.lastFocusedRowKey?.takeIf { row ->
            rails.any { it.key == row }
        } ?: firstKey
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRowKey] ?: 0
        val target = requesterMap["$targetRowKey:$targetIndex"] ?: requesterMap["$firstKey:0"]
        target?.requestFocus()
    }

    when {
        loading && rails.isEmpty() -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color(0xFFD6A45B))
            }
        }

        rails.isEmpty() -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFDCE3F0),
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 40.dp, top = 16.dp, end = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                itemsIndexed(rails, key = { _, row -> row.key }) { rowIndex, row ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(end = 16.dp),
                        ) {
                            itemsIndexed(
                                items = row.items,
                                key = { itemIndex, item ->
                                    item.tmdbId?.let { "tmdb_${item.type}_$it" }
                                        ?: "${item.type}_${item.id}_$itemIndex"
                                },
                            ) { itemIndex, item ->
                                val mapKey = "${row.key}:$itemIndex"
                                val focusRequester = remember(mapKey) { FocusRequester() }
                                requesterMap[mapKey] = focusRequester

                                val isFirstItem = rowIndex == 0 && itemIndex == 0
                                if (isFirstItem) {
                                    onFirstContentRequester(focusRequester)
                                }

                                TvPosterCard(
                                    item = item,
                                    modifier = Modifier
                                        .width(198.dp)
                                        .aspectRatio(2f / 3f)
                                        .focusRequester(focusRequester)
                                        .focusProperties {
                                            if (itemIndex == 0) {
                                                left = railFocusRequester
                                            }
                                            if (rowIndex == 0 && headerFocusRequester != null) {
                                                up = headerFocusRequester
                                            }
                                        },
                                    onClick = { onMediaClick(item) },
                                    onFocused = {
                                        focusMemory.lastFocusedRowKey = row.key
                                        focusMemory.lastFocusedIndexByRow[row.key] = itemIndex
                                        onContentFocused(focusRequester)
                                    },
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
private fun TvPosterCard(
    item: MediaItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "posterScale")
    val borderColor = if (focused) Color(0xFFCFA86C) else Color.Transparent

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        AsyncImage(
            model = item.posterUrl ?: item.backdropUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xE610121A),
                        ),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    item.year?.let { append(it) }
                    if (focused && item.rating != null) {
                        if (isNotBlank()) append("  ")
                        append(String.format("%.1f", item.rating))
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFD0D8E8),
                    )
                }
            }
        }
    }
}

