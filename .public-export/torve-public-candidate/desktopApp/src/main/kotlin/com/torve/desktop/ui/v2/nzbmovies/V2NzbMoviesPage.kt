@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.nzbmovies

import androidx.compose.foundation.background
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.adult.IndexerCategoryMap
import com.torve.desktop.adult.IndexerCategoryMap.MovieLanguage
import com.torve.desktop.adult.NewznabClient
import com.torve.desktop.adult.NewznabItem
import com.torve.desktop.adult.NzbBrowseStateHolder
import com.torve.desktop.adult.NzbPosterCache
import com.torve.desktop.adult.NzbReleaseTitleParser
import com.torve.desktop.adult.TorBoxUsenetClient
import com.torve.desktop.download.DesktopDownloadManager
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Movies catalog sourced directly from a Newznab indexer (e.g. scenenzbs)
 * with language-aware category routing - see [IndexerCategoryMap].
 *
 * Three filter dimensions:
 *  1. **Language pills** drive the `cat=` parameter via the indexer map.
 *  2. **Search box** appends a free-text `q=` to the same call.
 *  3. **Sort** is always pubdate-desc (newest first), enforced
 *     client-side by [NewznabClient] regardless of indexer behavior.
 *
 * Releases render as a poster grid: [NzbReleaseTitleParser] extracts
 * (title, year) from the scene name, [NzbPosterCache] hits TMDB to
 * fetch artwork, and rows that don't match TMDB fall back to a
 * text-only card so we never silently drop releases.
 */
