package com.streamvault.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.ui.components.CastAvatar
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.SectionHeader
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Graphite
import com.streamvault.android.ui.theme.HeroGradient
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.Download
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.presentation.detail.DetailViewModel
import com.streamvault.presentation.download.DownloadViewModel
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.util.FormatUtil
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    type: String,
    id: Int,
    onPlayClick: (String) -> Unit,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onPersonClick: ((Int) -> Unit)? = null,
    viewModel: DetailViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    downloadViewModel: DownloadViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var showActionSheet by remember { mutableStateOf(false) }
    var resolvedUrl by remember { mutableStateOf("") }

    LaunchedEffect(type, id) { viewModel.loadDetail(type, id) }

    LaunchedEffect(state.resolvedStream) {
        state.resolvedStream?.let { resolved ->
            val url = resolved.transcodeUrls?.mp4
                ?: resolved.transcodeUrls?.hls
                ?: resolved.url
            resolvedUrl = url
            showActionSheet = true
            viewModel.clearResolvedStream()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(48.dp),
                    color = Amber,
                )
            }

            state.error != null -> {
                Text(
                    text = state.error ?: "Error",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.mediaItem != null -> {
                val item = state.mediaItem!!

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Immersive Backdrop ──
                    // Taller than before, deeper gradient, content overlaid
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    ) {
                        AsyncImage(
                            model = item.backdropUrl ?: item.posterUrl,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(HeroGradient)),
                        )

                        // Back button
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Snow,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }

                    // ── Content Section ──
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        // Title
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.displaySmall,
                            color = Snow,
                        )

                        Spacer(Modifier.height(8.dp))

                        // Metadata row — year • runtime • rating
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item.year?.let {
                                MetadataText(it.toString())
                            }
                            item.runtime?.let { runtime ->
                                if (runtime > 0) {
                                    MetadataText("•")
                                    MetadataText(FormatUtil.formatRuntime(runtime))
                                }
                            }
                            item.rating?.let { rating ->
                                if (rating > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Rounded.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Amber,
                                    )
                                    Text(
                                        text = "%.1f".format(rating),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Amber,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        // Genre pills
                        if (item.genres.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                item.genres.forEach { genre ->
                                    GenrePill(genre.name)
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ── Action Buttons ──
                        if (item.imdbId != null) {
                            // Play button — full width, amber, prominent
                            Button(
                                onClick = {
                                    if (settingsState.debridConnected) {
                                        viewModel.fetchStreams()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                enabled = !state.isLoadingStreams && !state.isResolving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Amber,
                                    contentColor = Obsidian,
                                    disabledContainerColor = Amber.copy(alpha = 0.4f),
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                if (state.isLoadingStreams || state.isResolving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Obsidian,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (state.isResolving) "Resolving..." else "Finding streams...",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (settingsState.debridConnected) "Play" else "Connect cloud service in Settings",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            // Secondary actions row
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Watchlist button
                                FilledTonalButton(
                                    onClick = { /* Toggle watchlist */ },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Graphite,
                                        contentColor = Snow,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Rounded.BookmarkBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Watchlist", style = MaterialTheme.typography.labelLarge)
                                }

                                // Mark watched (only if Trakt connected)
                                if (settingsState.traktConnected) {
                                    FilledTonalButton(
                                        onClick = {
                                            if (state.isMarkedWatched) viewModel.markUnwatched()
                                            else viewModel.markWatched()
                                        },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Graphite,
                                            contentColor = Snow,
                                        ),
                                    ) {
                                        Icon(
                                            if (state.isMarkedWatched) Icons.Rounded.VisibilityOff
                                            else Icons.Rounded.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (state.isMarkedWatched) "Unwatched" else "Watched",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }

                            // Errors
                            state.streamsError?.let { error ->
                                ErrorMessage(error, Modifier.padding(top = 8.dp))
                            }
                            state.resolveError?.let { error ->
                                ErrorMessage(error, Modifier.padding(top = 8.dp))
                            }
                        }

                        // Tagline
                        item.tagline?.let { tagline ->
                            if (tagline.isNotBlank()) {
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = "\"$tagline\"",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Amber.copy(alpha = 0.8f),
                                    fontStyle = FontStyle.Italic,
                                )
                            }
                        }

                        // Overview
                        item.overview?.let { overview ->
                            if (overview.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = overview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StreamVault.colors.textSecondary,
                                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                                )
                            }
                        }

                        // ── Director ──
                        item.director?.let { director ->
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Director: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StreamVault.colors.textTertiary,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = director,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Amber,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = if (item.directorId != null && onPersonClick != null) {
                                        Modifier.clickable { onPersonClick(item.directorId!!) }
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }

                        // ── Episode Selector (TV Shows) ──
                        if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            EpisodeSelector(
                                seasons = item.seasons,
                                selectedSeason = state.selectedSeason,
                                seasonDetail = state.seasonDetail,
                                isLoadingSeasonDetail = state.isLoadingSeasonDetail,
                                onSeasonSelected = { seasonNum ->
                                    item.tmdbId?.let { tvId ->
                                        viewModel.loadSeasonDetail(tvId, seasonNum)
                                    }
                                },
                                onEpisodePlay = { season, episode ->
                                    if (settingsState.debridConnected) {
                                        viewModel.fetchStreams(season = season, episode = episode)
                                    }
                                },
                                onEpisodeDownload = { season, episode ->
                                    if (settingsState.debridConnected) {
                                        viewModel.fetchStreams(season = season, episode = episode)
                                    }
                                },
                                onDownloadSeason = { season ->
                                    if (settingsState.debridConnected) {
                                        // Fetch streams for episode 1 of the season as entry point
                                        viewModel.fetchStreams(season = season, episode = 1)
                                    }
                                },
                                onDownloadAll = {
                                    if (settingsState.debridConnected) {
                                        // Fetch streams for S01E01 as entry point
                                        viewModel.fetchStreams(season = 1, episode = 1)
                                    }
                                },
                            )
                        }

                        // ── Cast ──
                        if (item.cast.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(
                                title = "Cast",
                                modifier = Modifier.padding(horizontal = 0.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(item.cast.take(15)) { castMember ->
                                    CastAvatar(
                                        name = castMember.name,
                                        character = castMember.character,
                                        profileUrl = castMember.profileUrl,
                                        onClick = {
                                            onPersonClick?.invoke(castMember.id)
                                        },
                                    )
                                }
                            }
                        }

                        // ── Similar ──
                        if (state.similar.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(
                                title = "More Like This",
                                modifier = Modifier.padding(horizontal = 0.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(state.similar, key = { it.id }) { similar ->
                                    PosterCard(
                                        item = similar,
                                        size = CardSize.SMALL,
                                        onClick = { onMediaClick(similar) },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }

                // Stream picker
                if (state.showStreamPicker && state.streams.isNotEmpty()) {
                    StreamPickerSheet(
                        streams = state.streams,
                        isResolving = state.isResolving,
                        onStreamSelected = { stream ->
                            viewModel.resolveStream(
                                stream = stream,
                                provider = settingsViewModel.getDebridProvider(),
                                apiKey = settingsViewModel.getDebridApiKey(),
                            )
                        },
                        onDismiss = { viewModel.dismissStreamPicker() },
                    )
                }

                // Action sheet after resolution
                if (showActionSheet && resolvedUrl.isNotBlank()) {
                    val item = state.mediaItem
                    val ctxSeason = state.streamContextSeason
                    val ctxEpisode = state.streamContextEpisode
                    val dlTitle = if (ctxSeason != null && ctxEpisode != null) {
                        "${item?.title ?: ""} S${ctxSeason.toString().padStart(2, '0')}E${ctxEpisode.toString().padStart(2, '0')}"
                    } else {
                        item?.title ?: ""
                    }
                    StreamActionSheet(
                        url = resolvedUrl,
                        title = dlTitle,
                        onPlayInApp = { onPlayClick(resolvedUrl) },
                        onDownload = {
                            if (item != null) {
                                downloadViewModel.enqueueDownload(
                                    Download(
                                        id = "${item.tmdbId}_${ctxSeason ?: 0}_${ctxEpisode ?: 0}_${System.currentTimeMillis()}",
                                        mediaId = item.tmdbId.toString(),
                                        mediaType = item.type,
                                        title = dlTitle,
                                        posterUrl = item.posterUrl,
                                        streamUrl = resolvedUrl,
                                        filePath = null,
                                        fileSizeBytes = null,
                                        status = DownloadStatus.PENDING,
                                        seasonNumber = ctxSeason,
                                        episodeNumber = ctxEpisode,
                                        createdAt = System.currentTimeMillis(),
                                        completedAt = null,
                                    ),
                                )
                            }
                        },
                        onDismiss = {
                            showActionSheet = false
                            resolvedUrl = ""
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = StreamVault.colors.textTertiary,
    )
}

@Composable
private fun GenrePill(name: String) {
    Box(
        modifier = Modifier
            .background(
                Graphite,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = StreamVault.colors.textSecondary,
        )
    }
}

@Composable
private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}
