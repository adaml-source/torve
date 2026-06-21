package com.torve.android.ui.download

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.download.DownloadWorker
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Amethyst
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Sapphire
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Smoke
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.MultiRatingPills
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.download.DownloadCatalogueBuilder
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.CatalogueFilterType
import com.torve.domain.model.CatalogueGrouping
import com.torve.domain.model.CatalogueSection
import com.torve.domain.model.CatalogueSortBy
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.CatalogueState
import com.torve.domain.model.CatalogueViewMode
import com.torve.domain.model.CatalogueWatchFilter
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadCataloguePrefs
import com.torve.domain.model.DownloadGroup
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.DownloadedItem
import com.torve.presentation.download.DownloadCatalogueViewModel
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Main Screen
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadCatalogueScreen(
    onBack: () -> Unit,
    onPlayOffline: (DownloadedItem) -> Unit,
    onShowDetail: (String) -> Unit,
    catalogueVM: DownloadCatalogueViewModel = koinInject(),
    downloadVM: DownloadViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    ratingsEnricher: RatingsEnricher = koinInject(),
) {
    val catalogueState by catalogueVM.state.collectAsState()
    val downloadState by downloadVM.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val context = LocalContext.current
    val state = catalogueState.catalogue
    val prefs = catalogueState.prefs
    var ratingsByMediaId by remember { mutableStateOf<Map<String, MediaRatings>>(emptyMap()) }

    LaunchedEffect(state.sections, state.specialSections, settingsState.mdblistApiKey) {
        val apiKey = settingsState.mdblistApiKey
        if (apiKey.isBlank()) {
            ratingsByMediaId = emptyMap()
            return@LaunchedEffect
        }
        val groups = buildList {
            state.specialSections.forEach { addAll(it.items) }
            state.sections.forEach { addAll(it.items) }
        }.distinctBy { it.mediaId }

        val mediaItems = groups.mapNotNull { group ->
            val tmdbId = group.mediaId.toIntOrNull() ?: return@mapNotNull null
            val type = if (group.type == DownloadGroupType.MOVIE) MediaType.MOVIE else MediaType.SERIES
            MediaItem(
                id = group.mediaId,
                tmdbId = tmdbId,
                type = type,
                title = group.title,
                posterUrl = group.posterUrl,
                backdropUrl = group.backdropUrl,
            )
        }
        val enriched = withContext(Dispatchers.Default) { ratingsEnricher.enrichList(mediaItems, apiKey) }
        ratingsByMediaId = enriched.associate { it.id to (it.ratings ?: MediaRatings()) }
    }

    // Wire platform callbacks
    LaunchedEffect(catalogueVM, downloadVM) {
        downloadVM.onDownloadEnqueued = { DownloadWorker.enqueue(context, it) }
        downloadVM.onDownloadCancelled = { DownloadWorker.cancel(context, it) }
        downloadVM.onFileDelete = { File(it).delete() }
        catalogueVM.onFileDelete = { File(it).delete() }
        catalogueVM.onDownloadCancelled = { DownloadWorker.cancel(context, it) }
    }

    // Auto-refresh for active downloads
    val hasActive = downloadState.activeDownloads.isNotEmpty()
    LaunchedEffect(hasActive) {
        if (hasActive) {
            while (true) {
                delay(2000)
                downloadVM.loadDownloads()
                catalogueVM.loadCatalogue()
            }
        }
    }

    var showSortSheet by remember { mutableStateOf(false) }
    var showGroupSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var contextMenuGroup by remember { mutableStateOf<DownloadGroup?>(null) }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Top Bar ──
        CatalogueTopBar(
            state = state,
            prefs = prefs,
            onBack = onBack,
            onViewModeChange = { mode -> catalogueVM.updatePrefs { it.copy(viewMode = mode) } },
            onSortClick = { showSortSheet = true },
            onGroupClick = { showGroupSheet = true },
            onSettingsClick = { showSettingsSheet = true },
        )

        if (state.isEmpty && catalogueState.activeDownloads.isEmpty()) {
            EmptyDownloadsState()
        } else {
            // Active downloads strip
            if (catalogueState.activeDownloads.isNotEmpty()) {
                ActiveDownloadsStrip(
                    downloads = catalogueState.activeDownloads,
                    downloadVM = downloadVM,
                )
            }

            // Storage summary
            if (prefs.showStorageInfo && !state.isEmpty) {
                StorageSummaryBar(state)
            }

            // Filter chips
            if (!state.isEmpty) {
                FilterChipsRow(
                    prefs = prefs,
                    availableGenres = state.availableGenres,
                    availableQualities = state.availableQualities,
                    onUpdate = { newPrefs -> catalogueVM.updatePrefs { newPrefs } },
                )
            }

            // Main content by view mode
            when (prefs.viewMode) {
                CatalogueViewMode.GRID -> GridCatalogue(state, prefs, ratingsByMediaId, onPlayOffline, onShowDetail) { contextMenuGroup = it }
                CatalogueViewMode.LIST -> ListCatalogue(state, prefs, onPlayOffline, onShowDetail) { contextMenuGroup = it }
                CatalogueViewMode.SHELF -> ShelfCatalogue(state, prefs, ratingsByMediaId, onPlayOffline, onShowDetail) { contextMenuGroup = it }
                CatalogueViewMode.COVER -> CoverCatalogue(state, prefs, ratingsByMediaId, onPlayOffline, onShowDetail) { contextMenuGroup = it }
            }
        }
    }
    }

    // Bottom sheets
    if (showSortSheet) {
        SortBottomSheet(prefs, { catalogueVM.updatePrefs { _ -> it } }) { showSortSheet = false }
    }
    if (showGroupSheet) {
        GroupBottomSheet(prefs, { catalogueVM.updatePrefs { _ -> it } }) { showGroupSheet = false }
    }
    if (showSettingsSheet) {
        CatalogueSettingsSheet(prefs, { catalogueVM.updatePrefs { _ -> it } }, catalogueVM) { showSettingsSheet = false }
    }
    contextMenuGroup?.let { group ->
        DownloadContextMenu(
            group = group,
            onDismiss = { contextMenuGroup = null },
            onDelete = { catalogueVM.deleteGroup(group); contextMenuGroup = null },
            onDetail = { onShowDetail(group.mediaId); contextMenuGroup = null },
            onPlay = {
                when (group.type) {
                    DownloadGroupType.MOVIE -> group.movie?.let { onPlayOffline(it) }
                    DownloadGroupType.SHOW -> onShowDetail(group.mediaId)
                }
                contextMenuGroup = null
            },
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Top Bar
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CatalogueTopBar(
    state: CatalogueState,
    prefs: DownloadCataloguePrefs,
    onBack: () -> Unit,
    onViewModeChange: (CatalogueViewMode) -> Unit,
    onSortClick: () -> Unit,
    onGroupClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Graphite, Obsidian)))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = Snow)
            }
            Text(
                stringResource(R.string.download_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.weight(1f),
            )
            if (!state.isEmpty) {
                Text(
                    stringResource(R.string.download_items_count, state.totalItemCount),
                    color = Silver,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        if (!state.isEmpty) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Obsidian)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // View mode toggle
                Row(
                    Modifier
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                        .padding(2.dp),
                ) {
                    CatalogueViewMode.entries.forEach { mode ->
                        val selected = prefs.viewMode == mode
                        val icon = when (mode) {
                            CatalogueViewMode.GRID -> Icons.Default.GridView
                            CatalogueViewMode.LIST -> Icons.AutoMirrored.Default.ViewList
                            CatalogueViewMode.SHELF -> Icons.Default.ViewCarousel
                            CatalogueViewMode.COVER -> Icons.Default.ViewAgenda
                        }
                        IconButton(
                            onClick = { onViewModeChange(mode) },
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (selected) AmberSubtle else Color.Transparent,
                                    RoundedCornerShape(6.dp),
                                ),
                        ) {
                            Icon(icon, mode.label, tint = if (selected) Amber else Silver, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                CompactActionChip(Icons.Default.SwapVert, prefs.sortBy.label.take(12), onSortClick)
                CompactActionChip(Icons.Default.FolderOpen, prefs.groupingMode.label.take(10), onGroupClick)

                IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, stringResource(R.string.settings_title), tint = Silver, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Storage Summary Bar
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun StorageSummaryBar(state: CatalogueState) {
    val totalSize = formatFileSize(state.totalSizeBytes)
    val statFs = remember { StatFs(Environment.getDataDirectory().path) }
    val availableBytes = statFs.availableBytes
    val totalBytes = statFs.totalBytes
    val usedPercent = if (totalBytes > 0) (state.totalSizeBytes.toFloat() / totalBytes * 100).toInt() else 0

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.download_storage_counts, state.movieCount, state.showCount, state.episodeCount),
                    color = Silver, fontSize = 11.sp,
                )
                Text(totalSize, color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (totalBytes > 0) state.totalSizeBytes.toFloat() / totalBytes else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Amber,
                trackColor = Color(0xFF2A2A3E),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.download_storage_free_used, formatFileSize(availableBytes), usedPercent),
                color = Smoke, fontSize = 10.sp,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Active Downloads Strip
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun ActiveDownloadsStrip(
    downloads: List<Download>,
    downloadVM: DownloadViewModel,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A2E))
            .padding(vertical = 8.dp),
    ) {
        Text(
            stringResource(R.string.download_active_downloads, downloads.size),
            color = Amber,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(downloads, key = { it.id }) { download ->
                Card(
                    modifier = Modifier.width(160.dp),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            download.title,
                            color = Snow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        val statusColor = when (download.status) {
                            DownloadStatus.DOWNLOADING -> Amber
                            DownloadStatus.PAUSED -> Silver
                            else -> Smoke
                        }
                        Text(
                            when (download.status) {
                                DownloadStatus.DOWNLOADING -> "${(download.progressPercent * 100).toInt()}%"
                                DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
                                DownloadStatus.PENDING -> stringResource(R.string.download_queued)
                                else -> download.status.name
                            },
                            color = statusColor,
                            fontSize = 10.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { download.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = statusColor,
                            trackColor = Gunmetal,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            if (download.status == DownloadStatus.DOWNLOADING) {
                                IconButton(onClick = { downloadVM.pauseDownload(download.id) }, Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.common_pause), tint = Silver, modifier = Modifier.size(14.dp))
                                }
                            } else if (download.status == DownloadStatus.PAUSED || download.status == DownloadStatus.PENDING) {
                                IconButton(onClick = { downloadVM.resumeDownload(download.id) }, Modifier.size(24.dp)) {
                                    Icon(Icons.Default.PlayArrow, stringResource(R.string.download_resume), tint = Amber, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Filter Chips Row
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun FilterChipsRow(
    prefs: DownloadCataloguePrefs,
    availableGenres: List<String>,
    availableQualities: List<String>,
    onUpdate: (DownloadCataloguePrefs) -> Unit,
) {
    var showGenreDropdown by remember { mutableStateOf(false) }
    var showQualityDropdown by remember { mutableStateOf(false) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        // Type filter chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CatalogueFilterType.entries.forEach { type ->
                    FilterChip(
                        selected = prefs.filterType == type,
                        onClick = { onUpdate(prefs.copy(filterType = type)) },
                        label = { Text(type.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                    )
                }
            }
        }

        // Watch state filter chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CatalogueWatchFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = prefs.filterWatchState == filter,
                        onClick = { onUpdate(prefs.copy(filterWatchState = filter)) },
                        label = { Text(filter.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                    )
                }
            }
        }

        // Genre dropdown
        if (availableGenres.isNotEmpty()) {
            item {
                Box {
                    FilterChip(
                        selected = prefs.filterGenre != null,
                        onClick = { showGenreDropdown = true },
                        label = { Text(prefs.filterGenre ?: stringResource(R.string.download_filter_genre), fontSize = 11.sp) },
                        trailingIcon = {
                            if (prefs.filterGenre != null) {
                                Icon(
                                    Icons.Default.Close, stringResource(R.string.common_clear),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onUpdate(prefs.copy(filterGenre = null)) },
                                )
                            }
                        },
                    )
                    DropdownMenu(expanded = showGenreDropdown, onDismissRequest = { showGenreDropdown = false }) {
                        availableGenres.forEach { genre ->
                            DropdownMenuItem(
                                text = { Text(genre) },
                                onClick = {
                                    onUpdate(prefs.copy(filterGenre = genre))
                                    showGenreDropdown = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Quality dropdown
        if (availableQualities.isNotEmpty()) {
            item {
                Box {
                    FilterChip(
                        selected = prefs.filterQuality != null,
                        onClick = { showQualityDropdown = true },
                        label = { Text(prefs.filterQuality ?: stringResource(R.string.download_filter_quality), fontSize = 11.sp) },
                        trailingIcon = {
                            if (prefs.filterQuality != null) {
                                Icon(
                                    Icons.Default.Close, stringResource(R.string.common_clear),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onUpdate(prefs.copy(filterQuality = null)) },
                                )
                            }
                        },
                    )
                    DropdownMenu(expanded = showQualityDropdown, onDismissRequest = { showQualityDropdown = false }) {
                        availableQualities.forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q) },
                                onClick = {
                                    onUpdate(prefs.copy(filterQuality = q))
                                    showQualityDropdown = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Grid View
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun GridCatalogue(
    state: CatalogueState,
    prefs: DownloadCataloguePrefs,
    ratingsByMediaId: Map<String, MediaRatings>,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    val columns = if (prefs.gridColumns > 0) GridCells.Fixed(prefs.gridColumnsManual)
    else GridCells.Adaptive(minSize = 120.dp)

    LazyVerticalGrid(
        columns = columns,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Special sections
        state.specialSections.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    section.title, color = Amber, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(section.items, key = { "sp_${section.title}_${it.mediaId}" }) { group ->
                DownloadGroupCard(group, prefs, ratingsByMediaId, onPlay, onDetail, onLongPress)
            }
        }

        // Main sections
        state.sections.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    section.title, color = Snow, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
            items(section.items, key = { "${section.title}_${it.mediaId}" }) { group ->
                DownloadGroupCard(group, prefs, ratingsByMediaId, onPlay, onDetail, onLongPress)
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// List View
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun ListCatalogue(
    state: CatalogueState,
    prefs: DownloadCataloguePrefs,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    val allDownloadsTitle = stringResource(R.string.download_all_downloads)
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.sections.forEach { section ->
            if (state.sections.size > 1 || section.title != allDownloadsTitle) {
                item {
                    Text(
                        section.title, color = Amber, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(section.items, key = { "list_${section.title}_${it.mediaId}" }) { group ->
                DownloadGroupListRow(group, prefs, onPlay, onDetail, onLongPress)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadGroupListRow(
    group: DownloadGroup,
    prefs: DownloadCataloguePrefs,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when (group.type) {
                        DownloadGroupType.MOVIE -> group.movie?.let { onPlay(it) }
                        DownloadGroupType.SHOW -> onDetail(group.mediaId)
                    }
                },
                onLongClick = { onLongPress(group) },
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = group.posterUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(50.dp, 75.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, color = Snow, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.year?.let { Text("$it", color = Silver, fontSize = 11.sp) }
                    if (group.type == DownloadGroupType.SHOW) {
                        Text(stringResource(R.string.download_episodes_short, group.itemCount), color = Silver, fontSize = 11.sp)
                    }
                    if (prefs.showFileSize) {
                        Text(formatFileSize(group.totalSizeBytes), color = Silver, fontSize = 11.sp)
                    }
                }
                if (prefs.showQualityBadges) {
                    val res = getGroupResolution(group)
                    res?.let {
                        Text(it, color = qualityColor(it), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (prefs.showWatchProgress && group.overallProgress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { group.overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp)),
                        color = Amber,
                        trackColor = Color(0xFF2A2A3E),
                    )
                }
            }
            Icon(
                if (group.type == DownloadGroupType.MOVIE) Icons.Default.Movie else Icons.Default.Tv,
                null, tint = Silver, modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Shelf View
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun ShelfCatalogue(
    state: CatalogueState,
    prefs: DownloadCataloguePrefs,
    ratingsByMediaId: Map<String, MediaRatings>,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        state.specialSections.forEach { section ->
            item { ShelfSection(section, prefs, ratingsByMediaId, onPlay, onDetail, onLongPress) }
        }
        state.sections.forEach { section ->
            item { ShelfSection(section, prefs, ratingsByMediaId, onPlay, onDetail, onLongPress) }
        }
    }
}

@Composable
private fun ShelfSection(
    section: CatalogueSection,
    prefs: DownloadCataloguePrefs,
    ratingsByMediaId: Map<String, MediaRatings>,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(section.title, color = Snow, fontWeight = FontWeight.Bold)
            Text("${section.items.size}", color = Silver, fontSize = 12.sp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.items, key = { "shelf_${section.title}_${it.mediaId}" }) { group ->
                DownloadGroupCard(group, prefs, ratingsByMediaId, onPlay, onDetail, onLongPress)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Cover View
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CoverCatalogue(
    state: CatalogueState,
    prefs: DownloadCataloguePrefs,
    ratingsByMediaId: Map<String, MediaRatings>,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.sections.forEach { section ->
            item {
                Text(
                    section.title, color = Snow, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(section.items, key = { "cover_${section.title}_${it.mediaId}" }) { group ->
                CoverCard(group, prefs, ratingsByMediaId, onPlay, onDetail, onLongPress)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverCard(
    group: DownloadGroup,
    prefs: DownloadCataloguePrefs,
    ratingsByMediaId: Map<String, MediaRatings>,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .combinedClickable(
                onClick = {
                    when (group.type) {
                        DownloadGroupType.MOVIE -> group.movie?.let { onPlay(it) }
                        DownloadGroupType.SHOW -> onDetail(group.mediaId)
                    }
                },
                onLongClick = { onLongPress(group) },
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = group.backdropUrl ?: group.posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp)
                    .fillMaxWidth(0.6f),
            ) {
                Text(
                    group.title, color = Snow, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.year?.let { Text("$it", color = Silver, fontSize = 13.sp) }
                    if (group.type == DownloadGroupType.SHOW) {
                        Text(stringResource(R.string.download_episodes_count, group.itemCount), color = Silver, fontSize = 13.sp)
                    }
                    Text(formatFileSize(group.totalSizeBytes), color = Amber, fontSize = 13.sp)
                }
                if (prefs.showWatchProgress && group.overallProgress > 0f) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { group.overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Amber,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            }

            // Quality badge
            if (prefs.showQualityBadges) {
                val res = getGroupResolution(group)
                res?.let {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(qualityColor(it).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            ratingsByMediaId[group.mediaId]?.let { ratings ->
                MultiRatingPills(
                    ratings = ratings,
                    prefs = LocalCardStyle.current.ratingPrefs,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Download Group Card (for Grid + Shelf views)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadGroupCard(
    group: DownloadGroup,
    prefs: DownloadCataloguePrefs,
    ratingsByMediaId: Map<String, MediaRatings>,
    onPlay: (DownloadedItem) -> Unit,
    onDetail: (String) -> Unit,
    onLongPress: (DownloadGroup) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .combinedClickable(
                onClick = {
                    when (group.type) {
                        DownloadGroupType.MOVIE -> group.movie?.let { onPlay(it) }
                        DownloadGroupType.SHOW -> onDetail(group.mediaId)
                    }
                },
                onLongClick = { onLongPress(group) },
            ),
    ) {
        Box(
            Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = group.posterUrl,
                contentDescription = group.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            // Episode count badge (shows)
            if (group.type == DownloadGroupType.SHOW && prefs.showEpisodeCount) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xFF0D0D1A).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.download_episodes_short_caps, group.itemCount), color = Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // File size badge
            if (prefs.showFileSize) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xFF0D0D1A).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(formatFileSize(group.totalSizeBytes), color = Silver, fontSize = 9.sp)
                }
            }

            // Quality badge
            if (prefs.showQualityBadges) {
                val resolution = getGroupResolution(group)
                resolution?.let { res ->
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(qualityColor(res).copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(res, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            ratingsByMediaId[group.mediaId]?.let { ratings ->
                MultiRatingPills(
                    ratings = ratings,
                    prefs = LocalCardStyle.current.ratingPrefs,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }

            // Watch progress bar
            if (prefs.showWatchProgress && group.overallProgress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(group.overallProgress)
                            .background(Amber),
                    )
                }
            }

            // Watched overlay
            if (group.watchedCount == group.totalCount && group.totalCount > 0) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.detail_watched),
                        tint = Emerald.copy(alpha = 0.85f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Show type icon
            if (group.type == DownloadGroupType.SHOW) {
                Icon(
                    Icons.Default.Tv, contentDescription = stringResource(R.string.download_tv_show),
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(14.dp),
                )
            }
        }

        // Title and info below
        Spacer(Modifier.height(6.dp))
        Text(
            group.title, color = Snow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (group.year != null) {
            Text("${group.year}", color = Silver, fontSize = 10.sp)
        }
        if (group.type == DownloadGroupType.SHOW) {
            Text(
                stringResource(R.string.download_watched_count, group.watchedCount, group.totalCount),
                color = if (group.watchedCount == group.totalCount) Emerald else Silver,
                fontSize = 10.sp,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Context Menu
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadContextMenu(
    group: DownloadGroup,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onDetail: () -> Unit,
    onPlay: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = group.posterUrl, contentDescription = null,
                    modifier = Modifier
                        .size(40.dp, 60.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(group.title, color = Snow, fontWeight = FontWeight.Bold)
                    Text(
                        "${if (group.type == DownloadGroupType.MOVIE) stringResource(R.string.download_movie) else stringResource(R.string.download_episodes_count, group.itemCount)} \u2022 ${formatFileSize(group.totalSizeBytes)}",
                        color = Silver, fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(8.dp))

            ContextMenuItem(Icons.Default.PlayArrow, stringResource(R.string.common_play), Amber) { onPlay() }
            ContextMenuItem(Icons.Default.Info, stringResource(R.string.common_view_details), Silver) { onDetail() }
            ContextMenuItem(Icons.Default.Delete, stringResource(R.string.common_delete), Ruby) { showDeleteConfirm = true }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.download_delete_title, group.title)) },
            text = {
                Text(
                    when (group.type) {
                        DownloadGroupType.MOVIE -> stringResource(R.string.download_delete_movie, formatFileSize(group.totalSizeBytes))
                        DownloadGroupType.SHOW -> stringResource(R.string.download_delete_show, group.itemCount, formatFileSize(group.totalSizeBytes))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.common_delete), color = Ruby) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Sort / Group / Settings Bottom Sheets
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    prefs: DownloadCataloguePrefs,
    onUpdate: (DownloadCataloguePrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.common_sort_by), color = Snow, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            CatalogueSortBy.entries.forEach { sort ->
                val selected = prefs.sortBy == sort
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdate(prefs.copy(sortBy = sort))
                            onDismiss()
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        sort.label, color = if (selected) Amber else Snow,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, null, tint = Amber, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupBottomSheet(
    prefs: DownloadCataloguePrefs,
    onUpdate: (DownloadCataloguePrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.download_group_by), color = Snow, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            CatalogueGrouping.entries.forEach { grouping ->
                val selected = prefs.groupingMode == grouping
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdate(prefs.copy(groupingMode = grouping))
                            onDismiss()
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        grouping.label, color = if (selected) Amber else Snow,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, null, tint = Amber, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogueSettingsSheet(
    prefs: DownloadCataloguePrefs,
    onUpdate: (DownloadCataloguePrefs) -> Unit,
    vm: DownloadCatalogueViewModel,
    onDismiss: () -> Unit,
) {
    var showDeleteWatchedConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(16.dp)) {
            item {
                Text(
                    stringResource(R.string.download_catalogue_settings), color = Snow, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
            }

            // Grid columns
            item {
                Text(stringResource(R.string.download_grid_columns), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = prefs.gridColumns == 0,
                        onClick = { onUpdate(prefs.copy(gridColumns = 0)) },
                        label = { Text(stringResource(R.string.download_auto), fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                    )
                    (2..6).forEach { n ->
                        FilterChip(
                            selected = prefs.gridColumns > 0 && prefs.gridColumnsManual == n,
                            onClick = { onUpdate(prefs.copy(gridColumns = 1, gridColumnsManual = n)) },
                            label = { Text("$n", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberSubtle,
                                selectedLabelColor = Amber,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Card info toggles
            item {
                Text(stringResource(R.string.download_card_info), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            item { ToggleRow(stringResource(R.string.download_storage_info_bar), prefs.showStorageInfo) { onUpdate(prefs.copy(showStorageInfo = it)) } }
            item { ToggleRow(stringResource(R.string.download_quality_badges), prefs.showQualityBadges) { onUpdate(prefs.copy(showQualityBadges = it)) } }
            item { ToggleRow(stringResource(R.string.download_episode_count), prefs.showEpisodeCount) { onUpdate(prefs.copy(showEpisodeCount = it)) } }
            item { ToggleRow(stringResource(R.string.download_file_size), prefs.showFileSize) { onUpdate(prefs.copy(showFileSize = it)) } }
            item { ToggleRow(stringResource(R.string.download_download_date), prefs.showDownloadDate) { onUpdate(prefs.copy(showDownloadDate = it)) } }
            item { ToggleRow(stringResource(R.string.download_watch_progress), prefs.showWatchProgress) { onUpdate(prefs.copy(showWatchProgress = it)) } }

            // Special sections
            item {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.download_special_sections), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            item { ToggleRow(stringResource(R.string.download_continue_watching), prefs.showContinueWatchingSection) { onUpdate(prefs.copy(showContinueWatchingSection = it)) } }
            item { ToggleRow(stringResource(R.string.download_recently_added), prefs.showRecentlyAddedSection) { onUpdate(prefs.copy(showRecentlyAddedSection = it)) } }

            // Auto-cleanup
            item {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.download_auto_cleanup), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            item { ToggleRow(stringResource(R.string.download_auto_delete_watched), prefs.autoDeleteWatched) { onUpdate(prefs.copy(autoDeleteWatched = it)) } }
            if (prefs.autoDeleteWatched) {
                item {
                    Text(stringResource(R.string.download_delete_after, prefs.autoDeleteAfterDays), color = Silver, fontSize = 12.sp)
                    Slider(
                        value = prefs.autoDeleteAfterDays.toFloat(),
                        onValueChange = { onUpdate(prefs.copy(autoDeleteAfterDays = it.toInt())) },
                        valueRange = 1f..90f,
                        colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
                    )
                }
            }

            // Bulk actions
            item {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.download_bulk_actions), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showDeleteWatchedConfirm = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Delete, null, tint = Ruby, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.download_delete_all_watched), color = Ruby)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteWatchedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteWatchedConfirm = false },
            title = { Text(stringResource(R.string.download_delete_all_watched)) },
            text = { Text(stringResource(R.string.download_delete_all_watched_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteWatched()
                    showDeleteWatchedConfirm = false
                }) { Text(stringResource(R.string.common_delete), color = Ruby) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWatchedConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Empty State
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun EmptyDownloadsState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CloudDownload, contentDescription = null,
                modifier = Modifier.size(64.dp), tint = Smoke,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.download_no_downloads), style = MaterialTheme.typography.titleMedium, color = Snow)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.download_empty_desc),
                style = MaterialTheme.typography.bodyMedium, color = Silver, textAlign = TextAlign.Center,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Helper Composables & Functions
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CompactActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(Color(0xFF1A1A2E), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Silver, modifier = Modifier.size(14.dp))
        Text(label, color = Silver, fontSize = 11.sp)
    }
}

@Composable
private fun ContextMenuItem(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Snow, fontSize = 15.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Snow, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Amber, checkedTrackColor = AmberSubtle),
        )
    }
}

private fun getGroupResolution(group: DownloadGroup): String? {
    return when (group.type) {
        DownloadGroupType.MOVIE -> group.movie?.resolution
        DownloadGroupType.SHOW -> group.seasons
            ?.flatMap { it.episodes }
            ?.mapNotNull { it.resolution }
            ?.maxByOrNull { DownloadCatalogueBuilder.resolutionRank(it) }
    }
}

private fun qualityColor(resolution: String): Color = when (resolution) {
    "4K", "2160p" -> Amethyst
    "1080p" -> Sapphire
    "720p" -> Color(0xFF43A047)
    else -> Color(0xFF555555)
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
