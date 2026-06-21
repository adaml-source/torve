@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.adult

import androidx.compose.foundation.background
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.torve.desktop.adult.AdultModePreferences
import com.torve.desktop.adult.NewznabClient
import com.torve.desktop.adult.NewznabItem
import com.torve.desktop.adult.NzbBrowseStateHolder
import com.torve.desktop.download.DesktopDownloadManager
import com.torve.domain.nzb.NzbResolver
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adult catalog sourced from a Newznab indexer (e.g. scenenzbs.com).
 * Defaults to category 6010 - XXX/Movies - which contains single-movie
 * releases rather than clip megapacks.
 *
 * Each result is the raw NZB title from the indexer; we don't try to
 * cross-reference TMDB because most adult titles aren't in TMDB anyway.
 * Click "Open NZB" to drop the URL into your browser so TorBox can
 * ingest it. Direct TorBox-API upload from inside Torve is the next
 * step once this surface stabilises.
 */
/**
 * scenenzbs / standard Newznab XXX subcategories. The filter chips let
 * the user toggle multiple at once - Newznab APIs accept a comma-
 * separated `cat=` list, so combining them is a one-line concat.
 */
private data class AdultCategoryPreset(val id: String, val label: String)
private val ADULT_CATEGORY_PRESETS = listOf(
    AdultCategoryPreset("6010", "Movies"),
    AdultCategoryPreset("6020", "DVD"),
    AdultCategoryPreset("6040", "x264"),
    AdultCategoryPreset("6050", "Pack"),
    AdultCategoryPreset("6060", "Imageset"),
    AdultCategoryPreset("6070", "Other"),
)

