package com.torve.desktop.ui.v2.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.library.LocalLibraryRepository
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * Local file library surface - Phase 2 first cut.
 *
 * Lets the user add/remove root folders and lists every video file found
 * inside them. Click to play through the desktop player. No metadata
 * matching yet - entries display the cleaned filename. TMDB enrichment
 * (filename → search → poster + overview) is the next iteration.
 */
@Composable
fun LocalLibraryView(
    repository: LocalLibraryRepository?,
    onPlayFile: (filePath: String, title: String, posterUrl: String?) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    if (repository == null) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 24.dp, top = 12.dp)) {
            TorvePlaceholderState(
                title = "Local library not initialised",
                description = "The desktop runtime hasn't published a LocalLibraryRepository to V2App. " +
                    "This is a build-wiring bug, not a configuration issue.",
            )
        }
        return
    }

    val state by repository.state.collectAsState()
    val enrichment by repository.enrichmentProgress.collectAsState()
    val scope = rememberCoroutineScope()
    var pickerError by remember { mutableStateOf<String?>(null) }

    // ── Browse controls ───────────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(LibrarySort.RECENT) }
    var groupByFolder by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Folder management strip.
        TorveSectionCard(
            title = "Watched folders",
            supportingText = "Torve scans these folders for video files. Add a folder to start.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorvePrimaryButton(
                        text = "Add folder",
                        onClick = {
                            scope.launch {
                                val picked = pickDirectory()
                                if (picked == null) {
                                    pickerError = null
                                    return@launch
                                }
                                val ok = repository.addFolder(picked)
                                pickerError = if (!ok) "Couldn't add folder. Path may not exist or is already tracked." else null
                            }
                        },
                    )
                    if (state.folders.isNotEmpty()) {
                        TorveGhostButton(
                            text = "Rescan all",
                            onClick = { scope.launch { repository.rescanAll() } },
                        )
                    }
                }
                pickerError?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.labelSmall, color = colors.error)
                }
                if (state.folders.isEmpty()) {
                    Text(
                        text = "No folders yet - pick one of your media drives and Torve will index every video file inside.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                } else {
                    state.folders.forEach { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = folder.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            val countInFolder = state.entries.count { it.folderPath == folder.path }
                            TorveBadge(
                                text = if (countInFolder == 1) "1 file" else "$countInFolder files",
                                tone = TorveBadgeTone.Neutral,
                            )
                            Spacer(Modifier.weight(1f))
                            TorveGhostButton(
                                text = "Remove",
                                onClick = { scope.launch { repository.removeFolder(folder.path) } },
                            )
                        }
                    }
                }
            }
        }

        // Composition counts. Cheap to compute on every recomposition;
        // the entries list is bounded by the per-folder cap.
        val movieCount = state.entries.count { !it.isSeries }
        val seriesEpisodeCount = state.entries.count { it.isSeries }
        val distinctSeriesCount = state.entries
            .asSequence()
            .filter { it.isSeries }
            .map { it.tmdbId?.toString() ?: it.matchedTitle ?: it.displayName }
            .distinct()
            .count()

        // Entry list with optional series grouping below.
        if (state.entries.isEmpty() && state.folders.isNotEmpty()) {
            TorvePlaceholderState(
                title = "Folders are empty",
                description = "No recognised video files were found in any of the watched folders. " +
                    "Supported extensions: mp4, mkv, avi, mov, m4v, webm, ts, vob, ...",
                emoji = "🎬",
            )
        } else if (state.entries.isNotEmpty()) {
            // Composition summary - gives the user immediate library
            // shape at a glance. Disk usage is computed once over the
            // full set; cheap because each entry already carries size.
            val totalBytes = remember(state.entries) {
                state.entries.sumOf { it.sizeBytes }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TorveBadge(text = "${state.entries.size} files", tone = TorveBadgeTone.Neutral)
                if (movieCount > 0) {
                    TorveBadge(text = "$movieCount movies", tone = TorveBadgeTone.Accent)
                }
                if (distinctSeriesCount > 0) {
                    TorveBadge(
                        text = "$distinctSeriesCount series · $seriesEpisodeCount episodes",
                        tone = TorveBadgeTone.Success,
                    )
                }
                TorveBadge(
                    text = "Total ${formatBytes(totalBytes)}",
                    tone = TorveBadgeTone.Neutral,
                )
                if (enrichment.isActive) {
                    // Background TMDB matching progress. Disappears when
                    // the queue drains.
                    TorveBadge(
                        text = "Matching ${enrichment.done} / ${enrichment.total}",
                        tone = TorveBadgeTone.Warning,
                    )
                }
            }

            // Search + sort + grouping controls.
            LibraryBrowseControls(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                sortMode = sortMode,
                onSortChange = { sortMode = it },
                groupByFolder = groupByFolder,
                onToggleGroupByFolder = { groupByFolder = !groupByFolder },
            )

            // Filtered + sorted view of the entries.
            val visibleEntries = remember(state.entries, searchQuery, sortMode) {
                applyFilterAndSort(state.entries, searchQuery, sortMode)
            }
            if (visibleEntries.isEmpty()) {
                TorvePlaceholderState(
                    title = "No matches",
                    description = "No files match \"$searchQuery\". Clear the filter or try a different term.",
                    emoji = "🔍",
                )
            } else {
                LocalLibraryGroupedList(
                    entries = visibleEntries,
                    groupByFolder = groupByFolder,
                    onPlay = { absolutePath, title ->
                        onPlayFile(absolutePath, title, null)
                    },
                )
            }
        }
    }
}