@Composable
fun V2NzbMoviesPage(
    newznab: NewznabClient,
    torbox: TorBoxUsenetClient,
    posterCache: NzbPosterCache,
    torboxApiKey: String,
    indexerType: String,
    indexerUrl: String,
    indexerApiKey: String,
    indexerSecretsRedacted: Boolean = false,
    pandaReleaseLanguages: List<String>,
    downloadManager: DesktopDownloadManager? = null,
    onPlayStream: (url: String, title: String, sizeBytes: Long?) -> Unit,
    onOpenPandaSetup: () -> Unit = {},
    onOpenDownloadSettings: () -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()

    val pageKey = "nzb_movies"
    val saved = remember { NzbBrowseStateHolder.get(pageKey) }
    var query by remember { mutableStateOf(saved.query) }
    var allItems by remember { mutableStateOf<List<NewznabItem>>(saved.items) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(saved.errorText) }
    var resolveStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var needsFolderPrompt by remember { mutableStateOf<String?>(null) }
    val configured = indexerUrl.isNotBlank() && indexerApiKey.isNotBlank()

    // Seed selection from Panda's languages, or restored cache.
    val initialLanguages = remember(pandaReleaseLanguages, saved) {
        val fromCache = saved.selectedLanguages
            .mapNotNull { MovieLanguage.fromCode(it) }
            .toSet()
        if (fromCache.isNotEmpty()) return@remember fromCache
        val mapped = pandaReleaseLanguages.mapNotNull { MovieLanguage.fromCode(it) }
        when {
            mapped.isEmpty() -> setOf(MovieLanguage.ANY)
            MovieLanguage.ANY in mapped -> setOf(MovieLanguage.ANY)
            else -> mapped.toSet()
        }
    }
    var selectedLanguages by remember { mutableStateOf(initialLanguages) }

    val categoryParam = remember(indexerType, selectedLanguages) {
        IndexerCategoryMap.moviesCategoriesFor(indexerType, selectedLanguages)
    }
    val needsTitleFallback = remember(indexerType) {
        !IndexerCategoryMap.hasLanguageCategories(indexerType)
    }

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = saved.scrollIndex,
        initialFirstVisibleItemScrollOffset = saved.scrollOffset,
    )

    suspend fun reload() {
        loading = true
        errorText = null
        try {
            // Pull up to 1,000 items - paginated and date-sorted by
            // NewznabClient. IO dispatcher so the blocking http.send
            // doesn't lock the Compose dispatcher.
            val raw = withContext(Dispatchers.IO) {
                if (query.isBlank()) {
                    newznab.browseAllPages(indexerUrl, indexerApiKey, categoryParam, maxItems = 1000)
                } else {
                    newznab.searchAllPages(indexerUrl, indexerApiKey, categoryParam, query.trim(), maxItems = 1000)
                }
            }
            allItems = if (needsTitleFallback) {
                raw.filter { IndexerCategoryMap.titleMatchesLanguages(it.title, selectedLanguages) }
            } else raw
            if (allItems.isEmpty() && configured) {
                errorText = "Indexer returned 0 results for the selected languages."
            }
        } catch (t: Throwable) {
            errorText = t.message ?: "Indexer call failed."
            allItems = emptyList()
        }
        loading = false
        NzbBrowseStateHolder.put(
            pageKey,
            NzbBrowseStateHolder.State(
                query = query,
                items = allItems,
                errorText = errorText,
                scrollIndex = gridState.firstVisibleItemIndex,
                scrollOffset = gridState.firstVisibleItemScrollOffset,
                selectedLanguages = selectedLanguages.map { it.name }.toSet(),
            ),
        )
    }

    LaunchedEffect(indexerUrl, indexerApiKey, categoryParam) {
        if (configured && allItems.isEmpty()) reload()
    }
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) {
        val current = NzbBrowseStateHolder.get(pageKey)
        NzbBrowseStateHolder.put(
            pageKey,
            current.copy(
                scrollIndex = gridState.firstVisibleItemIndex,
                scrollOffset = gridState.firstVisibleItemScrollOffset,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.shellBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)) {
            TorvePageHeader(
                title = ds("Movies via Usenet"),
                subtitle = if (configured) {
                    val indexerLabel = indexerType.takeIf { it.isNotBlank() } ?: "indexer"
                    "From $indexerLabel · cat=$categoryParam · sorted newest first"
                } else {
                    "Configure a Newznab indexer in Panda to browse"
                },
            )

            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MovieLanguage.entries.forEach { lang ->
                    val selected = lang in selectedLanguages
                    TorveFilterChip(
                        text = lang.label,
                        selected = selected,
                        onClick = {
                            selectedLanguages = when {
                                lang == MovieLanguage.ANY -> setOf(MovieLanguage.ANY)
                                selected -> {
                                    val next = selectedLanguages - lang
                                    if (next.isEmpty()) setOf(MovieLanguage.ANY) else next
                                }
                                else -> (selectedLanguages - MovieLanguage.ANY) + lang
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun runSearch() { scope.launch { reload() } }
                TorveTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = ds("Search movies (Enter)"),
                    modifier = Modifier.weight(1f),
                    onSubmit = ::runSearch,
                )
                TorvePrimaryButton(text = ds("Search"), onClick = ::runSearch)
            }

            needsFolderPrompt?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = colors.elevatedSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = ds("Download folder not set"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                        TorvePrimaryButton(
                            text = ds("Open Settings"),
                            onClick = {
                                needsFolderPrompt = null
                                onOpenDownloadSettings()
                            },
                        )
                        TorveGhostButton(
                            text = ds("Dismiss"),
                            onClick = { needsFolderPrompt = null },
                        )
                    }
                }
            }

            // Sticky resolve banner.
            val activeResolutions = resolveStatus.entries
                .filter { (_, msg) ->
                    !msg.startsWith("Failed") &&
                        !msg.contains("error", ignoreCase = true) &&
                        !msg.contains("invalid", ignoreCase = true)
                }
            if (activeResolutions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(color = colors.accentContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        activeResolutions.forEach { (key, status) ->
                            val title = allItems.firstOrNull { (it.guid ?: it.nzbUrl) == key }?.title ?: key
                            Text(
                                text = "Resolving via TorBox · $status",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent,
                            )
                            Text(
                                text = title.take(120),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            when {
                !configured -> Surface(
                    color = colors.elevatedSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (indexerSecretsRedacted) "Panda credentials are masked"
                                else "No NZB indexer configured in Panda",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = if (indexerSecretsRedacted)
                                "Open Panda → Usenet step → re-paste your indexer key, then your library here lights up."
                            else
                                "Settings → Add-ons → Panda → Configure → Usenet step.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        TorvePrimaryButton(text = ds("Open Panda setup"), onClick = onOpenPandaSetup)
                    }
                }
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.accent) }
                errorText != null -> TorveBanner(
                    title = ds("Couldn't load"),
                    description = errorText!!,
                    tone = TorveBannerTone.Info,
                )
                allItems.isEmpty() -> Text(
                    text = ds("No results."),
                    color = colors.textSecondary,
                )
                else -> Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().padding(end = 24.dp),
                    ) {
                    items(allItems, key = { it.guid ?: it.nzbUrl }) { item ->
                        val rowKey = item.guid ?: item.nzbUrl
                        val status = resolveStatus[rowKey]
                        val isAuth = status?.let { torbox.isAuthError(it) } == true
                        NzbMoviePosterCard(
                            item = item,
                            posterCache = posterCache,
                            statusText = status,
                            torboxConfigured = torboxApiKey.isNotBlank(),
                            showReconfigure = isAuth,
                            downloadEnabled = downloadManager != null,
                            onReconfigure = onOpenPandaSetup,
                            onPlay = {
                                resolveStatus = resolveStatus + (rowKey to "Starting...")
                                println("TORVE NZBMOVIES ┃ play clicked: ${item.title}")
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        torbox.resolve(item.nzbUrl, torboxApiKey) { msg ->
                                            resolveStatus = resolveStatus + (rowKey to msg)
                                        }
                                    }
                                    res.onSuccess { resolved ->
                                        resolveStatus = resolveStatus - rowKey
                                        onPlayStream(resolved.streamUrl, resolved.fileName, resolved.sizeBytes)
                                    }.onFailure { t ->
                                        resolveStatus = resolveStatus + (rowKey to (t.message ?: "Unknown error"))
                                    }
                                }
                            },
                            onDownload = downloadManager?.let { dm ->
                                {
                                    resolveStatus = resolveStatus + (rowKey to "Starting...")
                                    println("TORVE NZBMOVIES ┃ download clicked: ${item.title}")
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            torbox.resolve(item.nzbUrl, torboxApiKey) { msg ->
                                                resolveStatus = resolveStatus + (rowKey to msg)
                                            }
                                        }
                                        res.onSuccess { resolved ->
                                            resolveStatus = resolveStatus - rowKey
                                            // Look up TMDB for a poster URL we
                                            // can attach to the local Download
                                            // row - gives the Library a poster
                                            // even before the file finishes.
                                            val parsed = NzbReleaseTitleParser.parse(item.title)
                                            val posterUrl = parsed?.let {
                                                (posterCache.lookup(it.title, it.year) as? NzbPosterCache.Match.Found)?.posterUrl
                                            }
                                            val outcome = dm.queueNzbDownload(
                                                title = resolved.fileName.ifBlank { item.title },
                                                streamUrl = resolved.streamUrl,
                                                sizeBytes = resolved.sizeBytes,
                                                surface = DesktopDownloadManager.NzbDownloadSurface.MOVIES,
                                                posterUrl = posterUrl,
                                            )
                                            if (outcome is DesktopDownloadManager.QueueResult.NeedsFolder) {
                                                needsFolderPrompt = "Set a Movies download folder before downloading."
                                            }
                                        }.onFailure { t ->
                                            resolveStatus = resolveStatus + (rowKey to (t.message ?: "Unknown error"))
                                        }
                                    }
                                }
                            },
                        )
                    }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(gridState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NzbMoviePosterCard(
    item: NewznabItem,
    posterCache: NzbPosterCache,
    statusText: String?,
    torboxConfigured: Boolean,
    showReconfigure: Boolean = false,
    downloadEnabled: Boolean = false,
    onReconfigure: () -> Unit = {},
    onDownload: (() -> Unit)? = null,
    onPlay: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors

    // Resolve the TMDB match in the background. produceState gives the
    // grid responsive scrolling - cards render with a placeholder
    // immediately and swap to the poster once the lookup lands.
    val parsed = remember(item.title) { NzbReleaseTitleParser.parse(item.title) }
    val match by produceState<NzbPosterCache.Match?>(initialValue = null, parsed) {
        if (parsed == null) {
            value = NzbPosterCache.Match.None
        } else {
            value = withContext(Dispatchers.IO) {
                posterCache.lookup(parsed.title, parsed.year)
            }
        }
    }
    val found = match as? NzbPosterCache.Match.Found
    val posterBitmap = rememberCachedBitmap(found?.posterUrl)

    val isWorking = statusText != null && !statusText.startsWith("Failed") &&
        !statusText.startsWith("TorBox error")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp)),
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Poster (or placeholder). 2:3 aspect mimics the rest of
            // the catalog grid.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(colors.cardSurface)
                    .clickable(enabled = torboxConfigured && !isWorking, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (posterBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = posterBitmap,
                        contentDescription = found?.title ?: item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = parsed?.title ?: item.title.take(60),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        parsed?.year?.let {
                            Text(
                                text = it.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = found?.title ?: parsed?.title ?: item.title.take(60),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        (found?.year ?: parsed?.year)?.toString(),
                        item.sizeBytes?.let { humanBytes(it) },
                        item.pubDate?.take(16),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showReconfigure) colors.warning else colors.accent,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (showReconfigure) {
                    TorvePrimaryButton(
                        text = ds("Reconfigure Panda"),
                        onClick = onReconfigure,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (downloadEnabled && onDownload != null) {
                            TorveSecondaryButton(
                                text = ds("Download"),
                                onClick = onDownload,
                                enabled = torboxConfigured && !isWorking,
                            )
                        }
                        TorvePrimaryButton(
                            text = if (isWorking) "Working..." else "Play",
                            onClick = onPlay,
                            enabled = torboxConfigured && !isWorking,
                        )
                    }
                    TorveGhostButton(
                        text = ds("Copy NZB"),
                        onClick = {
                            runCatching {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                    java.awt.datatransfer.StringSelection(item.nzbUrl),
                                    null,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return "%.2f %s".format(v, units[i])
}
