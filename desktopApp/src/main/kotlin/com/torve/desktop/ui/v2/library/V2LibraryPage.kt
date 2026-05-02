package com.torve.desktop.ui.v2.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.download.DesktopDownloadManagerState
import com.torve.desktop.download.DesktopLocalMediaGroup
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.desktop.ui.v2.seeall.SeeAllRequest
import com.torve.domain.model.DownloadGroup
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.MediaItem
import com.torve.presentation.download.DownloadCatalogueUiState
import com.torve.presentation.download.DownloadUiState
import com.torve.presentation.home.HomeUiState
import java.text.DecimalFormat

private enum class LibraryViewTab(
    val label: String,
) {
    OVERVIEW("Overview"),
    STORED("Stored Media"),
    LOCAL("Local Files"),
}

@Composable
fun V2LibraryPage(
    homeState: HomeUiState,
    downloadState: DownloadUiState,
    downloadCatalogueState: DownloadCatalogueUiState,
    desktopDownloadState: DesktopDownloadManagerState,
    scrollState: ScrollState,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onDeleteDownloadGroup: (DownloadGroup) -> Unit,
    onDeleteDownloadSeason: (String, Int) -> Unit,
    onDeleteDownloadEpisode: (String) -> Unit,
    onRefreshStoredMedia: () -> Unit,
    onSeeAll: (SeeAllRequest) -> Unit = {},
    onPlayFile: (filePath: String, title: String, posterUrl: String?) -> Unit = { _, _, _ -> },
    localLibrary: com.torve.desktop.library.LocalLibraryRepository? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    var selectedTab by remember { mutableStateOf(LibraryViewTab.OVERVIEW) }
    val heroBackdropUrl = remember(homeState) {
        homeState.continueWatching.firstOrNull()?.backdropUrl
            ?: homeState.watchlistItems.firstOrNull()?.backdropUrl
            ?: homeState.recentlyWatched.firstOrNull()?.backdropUrl
    }
    val heroBackdrop = rememberCachedBitmap(heroBackdropUrl)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vpH = maxHeight

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(vpH * 0.6f).background(colors.shellBackground)) {
                heroBackdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Box(
                Modifier.fillMaxWidth().height(vpH * 0.6f).background(
                    Brush.verticalGradient(
                        0.0f to colors.shellBackground.copy(alpha = 0.3f),
                        0.4f to colors.shellBackground.copy(alpha = 0.65f),
                        1.0f to colors.shellBackground,
                    ),
                ),
            )

            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(132.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            ds("Library"),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibraryViewTab.entries.forEach { tab ->
                                TorveFilterChip(
                                    text = ds(tab.label),
                                    selected = tab == selectedTab,
                                    onClick = { selectedTab = tab },
                                )
                            }
                        }
                    }
                }

                when (selectedTab) {
                    LibraryViewTab.OVERVIEW -> LibraryOverview(
                        homeState = homeState,
                        onOpenDetail = onOpenDetail,
                        onSeeAll = onSeeAll,
                    )
                    LibraryViewTab.STORED -> StoredMediaView(
                        downloadState = downloadState,
                        downloadCatalogueState = downloadCatalogueState,
                        desktopDownloadState = desktopDownloadState,
                        onDeleteDownloadGroup = onDeleteDownloadGroup,
                        onDeleteDownloadSeason = onDeleteDownloadSeason,
                        onDeleteDownloadEpisode = onDeleteDownloadEpisode,
                        onRefreshStoredMedia = onRefreshStoredMedia,
                        onPlayFile = onPlayFile,
                    )
                    LibraryViewTab.LOCAL -> LocalLibraryView(
                        repository = localLibrary,
                        onPlayFile = onPlayFile,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LibraryOverview(
    homeState: HomeUiState,
    onOpenDetail: (MediaItem) -> Unit,
    onSeeAll: (SeeAllRequest) -> Unit,
) {
    if (homeState.continueWatching.isNotEmpty()) {
        V2Shelf(
            "Continue Watching",
            modifier = Modifier.padding(start = 72.dp),
            onSeeAll = { onSeeAll(SeeAllRequest("continue_watching", "Continue Watching")) },
        ) {
            homeState.continueWatching.take(15).forEach { wp ->
                V2PosterCard(
                    title = wp.showTitle ?: wp.title,
                    imageUrl = wp.posterUrl,
                    modifier = Modifier.width(160.dp),
                    progress = wp.progressPercent.takeIf { it > 0f },
                    onClick = {
                        onOpenDetail(
                            MediaItem(
                                id = wp.mediaId,
                                tmdbId = wp.mediaId.toIntOrNull(),
                                type = wp.mediaType,
                                title = wp.showTitle ?: wp.title,
                                posterUrl = wp.posterUrl,
                                backdropUrl = wp.backdropUrl,
                            ),
                        )
                    },
                )
            }
        }
    }

    if (homeState.watchlistItems.isNotEmpty()) {
        V2Shelf(
            "Watchlist",
            modifier = Modifier.padding(start = 72.dp),
            onSeeAll = { onSeeAll(SeeAllRequest("watchlist", "Watchlist")) },
        ) {
            homeState.watchlistItems.take(20).forEach { item ->
                V2PosterCard(
                    title = item.title,
                    imageUrl = item.posterUrl,
                    modifier = Modifier.width(160.dp),
                    year = item.year?.toString(),
                    rating = item.rating?.let { String.format("%.1f", it) },
                    backdropUrl = item.backdropUrl,
                    overview = item.overview,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }

    if (homeState.recentlyWatched.isNotEmpty()) {
        V2Shelf(
            "History",
            modifier = Modifier.padding(start = 72.dp),
            onSeeAll = { onSeeAll(SeeAllRequest("recently_watched", "Recently Watched")) },
        ) {
            homeState.recentlyWatched.take(15).forEach { item ->
                V2PosterCard(
                    title = item.title,
                    imageUrl = item.posterUrl,
                    modifier = Modifier.width(160.dp),
                    year = item.year?.toString(),
                    rating = item.rating?.let { String.format("%.1f", it) },
                    backdropUrl = item.backdropUrl,
                    overview = item.overview,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }

    if (homeState.continueWatching.isEmpty() &&
        homeState.watchlistItems.isEmpty() &&
        homeState.recentlyWatched.isEmpty() &&
        !homeState.isLoading
    ) {
        Text(
            text = ds("Your library is empty. Start watching to build your collection."),
            modifier = Modifier.padding(start = 72.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
    }
}

@Composable
private fun StoredMediaView(
    downloadState: DownloadUiState,
    downloadCatalogueState: DownloadCatalogueUiState,
    desktopDownloadState: DesktopDownloadManagerState,
    onDeleteDownloadGroup: (DownloadGroup) -> Unit,
    onDeleteDownloadSeason: (String, Int) -> Unit,
    onDeleteDownloadEpisode: (String) -> Unit,
    onRefreshStoredMedia: () -> Unit,
    onPlayFile: (filePath: String, title: String, posterUrl: String?) -> Unit,
) {
    val groups = remember(downloadCatalogueState.catalogue.sections) {
        downloadCatalogueState.catalogue.sections.flatMap { it.items }
    }

    Column(
        modifier = Modifier.padding(start = 72.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TorveSectionCard(
            title = ds("Stored Media"),
            supportingText = ds("Torve downloads stay manageable here, and extra folders are shown with their source path."),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveBadge(
                    text = "${groups.size} Torve group(s)",
                    tone = TorveBadgeTone.Accent,
                )
                TorveBadge(
                    text = "${desktopDownloadState.scannedGroups.size} local group(s)",
                    tone = TorveBadgeTone.Neutral,
                )
                TorveGhostButton(
                    text = ds("Refresh"),
                    onClick = onRefreshStoredMedia,
                )
            }
            desktopDownloadState.lastEvent?.takeIf { it.isNotBlank() }?.let { event ->
                Text(
                    text = event,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }

        if (downloadState.activeDownloads.isNotEmpty() || desktopDownloadState.isProcessing) {
            TorveSectionCard(
                title = ds("Active Downloads"),
                supportingText = ds("Downloads for shows are processed sequentially, one file at a time."),
            ) {
                if (desktopDownloadState.activeDownloadTitle != null) {
                    TorveListRow(
                        title = desktopDownloadState.activeDownloadTitle,
                        subtitle = "${(desktopDownloadState.activeProgress * 100f).toInt()}% complete",
                        trailing = {
                            TorveBadge(
                                text = ds("Running"),
                                tone = TorveBadgeTone.Accent,
                            )
                        },
                    )
                }
                downloadState.activeDownloads.forEach { download ->
                    TorveListRow(
                        title = download.title,
                        subtitle = buildString {
                            append(download.status.name.lowercase().replaceFirstChar(Char::uppercase))
                            download.fileSizeBytes?.takeIf { it > 0 }?.let {
                                append(" - ")
                                append(formatBytes(it))
                            }
                        },
                    )
                }
            }
        }

        TorveSectionCard(
            title = ds("Torve Downloads"),
            supportingText = ds("Only Torve-managed files can be deleted from here."),
        ) {
            if (groups.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No Torve downloads yet"),
                    description = ds("Download a movie from the source picker or queue episodes from a show detail page."),
                    emoji = "📥",
                )
            } else {
                groups.forEach { group ->
                    TorveListRow(
                        title = group.title,
                        subtitle = buildString {
                            append(if (group.type == DownloadGroupType.MOVIE) "Movie" else "TV Show")
                            append(" - ")
                            append(formatBytes(group.totalSizeBytes))
                            append(" - ")
                            append(group.itemCount)
                            append(if (group.itemCount == 1) " file" else " files")
                        },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TorveBadge(
                                    text = ds("Downloaded by Torve"),
                                    tone = TorveBadgeTone.Success,
                                )
                                TorveGhostButton(
                                    text = ds("Delete"),
                                    onClick = { onDeleteDownloadGroup(group) },
                                )
                            }
                        },
                    )
                    if (group.type == DownloadGroupType.SHOW) {
                        group.seasons.orEmpty().forEach { season ->
                            TorveListRow(
                                title = "Season ${season.seasonNumber}",
                                subtitle = "${season.episodes.size} episode(s) - ${formatBytes(season.totalSizeBytes)}",
                                trailing = {
                                    TorveGhostButton(
                                        text = ds("Delete Season"),
                                        onClick = { onDeleteDownloadSeason(group.mediaId, season.seasonNumber) },
                                    )
                                },
                            )
                            season.episodes.forEach { episode ->
                                val episodePath = episode.filePath
                                TorveListRow(
                                    title = "S${(episode.seasonNumber ?: 0).toString().padStart(2, '0')}E${(episode.episodeNumber ?: 0).toString().padStart(2, '0')}",
                                    subtitle = buildString {
                                        append(episodePath ?: "Torve download")
                                        if (episode.fileSizeBytes > 0) {
                                            append(" - ")
                                            append(formatBytes(episode.fileSizeBytes))
                                        }
                                    },
                                    onClick = episodePath?.takeIf { it.isNotBlank() }?.let { p ->
                                        { onPlayFile(p, "${group.title} S${season.seasonNumber}E${episode.episodeNumber ?: 0}", group.posterUrl) }
                                    },
                                    trailing = {
                                        TorveGhostButton(
                                            text = ds("Delete Episode"),
                                            onClick = { onDeleteDownloadEpisode(episode.id) },
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        group.movie?.let { movie ->
                            val moviePath = movie.filePath
                            TorveListRow(
                                title = ds("Play"),
                                subtitle = buildString {
                                    append(moviePath ?: "Torve download")
                                    if (movie.fileSizeBytes > 0) {
                                        append(" - ")
                                        append(formatBytes(movie.fileSizeBytes))
                                    }
                                },
                                onClick = moviePath?.takeIf { it.isNotBlank() }?.let { p ->
                                    { onPlayFile(p, group.title, group.posterUrl) }
                                },
                            )
                        }
                    }
                }
            }
        }

        TorveSectionCard(
            title = ds("Extra Local Folders"),
            supportingText = ds("These folders are scanned read-only. Source path stays visible so Jellyfin-linked folders are distinguishable from Torve downloads."),
        ) {
            if (desktopDownloadState.scannedGroups.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No extra folders scanned"),
                    description = ds("Add scan folders in Settings to surface existing local media here."),
                    emoji = "📁",
                )
            } else {
                desktopDownloadState.scannedGroups.forEach { group ->
                    LocalMediaGroupCard(group = group, onPlayFile = onPlayFile)
                }
            }
        }
    }
}

@Composable
private fun LocalMediaGroupCard(
    group: DesktopLocalMediaGroup,
    onPlayFile: (String, String, String?) -> Unit,
) {
    TorveListRow(
        title = group.title,
        subtitle = "${group.entries.size} item(s) - ${formatBytes(group.totalSizeBytes)} - ${group.sourcePath}",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveBadge(
                    text = if (group.isShow) "TV Group" else "Movie Group",
                    tone = TorveBadgeTone.Neutral,
                )
                TorveBadge(
                    text = group.sourceLabel,
                    tone = TorveBadgeTone.Warning,
                )
            }
        },
    )
    group.entries.forEach { entry ->
        TorveListRow(
            title = buildString {
                if (entry.seasonNumber != null && entry.episodeNumber != null) {
                    append("S${entry.seasonNumber.toString().padStart(2, '0')}E${entry.episodeNumber.toString().padStart(2, '0')} ")
                }
                append(entry.name)
            },
            subtitle = "${entry.path} - ${formatBytes(entry.sizeBytes)}",
            onClick = { onPlayFile(entry.path, entry.name, null) },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "${DecimalFormat("0.#").format(value)} ${units[unitIndex]}"
}
