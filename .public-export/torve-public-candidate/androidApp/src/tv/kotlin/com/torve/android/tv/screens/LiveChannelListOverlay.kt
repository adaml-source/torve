package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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

private const val GROUP_PANEL_WIDTH_DP = 236
private const val DETAIL_PANEL_WIDTH_DP = 340
private const val LONG_PRESS_THRESHOLD_MS = 650L

@Composable
fun LiveChannelListOverlay(
    categories: List<ChannelCategory>,
    currentChannelUrl: String,
    currentGroupName: String,
    favoriteChannels: List<Channel>,
    onTuneChannel: (Channel, String) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onLoadCategoryChannels: (suspend (String) -> List<EnrichedChannel>)? = null,
    preloadedChannelsByCategory: Map<String, List<EnrichedChannel>> = emptyMap(),
    onDismiss: () -> Unit = {},
) {
    BackHandler { onDismiss() }

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
    // (debug log removed — it ran `categories.map { it.name }` on every recomposition,
    // allocating 160+ strings per navigation tick and contributing to the GC-induced ANR.)
    val groupListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialGroupIndex.coerceAtLeast(0),
    )
    val channelListState = rememberLazyListState()
    var selectedGroupIndex by remember(currentChannelUrl) {
        mutableIntStateOf(initialGroupIndex)
    }
    var loadedCategoryChannels by remember(categories, preloadedChannelsByCategory) {
        mutableStateOf<Map<String, List<EnrichedChannel>>>(preloadedChannelsByCategory)
    }
    var loadingCategoryName by remember { mutableStateOf<String?>(null) }

    val categoriesWithLoadedChannels = remember(categories, loadedCategoryChannels) {
        categories.map { category ->
            loadedCategoryChannels[category.name]?.let { loadedChannels ->
                category.copy(
                    channels = loadedChannels,
                    channelCount = loadedChannels.size,
                )
            } ?: category
        }
    }

    // Scroll group panel to active group whenever the overlay opens for a new channel
    LaunchedEffect(currentChannelUrl, initialGroupIndex) {
        if (initialGroupIndex > 0 && categoriesWithLoadedChannels.isNotEmpty()) {
            groupListState.scrollToItem(initialGroupIndex.coerceIn(0, categoriesWithLoadedChannels.lastIndex))
        }
    }

    val maxIndex = (categoriesWithLoadedChannels.size - 1).coerceAtLeast(0)
    if (selectedGroupIndex > maxIndex) selectedGroupIndex = maxIndex
    val selectedCategory = remember(categoriesWithLoadedChannels, selectedGroupIndex) {
        categoriesWithLoadedChannels.getOrNull(selectedGroupIndex)
    }
    val channelsInGroup = remember(selectedCategory) {
        selectedCategory?.channels ?: emptyList()
    }
    val isLoadingSelectedCategory = selectedCategory?.name == loadingCategoryName
    val initialChannelIndex = remember(channelsInGroup, currentChannelUrl) {
        channelsInGroup.indexOfFirst { it.channel.url == currentChannelUrl }.takeIf { it >= 0 } ?: 0
    }
    var focusedChannelIndex by remember(selectedGroupIndex, currentChannelUrl) {
        mutableIntStateOf(initialChannelIndex)
    }
    var previewChannelUrl by remember(currentChannelUrl) { mutableStateOf(currentChannelUrl) }
    var shouldAutoFocusChannels by remember { mutableStateOf(true) }
    // Build a URL → channel index once, then look up by URL in O(1). Previously this
    // flatMap'd ALL channels in ALL categories on every recomposition, allocating tens
    // of thousands of list entries per navigation tick — the main-thread allocation
    // pressure was triggering ANRs during overlay navigation on Fire TV.
    val channelsByUrl = remember(categoriesWithLoadedChannels) {
        val map = HashMap<String, EnrichedChannel>()
        for (cat in categoriesWithLoadedChannels) {
            for (ch in cat.channels) {
                map.putIfAbsent(ch.channel.url, ch)
            }
        }
        map
    }
    val previewEnriched = remember(previewChannelUrl, currentChannelUrl, channelsByUrl) {
        channelsByUrl[previewChannelUrl] ?: channelsByUrl[currentChannelUrl]
    }

    if (focusedChannelIndex > channelsInGroup.lastIndex) {
        focusedChannelIndex = channelsInGroup.lastIndex.coerceAtLeast(0)
    }

    LaunchedEffect(selectedCategory?.name, selectedCategory?.channels?.size, selectedCategory?.channelCount) {
        val category = selectedCategory ?: return@LaunchedEffect
        val loader = onLoadCategoryChannels ?: return@LaunchedEffect
        if (
            category.channels.isNotEmpty() ||
            category.channelCount <= 0 ||
            loadedCategoryChannels.containsKey(category.name)
        ) {
            return@LaunchedEffect
        }

        loadingCategoryName = category.name
        try {
            val loadedChannels = loader(category.name)
            loadedCategoryChannels = loadedCategoryChannels + (category.name to loadedChannels)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            android.util.Log.w(
                "LiveChannelListOverlay",
                "Failed to load category ${category.name}: ${error.message}",
            )
            loadedCategoryChannels = loadedCategoryChannels + (category.name to emptyList())
        } finally {
            if (loadingCategoryName == category.name) {
                loadingCategoryName = null
            }
        }
    }

    LaunchedEffect(selectedGroupIndex, channelsInGroup, currentChannelUrl) {
        if (channelsInGroup.isEmpty()) {
            if (shouldAutoFocusChannels && !isLoadingSelectedCategory) {
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
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.78f),
                    0.48f to Color.Black.copy(alpha = 0.52f),
                    1f to Color.Black.copy(alpha = 0.28f),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 42.dp, vertical = 34.dp),
        ) {
            ZappingOverlayHeader(previewEnriched = previewEnriched, currentChannelUrl = currentChannelUrl)
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Left panel: category/group list
                LazyColumn(
                    state = groupListState,
                    modifier = Modifier
                        .width(GROUP_PANEL_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(Charcoal.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                        .padding(vertical = 8.dp),
                ) {
                    itemsIndexed(
                        categoriesWithLoadedChannels,
                        key = { _, cat -> cat.name },
                    ) { index, category ->
                        val isSelected = index == selectedGroupIndex
                        Surface(
                            onClick = { selectedGroupIndex = index },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .then(if (index == selectedGroupIndex) Modifier.focusRequester(groupFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) selectedGroupIndex = index }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                        shouldAutoFocusChannels = true
                                        if (category.channels.isNotEmpty()) {
                                            runCatching { channelFocus.requestFocus() }
                                        }
                                        true
                                    } else {
                                        false
                                    }
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) AmberSubtle.copy(alpha = 0.45f) else Charcoal.copy(alpha = 0.01f),
                                focusedContainerColor = Amber.copy(alpha = 0.2f),
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(
                                    border = BorderStroke(1.5.dp, Amber),
                                    shape = RoundedCornerShape(12.dp),
                                ),
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = category.name,
                                    color = if (isSelected) Amber else Snow,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${category.channelCount}",
                                    color = Silver,
                                    fontSize = 11.sp,
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
                            text = if (isLoadingSelectedCategory) {
                                stringResource(R.string.common_loading)
                            } else {
                                stringResource(R.string.tv_live_no_channels_in_list)
                            },
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
                            .background(Charcoal.copy(alpha = 0.64f), RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
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

                // Right panel: informational only. Actions stay on the focused channel row/menu.
                SelectedChannelDetailsPanel(
                    enrichedChannel = previewEnriched,
                    modifier = Modifier
                        .width(DETAIL_PANEL_WIDTH_DP.dp)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ZappingOverlayHeader(
    previewEnriched: EnrichedChannel?,
    currentChannelUrl: String,
) {
    val channel = previewEnriched?.channel
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (channel?.url == currentChannelUrl) "Now playing" else "Switch channel",
            color = Silver,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = channel?.name ?: stringResource(R.string.tv_live_no_channel_playing),
            color = Snow,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        previewEnriched?.currentProgramme?.let { programme ->
            Text(
                text = programme.title,
                color = Silver,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectedChannelDetailsPanel(
    enrichedChannel: EnrichedChannel?,
    modifier: Modifier = Modifier,
) {
    val channel = enrichedChannel?.channel
    Box(
        modifier = modifier
            .background(Charcoal.copy(alpha = 0.68f), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(54.dp)
                    .background(Graphite.copy(alpha = 0.46f), RoundedCornerShape(14.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (channel?.tvgLogo != null) {
                    AsyncImage(
                        model = channel.tvgLogo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = channel?.name?.take(6).orEmpty(),
                        color = Snow,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = channel?.name ?: stringResource(R.string.tv_live_no_channel_playing),
                color = Snow,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            enrichedChannel?.currentProgramme?.let { current ->
                Text(
                    text = current.title,
                    color = Snow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { programmeProgress(current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Amber,
                    trackColor = Graphite.copy(alpha = 0.8f),
                )
            } ?: Text(
                text = "No programme data",
                color = Silver,
                fontSize = 12.sp,
                maxLines = 1,
            )
            enrichedChannel?.nextProgramme?.let { next ->
                Text(
                    text = "Next: ${next.title}",
                    color = Silver,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
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
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onTune,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(vertical = 1.dp)
            .zIndex(if (isFocused) 4f else 0f)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPlaying) AmberSubtle.copy(alpha = 0.45f) else Graphite.copy(alpha = 0.46f),
            focusedContainerColor = Amber.copy(alpha = 0.22f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(1.5.dp, Amber),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPlaying) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Coral,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
            }

            ch.tvgLogo?.let { logo ->
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ch.name,
                    color = if (isPlaying) Amber else Snow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                enrichedChannel.currentProgramme?.let { prog ->
                    Text(
                        text = prog.title,
                        color = Silver,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (isFavorite) Amber else Silver.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
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
