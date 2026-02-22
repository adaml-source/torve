package com.streamvault.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.ui.home.CatalogShelf
import com.streamvault.android.ui.theme.Yellow500
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.presentation.detail.DetailViewModel
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
    viewModel: DetailViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()

    LaunchedEffect(type, id) {
        viewModel.loadDetail(type, id)
    }

    // Navigate to player when stream is resolved
    LaunchedEffect(state.resolvedStream) {
        state.resolvedStream?.let { resolved ->
            // Prefer transcode mp4 > direct download
            val url = resolved.transcodeUrls?.mp4
                ?: resolved.transcodeUrls?.hls
                ?: resolved.url
            onPlayClick(url)
            viewModel.clearResolvedStream()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
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
                    // Backdrop with gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                    ) {
                        AsyncImage(
                            model = item.backdropUrl ?: item.posterUrl,
                            contentDescription = item.title,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.background,
                                        ),
                                    ),
                                ),
                        )

                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        // Title
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )

                        Spacer(Modifier.height(8.dp))

                        // Metadata row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            item.year?.let {
                                Text(
                                    text = it.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            item.runtime?.let { runtime ->
                                if (runtime > 0) {
                                    Text(
                                        text = " \u2022 ${FormatUtil.formatRuntime(runtime)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            item.rating?.let { rating ->
                                if (rating > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Yellow500,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "%.1f".format(rating),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Play / Find Streams button
                        if (item.imdbId != null) {
                            Button(
                                onClick = {
                                    if (settingsState.debridConnected) {
                                        viewModel.fetchStreams()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isLoadingStreams && !state.isResolving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                if (state.isLoadingStreams || state.isResolving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (state.isResolving) "Resolving..." else "Finding streams...",
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (settingsState.debridConnected) "Play" else "Connect debrid in Settings",
                                    )
                                }
                            }

                            // Stream error
                            state.streamsError?.let { error ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            state.resolveError?.let { error ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        // Tagline
                        item.tagline?.let { tagline ->
                            if (tagline.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = tagline,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                )
                            }
                        }

                        // Genres
                        if (item.genres.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item.genres.forEach { genre ->
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                genre.name,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                }
                            }
                        }

                        // Overview
                        item.overview?.let { overview ->
                            if (overview.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = overview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Episode selector for TV shows
                        if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            EpisodeSelector(
                                seasons = item.seasons,
                                onEpisodeSelected = { season, episode ->
                                    if (settingsState.debridConnected) {
                                        viewModel.fetchStreams(season = season, episode = episode)
                                    }
                                },
                            )
                        }

                        // Cast
                        if (item.cast.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Cast",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = item.cast.take(10).joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Similar
                        if (state.similar.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            CatalogShelf(
                                title = "Similar",
                                items = state.similar,
                                onItemClick = onMediaClick,
                            )
                        }
                    }
                }

                // Stream picker bottom sheet
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
            }
        }
    }
}
