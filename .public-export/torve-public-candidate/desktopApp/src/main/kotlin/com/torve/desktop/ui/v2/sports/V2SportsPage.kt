@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.torve.data.usenet.NewznabClient
import com.torve.data.usenet.NewznabItem
import com.torve.desktop.download.DesktopDownloadManager
import com.torve.domain.nzb.NzbResolver
import com.torve.domain.sports.SportBucket
import com.torve.presentation.usenet.NzbBrowseStateHolder
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sports catalog. Fetches Newznab category 5060 (Sports - standard
 * across all Newznab indexers) from whatever indexer is configured in
 * Panda. Releases are then classified client-side by title into sport
 * buckets (NFL, NBA, UFC, Soccer, F1, etc.) so the user can filter.
 *
 * Playback uses the same TorBox upload + poll pipeline as the Adult
 * page; pre-resolved stream URLs are handed off to the player via the
 * shared `onPlayStream` callback.
 */
@Composable
fun V2SportsPage(
    newznab: NewznabClient,
    resolver: NzbResolver,
    indexerType: String = "",
    indexerUrl: String,
    indexerApiKey: String,
    indexerSecretsRedacted: Boolean = false,
    downloadManager: DesktopDownloadManager? = null,
    onPlayStream: (url: String, title: String, sizeBytes: Long?) -> Unit,
    onOpenPandaSetup: () -> Unit = {},
    onOpenDownloadSettings: () -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()

    val pageKey = "sports"
    val saved = remember { NzbBrowseStateHolder.get(pageKey) }
    var query by remember { mutableStateOf(saved.query) }
    var allItems by remember { mutableStateOf<List<NewznabItem>>(saved.items) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(saved.errorText) }
    var selectedBucket by remember {
        mutableStateOf(
            saved.selectedSportBucket?.let { name ->
                SportBucket.entries.firstOrNull { it.name == name }
            },
        )
    }
    var resolveStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var needsFolderPrompt by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved.scrollIndex,
        initialFirstVisibleItemScrollOffset = saved.scrollOffset,
    )
    val configured = indexerUrl.isNotBlank() && indexerApiKey.isNotBlank()

    suspend fun reload() {
        loading = true
        errorText = null
        try {
            // Pull up to 1,000 items via paginated Newznab calls so a
            // search like "NBA" against a busy indexer doesn't cap at
            // the first page. IO dispatcher: the underlying http.send
            // is blocking and would freeze recompositions otherwise.
            // scenenzbs has a German sport sub-cat (5160) alongside
            // the spec 5060; both together broaden the user's pool.
            val sportsCat = com.torve.domain.usenet.UsenetIndexerCategoryMap.sportsCategoriesFor(indexerType)
            allItems = withContext(Dispatchers.IO) {
                if (query.isBlank()) {
                    newznab.browseAllPages(indexerUrl, indexerApiKey, sportsCat, maxItems = 1000)
                } else {
                    newznab.searchAllPages(indexerUrl, indexerApiKey, sportsCat, query.trim(), maxItems = 1000)
                }
            }
            if (allItems.isEmpty() && configured) {
                errorText = "Indexer returned 0 results."
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
                scrollIndex = listState.firstVisibleItemIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
                selectedSportBucket = selectedBucket?.name,
            ),
        )
    }

    LaunchedEffect(indexerUrl, indexerApiKey) {
        if (configured && allItems.isEmpty()) reload()
    }
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val current = NzbBrowseStateHolder.get(pageKey)
        NzbBrowseStateHolder.put(
            pageKey,
            current.copy(
                scrollIndex = listState.firstVisibleItemIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
            ),
        )
    }

    // Classify each item once per load. Recomputed on filter / search.
    data class ClassifiedItem(val item: NewznabItem, val bucket: SportBucket)
    val classified: List<ClassifiedItem> = remember(allItems) {
        allItems.map { ClassifiedItem(it, SportBucket.classify(it.title)) }
    }
    val countsByBucket: Map<SportBucket, Int> = remember(classified) {
        classified.groupingBy { it.bucket }.eachCount()
    }
    val visible: List<ClassifiedItem> = remember(classified, selectedBucket) {
        if (selectedBucket == null) classified else classified.filter { it.bucket == selectedBucket }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.shellBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)) {
            TorvePageHeader(
                title = ds("Sports"),
                subtitle = ds("Newznab sports categories · classified client-side by release name"),
            )

            Spacer(Modifier.height(12.dp))
            // Sport filter pills. "All" resets, each pill shows the
            // count of classified items currently in that bucket.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TorveFilterChip(
                    text = "All · ${classified.size}",
                    selected = selectedBucket == null,
                    onClick = { selectedBucket = null },
                )
                SportBucket.entries.forEach { b ->
                    val count = countsByBucket[b] ?: 0
                    if (b == SportBucket.OTHER && count == 0) return@forEach
                    TorveFilterChip(
                        text = "${b.label} · $count",
                        selected = selectedBucket == b,
                        onClick = { selectedBucket = b },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // Search row.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun runSearch() { scope.launch { reload() } }
                fun clearSearch() {
                    query = ""
                    scope.launch { reload() }
                }
                TorveTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = ds("Search sports (Enter)"),
                    modifier = Modifier.weight(1f),
                    onSubmit = ::runSearch,
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = ::clearSearch) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    } else null,
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

            // Sticky resolve banner (mirrors Adult page UX).
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
                                "Panda has an indexer configured but won't share the API key with this device. Open Panda → Usenet → re-paste the key → Update."
                            else
                                "Set up Usenet via Settings → Add-ons → Panda → Configure → Usenet step. The Sports page reads its credentials directly from Panda's config.",
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
                visible.isEmpty() -> Text(
                    text = if (selectedBucket != null)
                        "No releases classified into ${selectedBucket!!.label} this batch."
                    else "No results.",
                    color = colors.textSecondary,
                )
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.item.guid ?: it.item.nzbUrl }) { ci ->
                        val rowKey = ci.item.guid ?: ci.item.nzbUrl
                        val status = resolveStatus[rowKey]
                        val isAuth = status?.let { resolver.isAuthError(it) } == true
                        SportsRow(
                            item = ci.item,
                            bucket = ci.bucket,
                            statusText = status,
                            torboxConfigured = resolver.isConfigured,
                            showReconfigure = isAuth,
                            downloadEnabled = downloadManager != null,
                            onReconfigure = onOpenPandaSetup,
                            onPlay = {
                                // IO dispatcher so the blocking http.send
                                // inside torbox.resolve doesn't freeze the
                                // Compose dispatcher for the duration of the
                                // NZB download.
                                if (resolveStatus[rowKey] != null) return@SportsRow
                                resolveStatus = resolveStatus + (rowKey to "Starting...")
                                println("TORVE SPORTS ┃ play clicked: ${ci.item.title}")
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        resolver.resolve(ci.item.nzbUrl) { msg ->
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
                                    if (resolveStatus[rowKey] != null) return@let
                                    resolveStatus = resolveStatus + (rowKey to "Starting...")
                                    println("TORVE SPORTS ┃ download clicked: ${ci.item.title}")
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            resolver.resolve(ci.item.nzbUrl) { msg ->
                                                resolveStatus = resolveStatus + (rowKey to msg)
                                            }
                                        }
                                        res.onSuccess { resolved ->
                                            resolveStatus = resolveStatus - rowKey
                                            val outcome = dm.queueNzbDownload(
                                                title = resolved.fileName.ifBlank { ci.item.title },
                                                streamUrl = resolved.streamUrl,
                                                sizeBytes = resolved.sizeBytes,
                                                surface = DesktopDownloadManager.NzbDownloadSurface.SPORTS,
                                            )
                                            if (outcome is DesktopDownloadManager.QueueResult.NeedsFolder) {
                                                needsFolderPrompt = "Set a Sports download folder before downloading."
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
            }
        }
    }
}

@Composable
private fun SportsRow(
    item: NewznabItem,
    bucket: SportBucket,
    statusText: String?,
    torboxConfigured: Boolean,
    showReconfigure: Boolean = false,
    downloadEnabled: Boolean = false,
    onReconfigure: () -> Unit = {},
    onDownload: (() -> Unit)? = null,
    onPlay: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(8.dp)),
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = colors.accentContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = bucket.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = listOfNotNull(
                        item.sizeBytes?.let { humanBytes(it) },
                        item.fileCount?.let { "$it files" },
                        item.grabs?.let { "$it grabs" },
                        item.pubDate,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showReconfigure) colors.warning else colors.accent,
                    )
                }
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
            if (showReconfigure) {
                TorvePrimaryButton(text = ds("Reconfigure Panda"), onClick = onReconfigure)
            } else {
                val isWorking = statusText != null && !statusText.startsWith("Failed") &&
                    !statusText.startsWith("TorBox error")
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
