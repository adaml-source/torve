package com.torve.android.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.*
import com.torve.domain.model.Episode
import com.torve.domain.model.Season
import com.torve.domain.model.WatchProgress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

@Composable
fun TvEpisodePicker(
    seasons: List<Season>,
    selectedSeason: Int,
    seasonDetail: Season?,
    isLoadingSeasonDetail: Boolean,
    watchedEpisodes: Set<String>,
    watchProgress: WatchProgress?,
    railFocusRequester: FocusRequester,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (season: Int, episode: Int) -> Unit,
    onSeasonDownload: ((Int) -> Unit)? = null,
    onToggleEpisodeWatched: (season: Int, episode: Int) -> Unit = { _, _ -> },
    onMarkSeasonWatched: (season: Int) -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    autoFocusFirstSeason: Boolean = false,
) {
    val seasonsLabel = stringResource(R.string.tv_episodes_title)
    val seasonNumbers = remember(seasons) {
        seasons.filter { it.seasonNumber > 0 }.map { it.seasonNumber }
    }
    val seasonRequesters = remember(seasonNumbers) {
        seasonNumbers.associateWith { FocusRequester() }
    }
    val fallbackSeasonRequester = remember { FocusRequester() }
    val firstSeasonRequester = seasonNumbers.firstOrNull()
        ?.let { seasonRequesters[it] }
        ?: fallbackSeasonRequester
    val selectedSeasonRequester = seasonRequesters[selectedSeason] ?: firstSeasonRequester
    val firstEpisodeRequester = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectedSeasonEpisodeCount = seasonDetail
        ?.takeIf { it.seasonNumber == selectedSeason }
        ?.episodes
        ?.size
        ?: seasons.firstOrNull { it.seasonNumber == selectedSeason }?.episodeCount
        ?: 0
    val selectedSeasonWatchedCount = remember(selectedSeason, selectedSeasonEpisodeCount, watchedEpisodes) {
        (1..selectedSeasonEpisodeCount).count { episode ->
            "s${selectedSeason}e$episode" in watchedEpisodes
        }
    }
    val selectedSeasonFullyWatched = selectedSeasonEpisodeCount > 0 &&
        selectedSeasonWatchedCount >= selectedSeasonEpisodeCount
    // Reset episode scroll whenever the season changes
    androidx.compose.runtime.LaunchedEffect(selectedSeason) {
        episodesListState.scrollToItem(0)
    }
    androidx.compose.runtime.LaunchedEffect(autoFocusFirstSeason) {
        if (autoFocusFirstSeason) {
            kotlinx.coroutines.delay(120)
            runCatching { firstSeasonRequester.requestFocus() }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = seasonsLabel,
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.focusProperties { canFocus = false },
        )

        // Season tabs
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            itemsIndexed(
                items = seasons.filter { it.seasonNumber > 0 },
                key = { _, s -> "season_${s.seasonNumber}" },
            ) { index, season ->
                val requester = seasonRequesters[season.seasonNumber] ?: fallbackSeasonRequester
                if (index == 0) {
                    onFirstContentRequester(requester)
                }
                TvSeasonChip(
                    seasonNumber = season.seasonNumber,
                    isSelected = season.seasonNumber == selectedSeason,
                    modifier = Modifier
                        .focusRequester(requester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                                scope.launch {
                                    episodesListState.scrollToItem(0)
                                    kotlinx.coroutines.delay(80)
                                    runCatching { firstEpisodeRequester.requestFocus() }
                                }
                                true
                            } else false
                        },
                    onFocused = { onContentFocused(requester) },
                    onClick = { onSeasonSelected(season.seasonNumber) },
                    onLongClick = { onMarkSeasonWatched(season.seasonNumber) },
                )
            }

            if (onSeasonDownload != null) {
                item(key = "dl_season") {
                    TvDownloadSeasonChip(
                        seasonNumber = selectedSeason,
                        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                                scope.launch {
                                    episodesListState.scrollToItem(0)
                                    kotlinx.coroutines.delay(80)
                                    runCatching { firstEpisodeRequester.requestFocus() }
                                }
                                true
                            } else false
                        },
                        onFocused = {},
                        onClick = { onSeasonDownload(selectedSeason) },
                    )
                }
            }

            item(key = "mark_season_watched_$selectedSeason") {
                val requester = remember("mark_season_watched_$selectedSeason") { FocusRequester() }
                TvSeasonWatchedActionChip(
                    seasonNumber = selectedSeason,
                    isComplete = selectedSeasonFullyWatched,
                    enabled = selectedSeasonEpisodeCount > 0 && !selectedSeasonFullyWatched,
                    modifier = Modifier
                        .focusRequester(requester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                                scope.launch {
                                    episodesListState.scrollToItem(0)
                                    kotlinx.coroutines.delay(80)
                                    runCatching { firstEpisodeRequester.requestFocus() }
                                }
                                true
                            } else false
                        },
                    onFocused = { onContentFocused(requester) },
                    onClick = { onMarkSeasonWatched(selectedSeason) },
                )
            }
        }

        // Episode cards
        when {
            isLoadingSeasonDetail -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber)
                }
            }

            seasonDetail != null && seasonDetail.episodes.isNotEmpty() -> {
                LazyRow(
                    state = episodesListState,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                ) {
                    itemsIndexed(
                        items = seasonDetail.episodes,
                        key = { _, ep -> "ep_${selectedSeason}_${ep.episodeNumber}" },
                    ) { index, episode ->
                        val episodeKey = "s${selectedSeason}e${episode.episodeNumber}"
                        val isWatched = episodeKey in watchedEpisodes
                        val episodeProgress = if (
                            watchProgress?.seasonNumber == selectedSeason &&
                            watchProgress.episodeNumber == episode.episodeNumber
                        ) {
                            watchProgress.progressPercent
                        } else {
                            null
                        }

                        TvEpisodeCard(
                            episode = episode,
                            seasonNumber = selectedSeason,
                            isWatched = isWatched,
                            progress = episodeProgress,
                            onClick = { onEpisodeSelected(selectedSeason, episode.episodeNumber) },
                            onLongClick = { onToggleEpisodeWatched(selectedSeason, episode.episodeNumber) },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(firstEpisodeRequester) else Modifier)
                                .focusProperties { up = selectedSeasonRequester },
                        )
                    }
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.tv_no_episodes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Silver,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvSeasonChip(
    seasonNumber: Int,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "seasonScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isSelected -> Amber.copy(alpha = 0.67f)
            else -> Steel.copy(alpha = 0.4f)
        },
        label = "seasonBorder",
    )
    val bgColor = when {
        focused -> Gunmetal
        isSelected -> Graphite
        else -> Charcoal
    }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { keyEvent ->
                val kc = keyEvent.nativeKeyEvent.keyCode
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER || kc == android.view.KeyEvent.KEYCODE_ENTER) {
                    when {
                        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                            keyEvent.nativeKeyEvent.repeatCount == 0 -> {
                            longPressHandled = false
                            false
                        }
                        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                            keyEvent.nativeKeyEvent.repeatCount >= 20 && !longPressHandled -> {
                            longPressHandled = true
                            onLongClick()
                            true
                        }
                        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP && longPressHandled -> {
                            longPressHandled = false
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "S$seasonNumber",
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected || focused) Snow else Silver,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvSeasonWatchedActionChip(
    seasonNumber: Int,
    isComplete: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.04f else 1f, label = "seasonWatchedScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isComplete -> Amber.copy(alpha = 0.42f)
            else -> Steel.copy(alpha = 0.4f)
        },
        label = "seasonWatchedBorder",
    )
    val label = if (isComplete) {
        stringResource(R.string.tv_season_watched, seasonNumber)
    } else {
        stringResource(R.string.tv_season_mark_watched, seasonNumber)
    }

    Row(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Gunmetal else Charcoal)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .combinedClickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled || isComplete) Snow else Silver,
            fontWeight = if (isComplete) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvEpisodeCard(
    episode: Episode,
    seasonNumber: Int,
    isWatched: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "episodeScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "episodeBorder",
    )

    // Format air date: "YYYY-MM-DD" → "May 9, 2026" / "Airs May 9, 2026"
    val todayStr = remember {
        val cal = java.util.Calendar.getInstance()
        String.format(
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
    val airDateText: String? = remember(episode.airDate) {
        val d = episode.airDate ?: return@remember null
        val parts = d.split("-")
        if (parts.size != 3) return@remember d
        val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        val month = parts[1].toIntOrNull()?.minus(1)?.let { months.getOrNull(it) } ?: return@remember d
        val formatted = "$month ${parts[2].trimStart('0')}, ${parts[0]}"
        if (d > todayStr) "Airs $formatted" else formatted
    }

    Box(
        modifier = modifier
            .width(260.dp)
            .aspectRatio(16f / 9f)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { keyEvent ->
                val kc = keyEvent.nativeKeyEvent.keyCode
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER || kc == android.view.KeyEvent.KEYCODE_ENTER) {
                    when {
                        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                            keyEvent.nativeKeyEvent.repeatCount == 0 -> {
                            longPressHandled = false
                            false
                        }
                        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                            keyEvent.nativeKeyEvent.repeatCount >= 20 && !longPressHandled -> {
                            longPressHandled = true
                            onLongClick()
                            true
                        }
                        // Consume key-up after a long press so combinedClickable doesn't also fire onClick
                        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP && longPressHandled -> {
                            longPressHandled = false
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (episode.stillUrl != null) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Charcoal),
            )
        }

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
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "E${episode.episodeNumber} ${episode.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val metaText = buildString {
                        if (episode.runtime != null) append("${episode.runtime}m")
                        if (airDateText != null) {
                            if (isNotEmpty()) append(" · ")
                            append(airDateText)
                        }
                    }
                    if (metaText.isNotEmpty()) {
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Silver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (episode.rating > 0.0) {
                        Box(
                            modifier = Modifier
                                .background(Amber.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "★ ${"%.1f".format(episode.rating)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberLight,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Watched badge
        if (isWatched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Charcoal.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.tv_watched),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberLight,
                )
            }
        }

        // Progress bar
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
                        .background(AmberLight),
                )
            }
        }
    }
}

@Composable
private fun TvDownloadSeasonChip(
    seasonNumber: Int,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "dlSeasonScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Steel.copy(alpha = 0.4f),
        label = "dlSeasonBorder",
    )
    val bgColor = if (focused) Gunmetal else Charcoal

    Row(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u2B07 S$seasonNumber",
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Snow else Silver,
            fontWeight = FontWeight.Normal,
        )
    }
}