private enum class LibrarySort(val label: String) {
    RECENT("Recently added"),
    ALPHABETICAL("A → Z"),
    YEAR("Year"),
    SIZE("Size (largest)"),
}

private fun applyFilterAndSort(
    entries: List<LocalLibraryRepository.LibraryEntry>,
    query: String,
    sort: LibrarySort,
): List<LocalLibraryRepository.LibraryEntry> {
    val q = query.trim().lowercase()
    val filtered = if (q.isBlank()) entries else entries.filter { e ->
        e.displayName.lowercase().contains(q) ||
            e.matchedTitle?.lowercase()?.contains(q) == true ||
            e.absolutePath.lowercase().contains(q)
    }
    return when (sort) {
        LibrarySort.RECENT -> filtered.sortedByDescending { it.modifiedAtMs }
        LibrarySort.ALPHABETICAL -> filtered.sortedBy { (it.matchedTitle ?: it.displayName).lowercase() }
        LibrarySort.YEAR -> filtered.sortedByDescending { it.year ?: -1 }
        LibrarySort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
    }
}

@Composable
private fun LibraryBrowseControls(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortMode: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    groupByFolder: Boolean,
    onToggleGroupByFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.torve.desktop.ui.components.TorveSearchField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = "Search local library",
            modifier = Modifier.weight(1f),
        )
        var sortMenuOpen by remember { mutableStateOf(false) }
        Box {
            TorveGhostButton(
                text = "Sort · ${sortMode.label}",
                onClick = { sortMenuOpen = true },
            )
            com.torve.desktop.ui.components.TorveDropdownScaffold(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
                items = LibrarySort.entries.map { mode ->
                    mode.label to { onSortChange(mode); sortMenuOpen = false }
                },
            )
        }
        TorveGhostButton(
            text = if (groupByFolder) "Grouping · Folder" else "Grouping · Series",
            onClick = onToggleGroupByFolder,
        )
    }
}

/**
 * Renders the entries grouped by series. Movies render as flat rows in
 * a single section; series get a header row with an expand/collapse,
 * and episodes are listed nested underneath.
 *
 * Grouping key uses tmdbId when matched, falls back to matchedTitle or
 * displayName so unmatched series episodes still cluster together.
 */