@Composable
fun V2AdultPage(
    newznab: NewznabClient,
    resolver: NzbResolver,
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

    // Persistent state slot - survives composition teardown when the
    // user taps Play and the player overlay swaps in. See
    // [NzbBrowseStateHolder] for the rationale.
    val pageKey = "adult"
    val saved = remember { NzbBrowseStateHolder.get(pageKey) }

    // Indexer credentials come from Panda config (nzbIndexers + URL
    // resolver). If the user picks built-in scenenzbs, type="scenenzbs"
    // → IndexerUrlResolver returns the canonical URL. If they pick
    // "custom" they typed the URL into Panda already.
    var selectedCategories by remember {
        mutableStateOf(
            saved.selectedCategories.takeIf { it.isNotEmpty() }
                ?: AdultModePreferences.getCategory()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet(),
        )
    }
    var query by remember { mutableStateOf(saved.query) }
    var items by remember { mutableStateOf<List<NewznabItem>>(saved.items) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(saved.errorText) }
    val configured = indexerUrl.isNotBlank() && indexerApiKey.isNotBlank()
    // guid → status text for the row that's currently resolving via TorBox
    var resolveStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var needsFolderPrompt by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved.scrollIndex,
        initialFirstVisibleItemScrollOffset = saved.scrollOffset,
    )

    /**
     * When no chip is selected we want a search to span *all* preset
     * adult categories instead of falling back to a single default -
     * otherwise a search like "blue" against an empty selection would
     * miss every release that lives outside Movies (6010).
     */
    fun activeCategoryParam(): String =
        selectedCategories.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?: ADULT_CATEGORY_PRESETS.joinToString(",") { it.id }

    suspend fun reload() {
        loading = true
        errorText = null
        val cat = activeCategoryParam()
        try {
            // Pull up to 1,000 items by paginating the indexer 100-at-a-
            // time. Newznab caps individual responses at ~100, so without
            // explicit pagination the user only ever saw the freshest
            // page. Run on IO so the UI stays responsive during paging.
            items = withContext(Dispatchers.IO) {
                if (query.isBlank()) {
                    newznab.browseAllPages(indexerUrl, indexerApiKey, cat, maxItems = 1000)
                } else {
                    newznab.searchAllPages(indexerUrl, indexerApiKey, cat, query.trim(), maxItems = 1000)
                }
            }
            if (items.isEmpty() && configured) {
                errorText = "Indexer returned 0 results. Check the selected categories."
            }
        } catch (t: Throwable) {
            errorText = t.message ?: "Indexer call failed."
            items = emptyList()
        }
        loading = false
        // Persist the result snapshot so a Play→return round-trip
        // doesn't lose the user's place.
        NzbBrowseStateHolder.put(
            pageKey,
            NzbBrowseStateHolder.State(
                query = query,
                items = items,
                errorText = errorText,
                scrollIndex = listState.firstVisibleItemIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
                selectedCategories = selectedCategories,
            ),
        )
    }

    LaunchedEffect(indexerUrl, indexerApiKey) {
        // Skip the auto-reload if we already have a cached snapshot -
        // the page should resume exactly where the user left it.
        if (configured && items.isEmpty()) reload()
    }
    // Keep scroll position fresh in the holder so re-entry restores it.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.shellBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)) {
            TorvePageHeader(
                title = ds("Adult"),
                subtitle = "Sourced from your Newznab indexer · categories ${activeCategoryParam()}",
            )

            // Surface a setup prompt when no download client is configured.
            if (!resolver.isConfigured) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = colors.elevatedSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = ds("Download client not configured"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "Direct in-app playback needs a download client. In Panda, set Download client = TorBox (cloud) or enable Usenet with an NzbDAV integration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        TorvePrimaryButton(
                            text = ds("Open Panda setup"),
                            onClick = onOpenPandaSetup,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // Category chips - Panda already supplied the indexer URL +
            // API key, so this is the only knob the user needs here.
            // Toggling fires a reload when the indexer is configured.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ADULT_CATEGORY_PRESETS.forEach { preset ->
                    val selected = preset.id in selectedCategories
                    TorveFilterChip(
                        text = "${preset.label} · ${preset.id}",
                        selected = selected,
                        onClick = {
                            selectedCategories = if (selected) {
                                selectedCategories - preset.id
                            } else {
                                selectedCategories + preset.id
                            }
                            AdultModePreferences.setCategory(activeCategoryParam())
                            if (configured) scope.launch { reload() }
                        },
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
                    label = ds("Filter results (Enter to search)"),
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

            // Sticky banner showing any in-flight TorBox resolution. Stays
            // visible across scrolling so the user always knows something
            // is happening even if the row scrolled offscreen.
            val activeResolutions = resolveStatus.entries
                .filter { (_, msg) ->
                    !msg.startsWith("Failed") &&
                        !msg.contains("error", ignoreCase = true) &&
                        !msg.contains("invalid", ignoreCase = true)
                }
            if (activeResolutions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = colors.accentContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        activeResolutions.forEach { (key, status) ->
                            val title = items.firstOrNull { (it.guid ?: it.nzbUrl) == key }?.title ?: key
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
                        if (indexerSecretsRedacted) {
                            Text(
                                text = ds("Panda credentials are masked"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "Panda has an indexer configured but won't share the API key with this device because the management token isn't cached locally. Open Panda setup → Usenet step → re-paste the indexer API key (and TorBox key if needed) → Update Panda. The Adult page picks them up automatically after that.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        } else {
                            Text(
                                text = ds("No NZB indexer configured in Panda"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "Add an indexer (scenenzbs, NZBgeek, etc.) under Settings → Add-ons → Panda → Configure → Usenet step. The Adult page reads its credentials directly from Panda's config.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                        TorvePrimaryButton(
                            text = ds("Open Panda setup"),
                            onClick = onOpenPandaSetup,
                        )
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
                items.isEmpty() -> Text(
                    "No results.",
                    color = colors.textSecondary,
                )
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.guid ?: it.nzbUrl }) { item ->
                        val rowKey = item.guid ?: item.nzbUrl
                        val status = resolveStatus[rowKey]
                        val isAuthFailure = status?.let { resolver.isAuthError(it) } == true
                        NewznabRow(
                            item = item,
                            statusText = status,
                            torboxConfigured = resolver.isConfigured,
                            showReconfigure = isAuthFailure,
                            downloadEnabled = downloadManager != null,
                            onPlay = {
                                // Set status synchronously BEFORE the launch so the
                                // Play button flips to "Working..." on the same frame
                                // as the click. Critical: dispatch the resolve onto
                                // IO so the blocking HTTP send (`http.send` is sync
                                // in java.net.http) doesn't freeze the Compose
                                // dispatcher - without this, the UI is dead for
                                // ~30s while the NZB downloads from the indexer.
                                if (resolveStatus[rowKey] != null) return@NewznabRow
                                resolveStatus = resolveStatus + (rowKey to "Starting...")
                                println("TORVE ADULT ┃ play clicked: ${item.title}")
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        resolver.resolve(item.nzbUrl) { msg ->
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
                                    // Same resolve path as Play but instead of
                                    // streaming we hand the resolved URL to
                                    // DesktopDownloadManager. queueNzbDownload
                                    // gates on the per-surface folder being
                                    // configured; if not, surface a banner
                                    // that routes to Settings.
                                    if (resolveStatus[rowKey] != null) return@let
                                    resolveStatus = resolveStatus + (rowKey to "Starting...")
                                    println("TORVE ADULT ┃ download clicked: ${item.title}")
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            resolver.resolve(item.nzbUrl) { msg ->
                                                resolveStatus = resolveStatus + (rowKey to msg)
                                            }
                                        }
                                        res.onSuccess { resolved ->
                                            resolveStatus = resolveStatus - rowKey
                                            val outcome = dm.queueNzbDownload(
                                                title = resolved.fileName.ifBlank { item.title },
                                                streamUrl = resolved.streamUrl,
                                                sizeBytes = resolved.sizeBytes,
                                                surface = DesktopDownloadManager.NzbDownloadSurface.ADULT,
                                            )
                                            if (outcome is DesktopDownloadManager.QueueResult.NeedsFolder) {
                                                needsFolderPrompt = "Set an Adult download folder before downloading."
                                            }
                                        }.onFailure { t ->
                                            resolveStatus = resolveStatus + (rowKey to (t.message ?: "Unknown error"))
                                        }
                                    }
                                }
                            },
                            onReconfigure = onOpenPandaSetup,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewznabRow(
    item: NewznabItem,
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
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                val categoryLabel = item.categoryId?.let { id ->
                    ADULT_CATEGORY_PRESETS.firstOrNull { it.id == id }
                        ?.let { "${it.label} · ${it.id}" }
                        ?: id
                }
                Text(
                    text = listOfNotNull(
                        categoryLabel,
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
                TorvePrimaryButton(
                    text = ds("Reconfigure Panda"),
                    onClick = onReconfigure,
                )
            } else {
                // Once an attempt is finished (success or non-auth failure)
                // re-enable Play so the user can retry without a refresh.
                val isWorking = statusText != null && statusText !in listOf("") &&
                    !statusText.startsWith("Failed") && !statusText.startsWith("TorBox error")
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
