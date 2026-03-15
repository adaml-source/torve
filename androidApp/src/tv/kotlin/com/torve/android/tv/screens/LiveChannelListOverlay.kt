package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Coral
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel

private const val GROUP_PANEL_WIDTH_DP = 260
private const val PREVIEW_PANEL_WIDTH_DP = 340
private const val LONG_PRESS_THRESHOLD_MS = 650L

@Composable
fun LiveChannelListOverlay(
    categories: List<ChannelCategory>,
    currentChannelUrl: String,
    currentGroupName: String,
    favoriteChannels: List<Channel>,
    onTuneChannel: (Channel, String) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismiss: () -> Unit = {},
) {
    BackHandler { onDismiss() }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val favoritesByUrl = remember(favoriteChannels) { favoriteChannels.associateBy { it.url } }
    val initialGroupIndex = remember(categories, currentChannelUrl, currentGroupName) {
        resolveGroupIndex(
            categories = categories,
            currentGroupName = currentGroupName,
            currentChannelUrl = currentChannelUrl,
        )
    }
    android.util.Log.d(
        "GroupDebug",
        "currentGroupName='$currentGroupName' currentChannelUrl='$currentChannelUrl' initialGroupIndex=$initialGroupIndex categories=${categories.map { it.name }.take(5)}",
    )
    val groupListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialGroupIndex.coerceAtLeast(0),
    )
    val channelListState = rememberLazyListState()
    var selectedGroupIndex by remember(currentChannelUrl) {
        mutableIntStateOf(initialGroupIndex)
    }

    val filteredCategories = remember(categories, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            categories
        } else {
            val normalized = query.lowercase()
            categories.mapNotNull { category ->
                val channels = category.channels.filter { enriched ->
                    enriched.channel.name.contains(normalized, ignoreCase = true) ||
                        enriched.currentProgramme?.title?.contains(normalized, ignoreCase = true) == true ||
                        enriched.nextProgramme?.title?.contains(normalized, ignoreCase = true) == true
                }
                if (channels.isEmpty()) {
                    null
                } else {
                    category.copy(
                        channels = channels,
                        channelCount = channels.size,
                    )
                }
            }
        }
    }

    // Scroll group panel to active group whenever the overlay opens for a new channel
    LaunchedEffect(currentChannelUrl, initialGroupIndex) {
        if (initialGroupIndex > 0 && filteredCategories.isNotEmpty()) {
            groupListState.scrollToItem(initialGroupIndex.coerceIn(0, filteredCategories.lastIndex))
        }
    }

    val maxIndex = (filteredCategories.size - 1).coerceAtLeast(0)
    if (selectedGroupIndex > maxIndex) selectedGroupIndex = maxIndex
    val selectedCategory = filteredCategories.getOrNull(selectedGroupIndex)
    val channelsInGroup = selectedCategory?.channels ?: emptyList()
    val initialChannelIndex = remember(channelsInGroup, currentChannelUrl) {
        channelsInGroup.indexOfFirst { it.channel.url == currentChannelUrl }.takeIf { it >= 0 } ?: 0
    }
    var focusedChannelIndex by remember(selectedGroupIndex, currentChannelUrl) {
        mutableIntStateOf(initialChannelIndex)
    }
    var previewChannelUrl by remember(currentChannelUrl) { mutableStateOf(currentChannelUrl) }
    var shouldAutoFocusChannels by remember { mutableStateOf(true) }
    val previewEnriched = remember(previewChannelUrl, currentChannelUrl, categories) {
        categories.flatMap { it.channels }.firstOrNull { it.channel.url == previewChannelUrl }
            ?: categories.flatMap { it.channels }.firstOrNull { it.channel.url == currentChannelUrl }
    }

    if (focusedChannelIndex > channelsInGroup.lastIndex) {
        focusedChannelIndex = channelsInGroup.lastIndex.coerceAtLeast(0)
    }


    LaunchedEffect(selectedGroupIndex, channelsInGroup, currentChannelUrl) {
        if (channelsInGroup.isEmpty()) {
            if (shouldAutoFocusChannels) {
                try { groupFocus.requestFocus() } catch (_: IllegalStateException) {}
                shouldAutoFocusChannels = false
            }
            return@LaunchedEffect
        }
        focusedChannelIndex = channelsInGroup.indexOfFirst { it.channel.url == currentChannelUrl }
            .takeIf { it >= 0 }
            ?: focusedChannelIndex.coerceIn(0, channelsInGroup.lastIndex)

        val targetChannel = channelsInGroup.getOrNull(focusedChannelIndex)?.channel?.url
            ?: channelsInGroup.first().channel.url
        previewChannelUrl = targetChannel

        runCatching { channelListState.scrollToItem(focusedChannelIndex) }
        if (shouldAutoFocusChannels) {
            kotlinx.coroutines.delay(40)
            try { channelFocus.requestFocus() } catch (_: IllegalStateException) {}
            shouldAutoFocusChannels = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.55f))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Backspace -> {
                        if (searchQuery.isNotEmpty()) {
                            searchQuery = searchQuery.dropLast(1)
                            true
                        } else {
                            false
                        }
                    }

                    Key.Spacebar -> {
                        searchQuery += " "
                        true
                    }

                    else -> {
                        val keyCode = event.key.keyCode.toInt()
                        val unicode = android.view.KeyCharacterMap
                            .load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                            .get(keyCode, 0)
                        if (unicode > 0) {
                            val c = unicode.toChar()
                            if (!c.isISOControl()) {
                                searchQuery += c
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.tv_live_channels_list),
                color = Snow,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
                    .background(Graphite.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (searchQuery.isBlank()) {
                        stringResource(R.string.tv_live_search_hint)
                    } else {
                        stringResource(R.string.tv_live_search_query, searchQuery)
                    },
                    color = if (searchQuery.isBlank()) Silver else Snow,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.tv_live_search_clear_hint),
                    color = Silver,
                    fontSize = 11.sp,
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // Left panel: category/group list
                LazyColumn(
                    state = groupListState,
                    modifier = Modifier
                        .width(GROUP_PANEL_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(Charcoal.copy(alpha = 0.7f))
                        .padding(vertical = 4.dp),
                ) {
                    itemsIndexed(
                        filteredCategories,
                        key = { _, cat -> cat.name },
                    ) { index, category ->
                        val isSelected = index == selectedGroupIndex
                        Surface(
                            onClick = { selectedGroupIndex = index },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .then(if (index == selectedGroupIndex) Modifier.focusRequester(groupFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) selectedGroupIndex = index }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                        runCatching { channelFocus.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) AmberSubtle else Charcoal.copy(alpha = 0.01f),
                                focusedContainerColor = Amber.copy(alpha = 0.2f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = category.name,
                                    color = if (isSelected) Amber else Snow,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${category.channelCount}",
                                    color = Silver,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                // Middle panel: channels in selected group
                if (channelsInGroup.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.tv_live_no_channels_in_list),
                            color = Silver,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = channelListState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        itemsIndexed(
                            channelsInGroup,
                            key = { index, ch -> "${index}_${ch.channel.url}" },
                        ) { index, enrichedChannel ->
                            val ch = enrichedChannel.channel
                            val isPlaying = ch.url == currentChannelUrl
                            val isFavorite = favoritesByUrl.containsKey(ch.url)

                            ChannelListItem(
                                enrichedChannel = enrichedChannel,
                                isPlaying = isPlaying,
                                isFavorite = isFavorite,
                                modifier = if (index == focusedChannelIndex) {
                                    Modifier.focusRequester(channelFocus)
                                } else {
                                    Modifier
                                },
                                onFocused = {
                                    previewChannelUrl = ch.url
                                    focusedChannelIndex = index
                                },
                                onTune = { onTuneChannel(ch, selectedCategory?.name ?: ch.groupTitle.orEmpty()) },
                                onToggleFavorite = { onToggleFavorite(ch) },
                            )
                        }
                    }
                }

                // Right panel: mini preview metadata while searching/browsing
                Box(
                    modifier = Modifier
                        .width(PREVIEW_PANEL_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .padding(end = 24.dp, top = 4.dp, bottom = 4.dp)
                        .background(Charcoal.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                        .padding(14.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tv_live_mini_preview_title),
                            color = Amber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.tv_live_mini_preview_subtitle),
                            color = Silver,
                            fontSize = 11.sp,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = previewEnriched?.channel?.name ?: stringResource(R.string.tv_live_no_channel_playing),
                            color = Snow,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        previewEnriched?.currentProgramme?.let { current ->
                            Text(
                                text = current.title,
                                color = Silver,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.tv_live_favorite_hint),
                            color = Silver,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelListItem(
    enrichedChannel: EnrichedChannel,
    isPlaying: Boolean,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onTune: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val ch = enrichedChannel.channel
    var centerKeyDownTime by remember { mutableLongStateOf(0L) }

    Surface(
        onClick = onTune,
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Menu && event.type == KeyEventType.KeyDown -> {
                        onToggleFavorite()
                        true
                    }

                    (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter) -> {
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (centerKeyDownTime == 0L) {
                                    centerKeyDownTime = System.currentTimeMillis()
                                }
                                false
                            }

                            KeyEventType.KeyUp -> {
                                val heldMs = System.currentTimeMillis() - centerKeyDownTime
                                centerKeyDownTime = 0L
                                if (heldMs >= LONG_PRESS_THRESHOLD_MS) {
                                    onToggleFavorite()
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> false
                        }
                    }

                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPlaying) AmberSubtle else Graphite.copy(alpha = 0.4f),
            focusedContainerColor = Amber.copy(alpha = 0.2f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                shape = RoundedCornerShape(6.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPlaying) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Coral,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            ch.tvgLogo?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(10.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ch.name,
                    color = if (isPlaying) Amber else Snow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                enrichedChannel.currentProgramme?.let { prog ->
                    Text(
                        text = prog.title,
                        color = Silver,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (isFavorite) Amber else Silver.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun resolveGroupIndex(
    categories: List<ChannelCategory>,
    currentGroupName: String,
    currentChannelUrl: String,
): Int {
    val normalizedGroup = currentGroupName.trim()
    return categories.indexOfFirst { category ->
        category.name.trim().equals(normalizedGroup, ignoreCase = true)
    }.takeIf { it >= 0 }
        ?: categories.indexOfFirst { category ->
            category.channels.any { enriched -> enriched.channel.url == currentChannelUrl }
        }.takeIf { it >= 0 }
        ?: 0
}
