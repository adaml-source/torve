package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.domain.model.MediaItem
import com.torve.domain.model.ProviderSourceStat
import com.torve.presentation.detail.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@Composable
fun ContentCommunityPanel(item: MediaItem, service: ContentCommunity = koinInject(), settings: SettingsViewModel = koinInject()) {
    val preferences by settings.state.collectAsState()
    val identity = "${item.type}:${item.id}:${preferences.appLanguage}:${preferences.personalDataUserId}"
    var facts by remember(identity) { mutableStateOf(emptyList<ContentFact>()) }
    var comments by remember(identity) { mutableStateOf(emptyList<ContentComment>()) }
    var page by remember(identity) { mutableIntStateOf(0) }
    var more by remember(identity) { mutableStateOf(false) }
    var loading by remember(identity) { mutableStateOf(false) }
    var factsFailed by remember(identity) { mutableStateOf(false) }
    var commentsFailed by remember(identity) { mutableStateOf(false) }
    var expanded by remember(identity) { mutableStateOf(false) }
    var opened by remember(identity) { mutableStateOf<ContentComment?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun loadFacts() {
        try { facts = service.facts(item); factsFailed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { factsFailed = true }
    }
    suspend fun loadComments() {
        if (loading) return
        loading = true
        try {
            val result = service.comments(item, page + 1)
            comments = (comments + result.items).distinctBy { it.id }
            page = result.page; more = result.hasMore; commentsFailed = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { commentsFailed = true }
        finally { loading = false }
    }
    LaunchedEffect(identity) { launch { loadFacts() }; launch { loadComments() } }
    Column(Modifier.fillMaxWidth().testTag("detail_community"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.torve.android.ui.components.SectionHeader(localizedUiText("Comments"), contentPadding = PaddingValues(0.dp))
        Text(localizedUiText("Trakt · Community opinions may contain spoilers"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
        if (commentsFailed) TextButton(onClick = { scope.launch { loadComments() } }) { Text(localizedUiText("Retry comments")) }
        else if (!loading && comments.isEmpty()) Text(localizedUiText("No Trakt comments are available for this title."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MobileCommentCarousel(if (expanded) comments else comments.take(8)) { opened = it }
        if (!expanded && comments.isNotEmpty()) TextButton(onClick = { expanded = true }) { Text(localizedUiText("See all comments")) }
        if (expanded && more) TextButton(onClick = { scope.launch { loadComments() } }, enabled = !loading) { Text(localizedUiText("Load more comments")) }
        Spacer(Modifier.height(16.dp))
        com.torve.android.ui.components.SectionHeader(localizedUiText("Details"), modifier = Modifier.testTag("detail_facts"), contentPadding = PaddingValues(0.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (factsFailed) TextButton(onClick = { scope.launch { loadFacts() } }) { Text(localizedUiText("Retry details")) }
        facts.forEach { fact ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(localizedUiText(fact.label), Modifier.weight(0.42f), fontWeight = FontWeight.Medium)
                val value = when {
                    fact.label.endsWith("(USD)") -> fact.value.toLongOrNull()?.let { NumberFormat.getCurrencyInstance().apply {
                        currency = Currency.getInstance("USD"); maximumFractionDigits = 0
                    }.format(it) } ?: fact.value
                    fact.label == "Original language" -> Locale.forLanguageTag(fact.value).getDisplayLanguage(Locale.getDefault())
                    else -> fact.value
                }
                Text(value, Modifier.weight(0.58f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        if (!factsFailed && facts.isEmpty()) Text(localizedUiText("No additional details available."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
    opened?.let { comment -> AlertDialog(onDismissRequest = { opened = null }, title = { Text(comment.author) },
        text = { Text(comment.comment.replace("[spoiler]", "", ignoreCase = true).replace("[/spoiler]", "", ignoreCase = true), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { opened = null }) { Text(localizedUiText("Done")) } }) }
}

@Composable
fun RemainingEpisodesBadge(count: Int, enabled: Boolean = true, modifier: Modifier = Modifier) {
    if (enabled && count > 0) Text("+$count", modifier.background(Color(0xFFD32F2F), RoundedCornerShape(6.dp))
        .padding(horizontal = 7.dp, vertical = 3.dp).semantics { contentDescription = "$count aired episodes left to watch" }
        .testTag("episodes_remaining"), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

@Composable
fun SourceStatisticsButton(rows: List<ProviderSourceStat>, loading: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("sources_stats")) { Text(localizedUiText("Source statistics")) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(localizedUiText("Source statistics")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localizedUiText("Response time measures the provider search, including retries. It is not network ping. Cached counts are provider reports before deduplication and your filters."))
            if (loading) Text(localizedUiText("Searching providers…"))
            if (rows.isEmpty() && !loading) Text(localizedUiText("No completed provider searches for this lookup yet."))
            rows.forEach { row ->
                Text(row.provider, fontWeight = FontWeight.SemiBold)
                Text(localizedUiText("Found ${row.found} · Cached ${row.cached}"))
                Text(localizedUiText((if (row.fromCache) "Reused search · Previous response " else "Response ") + "${row.responseMs} ms"))
                if (row.failed) Text(localizedUiText("Provider search failed or timed out"), color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(localizedUiText("Done")) } })
}