@Composable
private fun LocalLibraryGroupedList(
    entries: List<LocalLibraryRepository.LibraryEntry>,
    groupByFolder: Boolean = false,
    onPlay: (absolutePath: String, title: String) -> Unit,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    if (groupByFolder) {
        // Group by folderPath. Each folder becomes a header section with
        // its entries listed underneath.
        val folderGroups = remember(entries) {
            entries.groupBy { it.folderPath }
                .toList()
                .sortedBy { it.first.lowercase() }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(folderGroups, key = { "folder:${it.first}" }) { (folder, list) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val isExpanded = expanded[folder] != false
                    FolderHeaderRow(
                        folderPath = folder,
                        count = list.size,
                        expanded = isExpanded,
                        onToggle = { expanded[folder] = !isExpanded },
                    )
                    if (isExpanded) {
                        list.forEach { entry ->
                            Box(modifier = Modifier.padding(start = 24.dp)) {
                                LocalEntryRow(
                                    entry = entry,
                                    onPlay = { onPlay(entry.absolutePath, entry.matchedTitle ?: entry.displayName) },
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val movies = remember(entries) {
        entries.filter { !it.isSeries }
    }
    val seriesGroups = remember(entries) {
        entries.filter { it.isSeries }
            .groupBy { it.tmdbId?.toString() ?: it.matchedTitle ?: it.displayName }
            .mapValues { (_, episodes) ->
                episodes.sortedWith(
                    compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }),
                )
            }
            .toList()
            .sortedBy { (_, eps) -> (eps.first().matchedTitle ?: eps.first().displayName).lowercase() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (seriesGroups.isNotEmpty()) {
            items(
                items = seriesGroups,
                key = { it.first },
            ) { (groupKey, episodes) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val isExpanded = expanded[groupKey] == true
                    SeriesHeaderRow(
                        episodes = episodes,
                        expanded = isExpanded,
                        onToggle = { expanded[groupKey] = !isExpanded },
                    )
                    if (isExpanded) {
                        episodes.forEach { ep ->
                            Box(modifier = Modifier.padding(start = 24.dp)) {
                                LocalEntryRow(
                                    entry = ep,
                                    onPlay = { onPlay(ep.absolutePath, ep.matchedTitle ?: ep.displayName) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (movies.isNotEmpty()) {
            itemsIndexed(
                items = movies,
                key = { index, entry -> "${entry.absolutePath}#$index" },
            ) { _, entry ->
                LocalEntryRow(
                    entry = entry,
                    onPlay = { onPlay(entry.absolutePath, entry.matchedTitle ?: entry.displayName) },
                )
            }
        }
    }
}

@Composable
private fun FolderHeaderRow(
    folderPath: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderStrong.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderPath.substringAfterLast(java.io.File.separatorChar)
                        .ifBlank { folderPath },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folderPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TorveBadge(text = "$count files", tone = TorveBadgeTone.Neutral)
            TorveGhostButton(
                text = "Open",
                onClick = {
                    runCatching {
                        java.awt.Desktop.getDesktop().open(java.io.File(folderPath))
                    }
                },
            )
            TorveBadge(text = if (expanded) "Hide" else "Show", tone = TorveBadgeTone.Neutral)
        }
    }
}

@Composable
private fun SeriesHeaderRow(
    episodes: List<LocalLibraryRepository.LibraryEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val first = episodes.first()
    val title = first.matchedTitle ?: first.displayName
    val seasons = episodes.mapNotNull { it.seasonNumber }.distinct().sorted()
    val seasonSummary = when {
        seasons.isEmpty() -> ""
        seasons.size == 1 -> "Season ${seasons.first()}"
        else -> "${seasons.size} seasons"
    }
    Surface(
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderStrong.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val posterModifier = Modifier
                .size(width = 36.dp, height = 54.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.fieldSurface)
            val cachedPoster = first.posterUrl?.let {
                com.torve.desktop.ui.v2.components.rememberCachedBitmap(it)
            }
            if (cachedPoster != null) {
                androidx.compose.foundation.Image(
                    bitmap = cachedPoster,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = posterModifier,
                )
            } else {
                Box(modifier = posterModifier)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        first.year?.toString(),
                        seasonSummary.takeIf { it.isNotBlank() },
                        "${episodes.size} episodes",
                    ).joinToString("  •  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
            TorveBadge(
                text = if (expanded) "Hide" else "Show",
                tone = TorveBadgeTone.Neutral,
            )
        }
    }
}

@Composable
private fun LocalEntryRow(
    entry: LocalLibraryRepository.LibraryEntry,
    onPlay: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    // For series with a known episode title, the row title becomes
    // "Show - Episode" so the user immediately recognises which one
    // it is. Otherwise fall back to the show / cleaned filename.
    val title = when {
        entry.isSeries && !entry.episodeTitle.isNullOrBlank() ->
            "${entry.matchedTitle ?: entry.displayName}  -  ${entry.episodeTitle}"
        else -> entry.matchedTitle ?: entry.displayName
    }
    val subtitle = buildList {
        entry.year?.let { add(it.toString()) }
        if (entry.isSeries) {
            entry.seasonNumber?.let { s ->
                entry.episodeNumber?.let { e -> add("S${s}E${e}") }
            }
            add("Series")
        }
        if (entry.tmdbId == null) add("Unmatched")
    }.joinToString("  •  ")

    Surface(
        color = colors.cardSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Tiny poster thumb when TMDB found a match. Falls back to a
            // placeholder colour box so unmatched rows still align.
            val posterModifier = Modifier
                .size(width = 36.dp, height = 54.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.fieldSurface)
            val cachedPoster = entry.posterUrl?.let {
                com.torve.desktop.ui.v2.components.rememberCachedBitmap(it)
            }
            if (cachedPoster != null) {
                androidx.compose.foundation.Image(
                    bitmap = cachedPoster,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = posterModifier,
                )
            } else {
                Box(modifier = posterModifier)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = entry.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TorveBadge(
                text = formatBytes(entry.sizeBytes),
                tone = TorveBadgeTone.Neutral,
            )
            TorveGhostButton(
                text = "Reveal",
                onClick = {
                    runCatching {
                        // Open the parent folder in the OS file manager.
                        // Java's Desktop.open is the closest cross-platform
                        // equivalent of "Show in Finder/Explorer".
                        java.io.File(entry.absolutePath).parentFile?.let { parent ->
                            java.awt.Desktop.getDesktop().open(parent)
                        }
                    }
                },
            )
        }
    }
}

private val sizeFormatter = DecimalFormat("#.#")
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "-"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "${sizeFormatter.format(gb)} GB"
    val mb = bytes / 1_000_000.0
    if (mb >= 1.0) return "${sizeFormatter.format(mb)} MB"
    val kb = bytes / 1_000.0
    return "${sizeFormatter.format(kb)} KB"
}

/**
 * AWT FileDialog with directory mode. Returns the picked absolute path
 * or null on cancel. Runs on the calling coroutine - caller is expected
 * to be on a UI dispatcher already.
 */
private fun pickDirectory(): String? {
    // AWT FileDialog supports directory mode on macOS via the
    // `apple.awt.fileDialogForDirectories` system property; on Windows
    // and Linux it doesn't, so we fall back to JFileChooser there.
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    return if (isMac) {
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a folder", java.awt.FileDialog.LOAD)
            dialog.isVisible = true
            val name = dialog.file ?: return null
            val parent = dialog.directory ?: return null
            java.io.File(parent, name).absolutePath
        } finally {
            if (previous != null) System.setProperty("apple.awt.fileDialogForDirectories", previous)
            else System.clearProperty("apple.awt.fileDialogForDirectories")
        }
    } else {
        val chooser = javax.swing.JFileChooser().apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose a folder"
        }
        val result = chooser.showOpenDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath
        else null
    }
}
