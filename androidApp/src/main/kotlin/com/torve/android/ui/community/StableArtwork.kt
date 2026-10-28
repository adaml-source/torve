package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.runtime.*
import com.torve.domain.model.MediaItem
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/** Destination-independent, bounded selections survive lazy row recycling and late enrichment. */
internal class ArtworkSelections(private val capacity: Int = 2048, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(var selected: String? = null, val failed: MutableMap<String, Long> = mutableMapOf())
    private val entries = linkedMapOf<String, Entry>()
    @Synchronized fun choose(key: String, vararg candidates: String?): String? {
        val entry = entries.getOrPut(key) { Entry() }
        entry.failed.entries.removeAll { now() - it.value >= 30_000 }
        if (entry.selected == null) entry.selected = candidates.firstOrNull { it != null && it !in entry.failed }
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return entry.selected
    }
    @Synchronized fun failed(key: String, current: String?, vararg candidates: String?): String? {
        val entry = entries.getOrPut(key) { Entry() }
        current?.let { entry.failed[it] = now() }; entry.selected = null
        return choose(key, *candidates)
    }
    @Synchronized fun clear() = entries.clear()
}

private val artworkSelections = ArtworkSelections()
private val failedArtworkUrls = mutableStateMapOf<String, Long>()

internal fun artworkFailureStamp(item: MediaItem): Long =
    listOfNotNull(item.posterUrl, item.backdropUrl, item.titlelessBackdropUrl).maxOfOrNull { failedArtworkUrls[it] ?: 0L } ?: 0L

internal fun preferUsableArtwork(current: String?, other: String?): String? =
    if (current.isNullOrBlank() || ((failedArtworkUrls[current] ?: 0L) > 0 && !other.isNullOrBlank() && other != current))
        other?.takeIf { it.isNotBlank() } ?: current else current
val LocalArtworkScope = staticCompositionLocalOf { "guest:en" }

internal fun artworkCandidates(item: MediaItem, landscape: Boolean, preferTitleless: Boolean = false): List<String> {
    if (item.isContentPlaceholder || item.isStubDetail || item.catalogueNotice != null) return emptyList()
    val urls = if (landscape) listOf(if (preferTitleless) item.titlelessBackdropUrl else null, item.backdropUrl, item.posterUrl)
        else listOf(item.posterUrl, item.backdropUrl)
    return urls.mapNotNull { it?.trim()?.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") } }.distinct()
}

@Composable
internal fun stableArtwork(item: MediaItem, landscape: Boolean, preferTitleless: Boolean = false): Pair<String?, () -> Unit> {
    val scope = LocalArtworkScope.current
    val key = "$scope:${item.type}:${item.tmdbId ?: item.id}:$landscape"
    val allowed = !item.isContentPlaceholder && !item.isStubDetail && item.catalogueNotice == null
    val candidates = artworkCandidates(item, landscape, preferTitleless).toTypedArray()
    var selected by remember(key, allowed) { mutableStateOf(if (allowed) artworkSelections.choose(key, *candidates) else null) }
    var retries by remember(key, allowed) { mutableIntStateOf(0) }
    LaunchedEffect(key, allowed, candidates.toList(), selected) {
        if (allowed && selected == null) {
            selected = artworkSelections.choose(key, *candidates)
            if (selected == null && candidates.isNotEmpty() && retries < 2) {
                kotlinx.coroutines.delay(30_100)
                retries++
                selected = artworkSelections.choose(key, *candidates)
            }
        }
    }
    return selected to {
        selected?.let { failedArtworkUrls[it] = System.currentTimeMillis() }
        while (failedArtworkUrls.size > 512) failedArtworkUrls.remove(failedArtworkUrls.keys.first())
        selected = artworkSelections.failed(key, selected, *candidates)
    }
}

object CommunityNotices {
    var message by mutableStateOf<String?>(null)
        private set
    fun handle(item: MediaItem): Boolean = item.catalogueNotice?.let { message = it; true } ?: false
    fun close() { message = null }
}

@Composable
fun CatalogueNoticeCard(item: MediaItem, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    CommunityAction(localizedUiText("Extension needs attention\n\n${item.catalogueNotice.orEmpty()}\n\nReview settings"),
        { CommunityNotices.handle(item) }, modifier.heightIn(min = 140.dp)
            .onFocusChanged { if (it.isFocused) onFocused() })
}

@Composable
fun CatalogueNoticeHost(openSettings: () -> Unit) {
    CommunityNotices.message?.let { message ->
        androidx.compose.material3.AlertDialog(onDismissRequest = CommunityNotices::close,
            title = { androidx.compose.material3.Text(localizedUiText("Extension needs attention")) },
            text = { androidx.compose.material3.Text(localizedUiText(message)) },
            confirmButton = { CommunityAction(localizedUiText("Extension settings"), { CommunityNotices.close(); openSettings() }) },
            dismissButton = { CommunityAction(localizedUiText("Close"), CommunityNotices::close) })
    }
}
