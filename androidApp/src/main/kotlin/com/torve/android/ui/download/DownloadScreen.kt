package com.torve.android.ui.download

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.download.DownloadWorker
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.presentation.download.DownloadTab
import com.torve.presentation.download.DownloadViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.io.File

@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlayOffline: ((Download) -> Unit)? = null,
    viewModel: DownloadViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.onDownloadEnqueued = { downloadId ->
            DownloadWorker.enqueue(context, downloadId)
        }
        viewModel.onDownloadCancelled = { downloadId ->
            DownloadWorker.cancel(context, downloadId)
        }
        viewModel.onFileDelete = { filePath ->
            File(filePath).delete()
        }
    }

    val hasActive = state.activeDownloads.isNotEmpty()
    LaunchedEffect(hasActive) {
        if (hasActive) {
            while (true) {
                delay(2000)
                viewModel.loadDownloads()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Cinematic Header ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Graphite, Obsidian)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = Snow,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = stringResource(R.string.download_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${state.downloads.size} items · ${state.activeDownloads.size} active",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
            }
        }

        // ── Segmented Tab Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadTab.entries.forEach { tab ->
                val selected = state.selectedTab == tab
                val bgColor by animateColorAsState(
                    if (selected) Amber else Gunmetal,
                    animationSpec = tween(200),
                    label = "tab_bg",
                )
                val textColor by animateColorAsState(
                    if (selected) Obsidian else Torve.colors.textSecondary,
                    animationSpec = tween(200),
                    label = "tab_text",
                )
                val count = when (tab) {
                    DownloadTab.ALL -> state.downloads.size
                    DownloadTab.ACTIVE -> state.activeDownloads.size
                    DownloadTab.COMPLETED -> state.completedDownloads.size
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.selectTab(tab) },
                    shape = RoundedCornerShape(10.dp),
                    color = bgColor,
                ) {
                    Text(
                        text = "${tab.name.lowercase().replaceFirstChar { it.uppercase() }} ($count)",
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── Content ──
        val displayDownloads = viewModel.getDisplayDownloads()

        if (displayDownloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Torve.colors.textHint,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.download_no_downloads),
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.download_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(displayDownloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        onPause = { viewModel.pauseDownload(download.id) },
                        onResume = { viewModel.resumeDownload(download.id) },
                        onDelete = { viewModel.deleteDownload(download.id) },
                        onPlay = {
                            if (download.status == DownloadStatus.COMPLETED && download.filePath != null) {
                                onPlayOffline?.invoke(download)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    download: Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Charcoal,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster with play overlay
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (download.status == DownloadStatus.COMPLETED) {
                            Modifier.clickable(onClick = onPlay)
                        } else Modifier
                    ),
            ) {
                // Content-policy: no real artwork for locked download items
                val isLockedItem = download.title == com.torve.domain.model.LOCKED_CONTENT_TITLE
                AsyncImage(
                    model = if (isLockedItem) null else download.posterUrl,
                    contentDescription = if (isLockedItem) null else download.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (download.status == DownloadStatus.COMPLETED) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Obsidian.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Amber),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.common_play),
                                tint = Obsidian,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Torve.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                download.seasonNumber?.let { s ->
                    download.episodeNumber?.let { e ->
                        Text(
                            text = stringResource(R.string.episode_format, s, e),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textTertiary,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Status badge
                val statusColor = when (download.status) {
                    DownloadStatus.COMPLETED -> Emerald
                    DownloadStatus.FAILED -> Ruby
                    DownloadStatus.DOWNLOADING -> Amber
                    else -> Torve.colors.textTertiary
                }
                val statusText = when (download.status) {
                    DownloadStatus.PENDING -> stringResource(R.string.download_queued)
                    DownloadStatus.DOWNLOADING -> "${(download.progressPercent * 100).toInt()}%"
                    DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
                    DownloadStatus.COMPLETED -> stringResource(R.string.download_completed)
                    DownloadStatus.FAILED -> stringResource(R.string.download_failed)
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Progress bar
                if (download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.PAUSED) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { download.progressPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (download.status == DownloadStatus.PAUSED) Torve.colors.textTertiary else Amber,
                        trackColor = Gunmetal,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Actions
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (download.status) {
                    DownloadStatus.COMPLETED -> {
                        if (download.filePath != null) {
                            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.common_play),
                                    tint = Amber,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.Pause,
                                contentDescription = stringResource(R.string.common_pause),
                                tint = Torve.colors.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.PENDING -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.download_resume),
                                tint = Amber,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    else -> {}
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = Ruby.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
