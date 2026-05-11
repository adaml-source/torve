package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.Channel
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

internal data class TvVodSeriesDetailsArgs(
    val channel: Channel,
    val item: MediaItem,
)

@Composable
internal fun TvVodSeriesDetailsScreen(
    args: TvVodSeriesDetailsArgs,
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onEpisodePlay: (Channel, MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val channelRepo: ChannelRepository = koinInject()
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val watchHistoryRepo: WatchHistoryRepository = koinInject()
    val firstEpisodeRequester = remember { FocusRequester() }
    var episodes by remember(args.channel.url) { mutableStateOf<List<Channel>>(emptyList()) }
    var progressByEpisodeId by remember(args.channel.url) { mutableStateOf<Map<String, WatchProgress>>(emptyMap()) }
    var watchedEpisodeKeys by remember(args.channel.url) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(args.channel.url) { mutableStateOf(true) }
    var error by remember(args.channel.url) { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(args.channel.url) {
        loading = true
        error = null
        episodes = withContext(Dispatchers.IO) {
            channelRepo.getVodSeriesEpisodes(args.channel)
        }
        progressByEpisodeId = withContext(Dispatchers.IO) {
            episodes.mapNotNull { episode ->
                val id = vodEpisodeMediaId(args.item, episode)
                watchProgressRepo.getProgress(id)?.let { id to it }
            }.toMap()
        }
        watchedEpisodeKeys = withContext(Dispatchers.IO) {
            val showHistoryIds = buildSet {
                add(args.item.id)
                args.item.tmdbId?.let { add(it.toString()) }
                args.item.imdbId?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val episodeIds = episodes.associate { episode ->
                vodEpisodeMediaId(args.item, episode) to episode.watchKey()
            }
            watchHistoryRepo.getAll()
                .mapNotNull { entry ->
                    val exactEpisodeKey = episodeIds[entry.mediaId]
                    if (exactEpisodeKey != null) {
                        exactEpisodeKey
                    } else if (entry.mediaId in showHistoryIds && entry.seasonNumber != null && entry.episodeNumber != null) {
                        episodeWatchKey(entry.seasonNumber!!, entry.episodeNumber!!)
                    } else {
                        null
                    }
                }
                .toSet()
        }
        error = if (episodes.isEmpty()) "No playable episodes found for this show" else null
        loading = false
    }

    LaunchedEffect(episodes, loading) {
        if (!loading && episodes.isNotEmpty()) {
            onFirstContentRequester(firstEpisodeRequester)
            kotlinx.coroutines.delay(100)
            runCatching { firstEpisodeRequester.requestFocus() }
        }
    }

    val seasons = remember(episodes) {
        episodes
            .mapNotNull { it.kodiProps["vod_season_number"]?.toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1) }
    }
    var selectedSeason by remember(args.channel.url, seasons) { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    val visibleEpisodes = remember(episodes, selectedSeason) {
        val seasonEpisodes = episodes.filter {
            (it.kodiProps["vod_season_number"]?.toIntOrNull() ?: 1) == selectedSeason
        }
        if (seasonEpisodes.isNotEmpty()) seasonEpisodes else episodes
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
    ) {
        AsyncImage(
            model = args.item.backdropUrl ?: args.channel.kodiProps["vod_backdrop"] ?: args.item.posterUrl ?: args.channel.tvgLogo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.98f),
                            Obsidian.copy(alpha = 0.9f),
                            Obsidian.copy(alpha = 0.76f),
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 58.dp, top = 42.dp, end = 58.dp, bottom = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(330.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                AsyncImage(
                    model = args.item.posterUrl ?: args.channel.tvgLogo,
                    contentDescription = args.item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(210.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Charcoal),
                )
                Text(
                    text = args.item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                args.item.overview
                    ?.takeIf { it.isNotBlank() }
                    ?.let { overview ->
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Silver,
                            maxLines = 7,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    itemsIndexed(seasons, key = { _, season -> "vod_details_season_$season" }) { _, season ->
                        TvVodSeriesSeasonChip(
                            season = season,
                            selected = season == selectedSeason,
                            onFocused = {},
                            onClick = { selectedSeason = season },
                        )
                    }
                }

                when {
                    loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Amber)
                        }
                    }

                    error != null -> {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            color = Silver,
                        )
                    }

                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 48.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                visibleEpisodes,
                                key = { index, episode -> episode.kodiProps["vod_episode_id"] ?: "${episode.url}_$index" },
                            ) { index, episode ->
                                val episodeItem = args.item.toVodEpisodeMediaItem(episode)
                                val progress = progressByEpisodeId[episodeItem.id]
                                val watched = episode.watchKey() in watchedEpisodeKeys
                                val episodeRequester = if (index == 0) {
                                    firstEpisodeRequester
                                } else {
                                    remember(episodeItem.id) { FocusRequester() }
                                }
                                TvVodSeriesEpisodeRow(
                                    episode = episode,
                                    progress = progress,
                                    watchedFromHistory = watched,
                                    modifier = Modifier.focusRequester(episodeRequester),
                                    onFocused = { onContentFocused(episodeRequester) },
                                    onClick = { onEpisodePlay(episode, episodeItem) },
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
private fun TvVodSeriesSeasonChip(
    season: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            selected -> Amber
            else -> Steel.copy(alpha = 0.28f)
        },
        label = "vodSeriesSeasonBorder",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Amber.copy(alpha = 0.16f) else Charcoal.copy(alpha = 0.72f))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Season $season",
            style = MaterialTheme.typography.titleSmall,
            color = if (focused || selected) Snow else Silver,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TvVodSeriesEpisodeRow(
    episode: Channel,
    progress: WatchProgress?,
    watchedFromHistory: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val episodeNumber = episode.kodiProps["vod_episode_number"]?.toIntOrNull()
    val title = episode.tvgName?.takeIf { it.isNotBlank() } ?: episode.name
    val plot = episode.kodiProps["vod_episode_plot"]
    val rating = episode.kodiProps["vod_episode_rating"]?.toDoubleOrNull()
    val durationMinutes = episode.duration.takeIf { it > 0 }?.let { it / 60 }
    val watched = watchedFromHistory || progress?.progressPercent?.let { it >= 0.85f } == true
    val inProgress = progress?.progressPercent?.let { it > 0.02f && it < 0.85f } == true

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Graphite else Charcoal.copy(alpha = 0.76f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AmberLight else Steel.copy(alpha = 0.22f),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(Obsidian),
        ) {
            AsyncImage(
                model = episode.tvgLogo,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            episodeNumber?.let { number ->
                Text(
                    text = "E$number",
                    color = Snow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Obsidian.copy(alpha = 0.78f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (watched) {
                    Text(
                        stringResource(R.string.tv_watched),
                        color = AmberLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else if (inProgress) {
                    Text("${((progress?.progressPercent ?: 0f) * 100).toInt()}%", color = AmberLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            val metadata = buildList {
                durationMinutes?.takeIf { it > 0 }?.let { add("${it}m") }
                rating?.takeIf { it > 0.0 }?.let {
                    add(stringResource(R.string.tv_vod_rating_value, "%.1f".format(it)))
                }
            }.joinToString(" | ")
            if (metadata.isNotBlank()) {
                Text(text = metadata, style = MaterialTheme.typography.bodySmall, color = AmberLight)
            }
            plot?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun MediaItem.toVodEpisodeMediaItem(episode: Channel): MediaItem {
    val episodeTitle = episode.tvgName?.takeIf { it.isNotBlank() } ?: episode.name
    val episodeRating = episode.kodiProps["vod_episode_rating"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
    return copy(
        id = vodEpisodeMediaId(this, episode),
        type = MediaType.SERIES,
        title = title,
        posterUrl = episode.tvgLogo ?: posterUrl,
        backdropUrl = backdropUrl,
        seasons = seasons,
        overview = episode.kodiProps["vod_episode_plot"]?.takeIf { it.isNotBlank() } ?: overview,
        rating = episodeRating ?: rating,
    ).copy(
        // The player route carries the display episode title separately, but
        // keeping a concrete title here makes local progress rows readable.
        title = "$title - $episodeTitle",
    )
}

private fun vodEpisodeMediaId(show: MediaItem, episode: Channel): String {
    val episodeId = episode.kodiProps["vod_episode_id"]?.takeIf { it.isNotBlank() }
        ?: episode.url.hashCode().toString()
    val episodeKey = episodeId.hashCode().toUInt().toString(16)
    return when {
        show.tmdbId != null -> "tmdb:${show.tmdbId}:vod_episode:ep$episodeKey"
        !show.imdbId.isNullOrBlank() -> "imdb:${show.imdbId}:vod_episode:ep$episodeKey"
        else -> "vod_series:${show.id.hashCode().toUInt().toString(16)}:episode:ep$episodeKey"
    }
}

private fun Channel.watchKey(): String {
    val season = kodiProps["vod_season_number"]?.toIntOrNull() ?: 1
    val episode = kodiProps["vod_episode_number"]?.toIntOrNull()
        ?: kodiProps["vod_episode_id"]?.toIntOrNull()
        ?: url.hashCode()
    return episodeWatchKey(season, episode)
}

private fun episodeWatchKey(season: Int, episode: Int): String = "s${season}e${episode}"
