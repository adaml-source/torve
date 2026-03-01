package com.streamvault.android.tv.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.android.player.DeviceCodecProbe
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.presentation.detail.DetailViewModel
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.watchlist.WatchlistViewModel
import org.koin.compose.koinInject

@Composable
fun TvDetailsScreen(
    type: String,
    id: Int,
    autoPlay: Boolean,
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onPlayResolved: (
        url: String,
        fallbackUrl: String,
        mediaItem: MediaItem,
        season: Int?,
        episode: Int?,
    ) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val detailViewModel: DetailViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val state by detailViewModel.state.collectAsState()
    val context = LocalContext.current

    val playFocusRequester = remember { FocusRequester() }
    val watchlistFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }
    var attemptedPickerResolve by remember(type, id) { mutableStateOf(false) }
    var didAutoPlay by rememberSaveable(type, id) { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        detailViewModel.setSettingsProvider { settingsViewModel }
        detailViewModel.setDeviceCodecCaps(DeviceCodecProbe.probe())
        onFirstContentRequester(playFocusRequester)
    }

    LaunchedEffect(type, id) {
        detailViewModel.loadDetail(type, id)
    }

    LaunchedEffect(state.mediaItem?.id, autoPlay) {
        val media = state.mediaItem ?: return@LaunchedEffect
        if (autoPlay && !didAutoPlay) {
            didAutoPlay = true
            if (media.type == MediaType.SERIES) {
                detailViewModel.playNextEpisode()
            } else {
                detailViewModel.fetchStreams()
            }
        }
    }

    LaunchedEffect(state.showStreamPicker, state.streams.size, state.mediaItem?.id) {
        if (state.showStreamPicker && state.streams.isNotEmpty() && !attemptedPickerResolve) {
            val apiKey = settingsViewModel.getDebridApiKey()
            if (apiKey.isNotBlank()) {
                attemptedPickerResolve = true
                detailViewModel.resolveStream(
                    stream = state.streams.first(),
                    provider = settingsViewModel.getDebridProvider(),
                    apiKey = apiKey,
                )
            }
        }
        if (!state.showStreamPicker) {
            attemptedPickerResolve = false
        }
    }

    LaunchedEffect(state.resolvedStream, state.mediaItem) {
        val resolved = state.resolvedStream ?: return@LaunchedEffect
        val media = state.mediaItem ?: return@LaunchedEffect

        val playbackUrl = resolved.transcodeUrls?.mp4
            ?: resolved.transcodeUrls?.hls
            ?: resolved.url

        val fallbackUrl = when (playbackUrl) {
            resolved.url -> resolved.transcodeUrls?.hls ?: resolved.transcodeUrls?.mp4 ?: ""
            resolved.transcodeUrls?.mp4 -> resolved.transcodeUrls?.hls ?: resolved.url
            else -> resolved.url
        }

        onPlayResolved(
            playbackUrl,
            fallbackUrl,
            media,
            state.streamContextSeason,
            state.streamContextEpisode,
        )
        detailViewModel.clearResolvedStream()
    }

    val mediaItem = state.mediaItem
    val isInWatchlist = mediaItem?.let { watchlistViewModel.isInWatchlist(it.id) } == true
    val isBusy = state.isLoading || state.isLoadingStreams || state.isResolving

    if (state.isLoading && mediaItem == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFD6A45B))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 40.dp, top = 20.dp, end = 34.dp, bottom = 24.dp),
    ) {
        mediaItem?.let { item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xE9101422),
                                    Color(0xD9101422),
                                    Color(0x7A101422),
                                    Color(0x30101422),
                                ),
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val metadata = buildString {
                        item.year?.let { append(it) }
                        if (item.rating != null) {
                            if (isNotBlank()) append("  ")
                            append(String.format("%.1f", item.rating))
                        }
                        item.runtime?.let {
                            if (isNotBlank()) append("  ")
                            append("${it}m")
                        }
                    }
                    if (metadata.isNotBlank()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xD5EDF4FF),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            text = if (isBusy) stringResource(R.string.tv_loading) else stringResource(R.string.tv_action_play),
                            modifier = Modifier
                                .focusRequester(playFocusRequester)
                                .focusProperties { left = railFocusRequester },
                            enabled = !isBusy,
                            onFocused = { onContentFocused(playFocusRequester) },
                            onClick = {
                                if (item.type == MediaType.SERIES) {
                                    detailViewModel.playNextEpisode()
                                } else {
                                    detailViewModel.fetchStreams()
                                }
                            },
                        )

                        TvActionButton(
                            text = if (isInWatchlist) {
                                stringResource(R.string.tv_action_remove_watchlist)
                            } else {
                                stringResource(R.string.tv_action_add_watchlist)
                            },
                            modifier = Modifier.focusRequester(watchlistFocusRequester),
                            onFocused = { onContentFocused(watchlistFocusRequester) },
                            onClick = { watchlistViewModel.toggleWatchlist(item) },
                        )

                        if (!item.trailerKey.isNullOrBlank()) {
                            TvActionButton(
                                text = stringResource(R.string.tv_action_trailer),
                                modifier = Modifier.focusRequester(trailerFocusRequester),
                                onFocused = { onContentFocused(trailerFocusRequester) },
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.youtube.com/watch?v=${item.trailerKey}"),
                                    )
                                    runCatching { context.startActivity(intent) }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!item.overview.isNullOrBlank()) {
                Text(
                    text = item.overview.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xE0E3EBF8),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.streamsError != null) {
                Text(
                    text = state.streamsError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFB8B8),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        } ?: run {
            Text(
                text = state.error ?: stringResource(R.string.tv_no_data),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun TvActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.03f else 1f, label = "actionScale")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF1E2A3F) else Color(0x5523344C))
            .border(
                width = 1.5.dp,
                color = if (focused) Color(0xFFD3A967) else Color(0x334D5C74),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable(enabled = enabled)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Color.White else Color(0x80FFFFFF),
        )
    }
}

