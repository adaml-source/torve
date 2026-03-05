package com.streamvault.android.tv.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberLight
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.domain.model.MediaItem

enum class TvCardStyle { POSTER, BACKDROP }

data class TvContentRail(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
    val cardStyle: TvCardStyle = TvCardStyle.POSTER,
    val progressByMediaId: Map<String, Float> = emptyMap(),
)

/**
 * Cross-rail deduplication: each item appears only in the first rail
 * where it was found. Uses tmdbId as the primary key, falling back
 * to "${type}:${id}". Empty rails are removed.
 */
fun List<TvContentRail>.dedupeAcrossRails(): List<TvContentRail> {
    val seen = mutableSetOf<String>()
    return mapNotNull { rail ->
        val filtered = rail.items.filter { item ->
            val key = item.tmdbId?.let { "${item.type}:$it" } ?: "${item.type}:${item.id}"
            seen.add(key) // returns true if element was added (not already present)
        }
        if (filtered.isEmpty()) null else rail.copy(items = filtered)
    }
}

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
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
) {
    val context = LocalContext.current
    val tvPrefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    val showTitles = tvPrefs.getBoolean("tv_show_poster_titles", true)

    val requesterMap = remember { mutableMapOf<String, FocusRequester>() }
    val signature = remember(rails) { rails.joinToString("|") { "${it.key}:${it.items.size}" } }

    LaunchedEffect(signature, shouldAutoFocus) {
        if (!shouldAutoFocus) return@LaunchedEffect
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
                CircularProgressIndicator(color = Amber)
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
                    color = Silver,
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Hero overlay scrolls with content
                if (heroOverlay != null) {
                    item(key = "hero_overlay") {
                        heroOverlay()
                    }
                }

                itemsIndexed(rails, key = { _, row -> row.key }) { rowIndex, row ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 40.dp),
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = 40.dp, end = 32.dp),
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

                                val progress = row.progressByMediaId[item.id]
                                val cardModifier = Modifier
                                    .focusRequester(focusRequester)
                                    .focusProperties {
                                        if (itemIndex == 0) {
                                            left = railFocusRequester
                                        }
                                        if (rowIndex == 0 && headerFocusRequester != null) {
                                            up = headerFocusRequester
                                        }
                                    }

                                val onItemFocused: () -> Unit = {
                                    focusMemory.lastFocusedRowKey = row.key
                                    focusMemory.lastFocusedIndexByRow[row.key] = itemIndex
                                    onContentFocused(focusRequester)
                                    onMediaFocused?.invoke(item)
                                }

                                when (row.cardStyle) {
                                    TvCardStyle.BACKDROP -> {
                                        TvBackdropCard(
                                            item = item,
                                            modifier = cardModifier,
                                            onClick = { onMediaClick(item) },
                                            onFocused = onItemFocused,
                                            progress = progress,
                                        )
                                    }

                                    TvCardStyle.POSTER -> {
                                        val posterWidth = if (heroOverlay != null && rowIndex == 0) {
                                            118.dp
                                        } else {
                                            132.dp
                                        }
                                        TvPosterCard(
                                            item = item,
                                            modifier = cardModifier
                                                .width(posterWidth)
                                                .aspectRatio(2f / 3f),
                                            onClick = { onMediaClick(item) },
                                            onFocused = onItemFocused,
                                            progress = progress,
                                            showTitles = showTitles,
                                        )
                                    }
                                }
                            }

                            if (onSeeAll != null) {
                                item(key = "${row.key}_see_all") {
                                    val seeAllRequester = remember("${row.key}_see_all") { FocusRequester() }
                                    requesterMap["${row.key}:see_all"] = seeAllRequester
                                    TvSeeAllButton(
                                        modifier = Modifier
                                            .focusRequester(seeAllRequester)
                                            .focusProperties {
                                                if (rowIndex == 0 && headerFocusRequester != null) {
                                                    up = headerFocusRequester
                                                }
                                            },
                                        onClick = { onSeeAll(row.key, row.title) },
                                        onFocused = {
                                            focusMemory.lastFocusedRowKey = row.key
                                            onContentFocused(seeAllRequester)
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
}

@Composable
private fun TvPosterCard(
    item: MediaItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    progress: Float? = null,
    showTitles: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "posterScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "posterBorder",
    )

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
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

        if (showTitles) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Obsidian.copy(alpha = 0.9f),
                            ),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item.year?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Silver,
                            )
                        }
                        if (item.rating != null) {
                            if (item.year != null) {
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                            Image(
                                painter = painterResource(id = R.drawable.ic_rating_tmdb),
                                contentDescription = "TMDB",
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = String.format("%.1f", item.rating),
                                style = MaterialTheme.typography.labelMedium,
                                color = Silver,
                            )
                        }
                    }
                }
            }
        }

        if (progress != null && progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Snow.copy(alpha = 0.27f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Amber),
                )
            }
        }
    }
}

@Composable
private fun TvSeeAllButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "seeAllScale")
    val borderColor = if (focused) AmberLight else Steel.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .width(120.dp)
            .aspectRatio(2f / 3f)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(Charcoal)
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.tv_see_all),
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
