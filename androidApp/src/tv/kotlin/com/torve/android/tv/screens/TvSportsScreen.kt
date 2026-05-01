package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.data.usenet.NewznabClient
import com.torve.data.usenet.NewznabItem
import com.torve.data.usenet.TorBoxUsenetClient
import com.torve.domain.sports.SportBucket
import com.torve.domain.usenet.UsenetIndexerCategoryMap
import com.torve.domain.usenet.UsenetIndexerUrlResolver
import com.torve.presentation.panda.PandaConfigStateStore
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.usenet.NzbBrowseStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform

/**
 * TV Sports catalog. Cross-platform Newznab + TorBox plumbing now lives
 * in `shared/`, so this screen is just the TV-flavored UI on top of
 * [NewznabClient] / [TorBoxUsenetClient]: D-pad-friendly bucket pills,
 * a vertical list of focusable release rows, and Play / Resolve handed
 * off to the shared player route.
 *
 * The screen reads its indexer + TorBox credentials directly off the
 * live [PandaConfigStateStore]; if the user hasn't completed Panda
 * setup, the page surfaces a one-line CTA to open it instead of an
 * empty grid.
 */
@Composable
fun TvSportsScreen(
    railFocusRequester: FocusRequester,
    onPlayStream: (url: String, title: String, sizeBytes: Long?) -> Unit,
    onOpenPandaSetup: () -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
    isActive: Boolean = true,
    pandaStore: PandaConfigStateStore = koinInject(),
    newznab: NewznabClient = koinInject(),
    torbox: TorBoxUsenetClient = koinInject(),
) {
    val pandaState by pandaStore.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Hydrate the PandaConfigStateStore by instantiating the VM once.
    // Without this, a user who's never opened the Panda wizard in
    // this app session sees an empty configStateStore — so Sports
    // can't resolve any indexer URL/key even though their Panda
    // setup IS persisted on the backend. The VM's init pulls the
    // saved config from /panda/configs/me and publishes into the
    // singleton store; the resulting StateFlow update re-triggers
    // the credential resolution below. Discarding the VM reference
    // is fine — it's a factory and gets GC'd as soon as the launch
    // finishes; the store retains the hydrated state.
    LaunchedEffect(Unit) {
        runCatching { KoinPlatform.getKoin().get<PandaSetupViewModel>() }
    }

    // Resolve indexer + TorBox credentials from the live Panda state.
    // Mirrors the desktop V2SportsPage logic but reads directly from
    // the store rather than hand-threaded params.
    val activeIndexer = pandaState.nzbIndexers.firstOrNull { it.type != "none" && it.apiKey.isNotBlank() }
    val indexerType = activeIndexer?.type ?: pandaState.nzbIndexer.takeIf { it != "none" } ?: ""
    val indexerUrl = if (activeIndexer != null) {
        UsenetIndexerUrlResolver.resolve(activeIndexer.type, activeIndexer.url)
    } else if (pandaState.nzbIndexer != "none") {
        UsenetIndexerUrlResolver.resolve(pandaState.nzbIndexer, "")
    } else ""
    val indexerKey = activeIndexer?.apiKey
        ?: pandaState.nzbIndexerApiKey.takeIf { it.isNotBlank() }
        ?: ""
    val torboxKey = if (pandaState.downloadClient.equals("torbox", ignoreCase = true) &&
        pandaState.downloadClientApiKey.isNotBlank()
    ) pandaState.downloadClientApiKey else ""
    val configured = indexerUrl.isNotBlank() && indexerKey.isNotBlank()

    val pageKey = "tv_sports"
    val saved = remember { NzbBrowseStateHolder.get(pageKey) }
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved.scrollIndex,
        initialFirstVisibleItemScrollOffset = saved.scrollOffset,
    )

    suspend fun reload() {
        loading = true
        errorText = null
        try {
            val sportsCat = UsenetIndexerCategoryMap.sportsCategoriesFor(indexerType)
            allItems = withContext(Dispatchers.IO) {
                newznab.browseAllPages(indexerUrl, indexerKey, sportsCat, maxItems = 1000)
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
                query = "",
                items = allItems,
                errorText = errorText,
                scrollIndex = listState.firstVisibleItemIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
                selectedSportBucket = selectedBucket?.name,
            ),
        )
    }

    LaunchedEffect(indexerUrl, indexerKey) {
        if (configured && allItems.isEmpty()) reload()
    }

    // Classify each item once per load. Recomputed on filter change.
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

    val firstChipRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { onFirstContentRequester(firstChipRequester) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Sports",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Text(
            text = "Newznab sport releases · classified by release name",
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textSecondary,
        )

        // Bucket filter pills — first focusable row; LEFT off the
        // first pill returns to the nav rail.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "bucket_all") {
                TvBucketChip(
                    label = "All · ${classified.size}",
                    selected = selectedBucket == null,
                    onClick = { selectedBucket = null },
                    focusRequester = firstChipRequester,
                    leftFocusRequester = railFocusRequester,
                    onFocused = { onContentFocused(firstChipRequester) },
                )
            }
            items(SportBucket.entries.filter { b ->
                b != SportBucket.OTHER || (countsByBucket[b] ?: 0) > 0
            }, key = { it.name }) { bucket ->
                val count = countsByBucket[bucket] ?: 0
                TvBucketChip(
                    label = "${bucket.label} · $count",
                    selected = selectedBucket == bucket,
                    onClick = { selectedBucket = bucket },
                )
            }
        }

        when {
            !configured -> NotConfiguredBanner(
                redacted = pandaState.nzbIndexerApiKey.isBlank() &&
                    pandaState.nzbIndexers.any { it.type != "none" },
                onOpenPandaSetup = onOpenPandaSetup,
            )
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Amber) }
            errorText != null -> Surface(
                color = Charcoal,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Couldn't load",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Snow,
                    )
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                }
            }
            visible.isEmpty() -> Text(
                text = if (selectedBucket != null)
                    "No releases classified into ${selectedBucket!!.label} this batch."
                else "No results.",
                color = Torve.colors.textSecondary,
            )
            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(visible, key = { _, ci -> ci.item.guid ?: ci.item.nzbUrl }) { index, ci ->
                    val rowKey = ci.item.guid ?: ci.item.nzbUrl
                    val status = resolveStatus[rowKey]
                    val torboxConfigured = torboxKey.isNotBlank()
                    TvSportsRow(
                        item = ci.item,
                        bucket = ci.bucket,
                        statusText = status,
                        torboxConfigured = torboxConfigured,
                        onPlay = {
                            // IO dispatcher so the blocking NZB upload
                            // doesn't freeze the Compose dispatcher for
                            // the duration of the TorBox round-trip.
                            resolveStatus = resolveStatus + (rowKey to "Starting…")
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    torbox.resolve(ci.item.nzbUrl, torboxKey) { msg ->
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
                        leftFocusRequester = if (index == 0) railFocusRequester else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvBucketChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        selected && focused -> Amber
        selected -> Amber.copy(alpha = 0.85f)
        focused -> Graphite
        else -> Charcoal
    }
    val labelColor = if (selected) Obsidian else Snow
    val borderColor = if (focused) Amber else Color.Transparent
    Surface(
        color = container,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .scale(if (focused) 1.05f else 1f)
            .border(2.dp, borderColor, RoundedCornerShape(50))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (leftFocusRequester != null) Modifier.focusProperties { left = leftFocusRequester }
                else Modifier,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TvSportsRow(
    item: NewznabItem,
    bucket: SportBucket,
    statusText: String?,
    torboxConfigured: Boolean,
    onPlay: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isWorking = statusText != null &&
        !statusText.startsWith("Failed") &&
        !statusText.startsWith("TorBox error")
    val borderColor = if (focused) Amber else Color.Transparent
    val container = if (focused) Graphite else Charcoal
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.02f else 1f)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (leftFocusRequester != null) Modifier.focusProperties { left = leftFocusRequester }
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = torboxConfigured && !isWorking,
                onClick = onPlay,
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = Amber.copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Snow,
                )
                Text(
                    text = listOfNotNull(
                        item.sizeBytes?.let { humanBytes(it) },
                        item.fileCount?.let { "$it files" },
                        item.grabs?.let { "$it grabs" },
                        item.pubDate,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textSecondary,
                )
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                    )
                }
            }
            Text(
                text = if (!torboxConfigured) "TorBox key missing"
                    else if (isWorking) "Working…"
                    else "Play (OK)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (torboxConfigured && !isWorking) Amber else Torve.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun NotConfiguredBanner(redacted: Boolean, onOpenPandaSetup: () -> Unit) {
    Surface(
        color = Charcoal,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (redacted) "Panda credentials are masked"
                    else "No NZB indexer configured in Panda",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Text(
                text = if (redacted)
                    "Panda has an indexer configured but won't share the API key with this device. Re-paste the key in Panda → Usenet → Update."
                else
                    "Set up Usenet via Settings → Connections → Panda → Usenet step. The Sports page reads its credentials directly from Panda.",
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textSecondary,
            )
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
    val whole = (v * 100).toInt() / 100.0
    return "$whole ${units[i]}"
}
