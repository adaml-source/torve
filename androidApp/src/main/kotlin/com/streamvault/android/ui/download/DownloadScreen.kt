package com.streamvault.android.ui.download

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.domain.model.Download
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.presentation.download.DownloadTab
import com.streamvault.presentation.download.DownloadViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        TabRow(selectedTabIndex = state.selectedTab.ordinal) {
            DownloadTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = {
                        val count = when (tab) {
                            DownloadTab.ALL -> state.downloads.size
                            DownloadTab.ACTIVE -> state.activeDownloads.size
                            DownloadTab.COMPLETED -> state.completedDownloads.size
                        }
                        Text("${tab.name.lowercase().replaceFirstChar { it.uppercase() }} ($count)")
                    },
                )
            }
        }

        val displayDownloads = viewModel.getDisplayDownloads()

        if (displayDownloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No downloads",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(displayDownloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        onPause = { viewModel.pauseDownload(download.id) },
                        onResume = { viewModel.resumeDownload(download.id) },
                        onDelete = { viewModel.deleteDownload(download.id) },
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster
            AsyncImage(
                model = download.posterUrl,
                contentDescription = download.title,
                modifier = Modifier
                    .size(width = 60.dp, height = 90.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                download.seasonNumber?.let { s ->
                    download.episodeNumber?.let { e ->
                        Text(
                            text = "S${s}E${e}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Status text
                Text(
                    text = when (download.status) {
                        DownloadStatus.PENDING -> "Queued"
                        DownloadStatus.DOWNLOADING -> "${(download.progressPercent * 100).toInt()}%"
                        DownloadStatus.PAUSED -> "Paused"
                        DownloadStatus.COMPLETED -> "Completed"
                        DownloadStatus.FAILED -> "Failed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (download.status) {
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                // Progress bar for active downloads
                if (download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.PAUSED) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { download.progressPercent },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Actions
            Column {
                if (download.status == DownloadStatus.DOWNLOADING) {
                    IconButton(onClick = onPause) {
                        Text("⏸", style = MaterialTheme.typography.titleMedium)
                    }
                } else if (download.status == DownloadStatus.PAUSED || download.status == DownloadStatus.PENDING) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
