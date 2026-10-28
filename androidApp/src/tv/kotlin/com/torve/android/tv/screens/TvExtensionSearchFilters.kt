package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun TvExtensionSearchFilterDialog(
    filters: TvExtensionSearchFilters,
    genres: List<String>,
    apply: (TvExtensionSearchFilters) -> Unit,
    dismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(filters) }
    var picker by remember { mutableStateOf<TvSettingsChoice?>(null) }
    val rows = remember { List(8) { FocusRequester() } }
    val applyRequester = remember { FocusRequester() }
    var returnIndex by remember { mutableIntStateOf(0) }
    val years = remember { listOf("" to "Any") + ((java.time.Year.now().value + 2) downTo 1900).map { "$it" to "$it" } }
    Dialog(onDismissRequest = { if (picker != null) picker = null else dismiss() }) {
        Surface(shape = RoundedCornerShape(18.dp), color = com.torve.android.ui.theme.menuPanelColor(true), contentColor = Snow,
            modifier = Modifier.width(600.dp).heightIn(max = 480.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localizedUiText(picker?.title ?: "Filters"), style = MaterialTheme.typography.headlineSmall)
                if (picker == null) {
                    Text(localizedUiText("Ratings use IMDb. Filters apply across the installed search catalogues."), color = Silver, style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        @Composable fun field(index: Int, label: String, choice: () -> TvSettingsChoice) {
                            TvSearchToolbarButton(label, Modifier.fillMaxWidth().focusRequester(rows[index]).focusProperties {
                                up = if (index == 0) FocusRequester.Cancel else rows[index - 1]
                                down = if (index == 5) applyRequester else rows[index + 1]
                            }, onMoveDown = { (if (index == 5) applyRequester else rows[index + 1]).requestFocus() },
                                onFocused = { returnIndex = index }, onClick = { returnIndex = index; picker = choice() })
                        }
                        field(0, localizedUiText("Genre: ${draft.genre ?: "All genres"}")) { TvSettingsChoice("Genre", draft.genre.orEmpty(),
                            listOf("" to "All genres") + genres.map { it to it }) { draft = draft.copy(genre = it.takeIf(String::isNotEmpty)) } }
                        field(1, localizedUiText("Release year from: ${draft.fromYear ?: "Any"}")) { TvSettingsChoice("Release year from", draft.fromYear?.toString().orEmpty(), years) {
                            val year = it.toIntOrNull(); draft = draft.copy(fromYear = year, throughYear = draft.throughYear?.takeIf { last -> year == null || last >= year }) } }
                        field(2, localizedUiText("Release year through: ${draft.throughYear ?: "Any"}")) { TvSettingsChoice("Release year through", draft.throughYear?.toString().orEmpty(), years) {
                            val year = it.toIntOrNull(); draft = draft.copy(throughYear = year, fromYear = draft.fromYear?.takeIf { first -> year == null || first <= year }) } }
                        field(3, localizedUiText("Minimum IMDb rating: ${draft.minImdb?.let { "$it / 10" } ?: "Any"}")) { TvSettingsChoice("Minimum IMDb rating", draft.minImdb?.toString().orEmpty(),
                            listOf("" to "Any") + (1..19).map { (it / 2f).toString() to "${it / 2f} / 10" }) { draft = draft.copy(minImdb = it.toFloatOrNull()) } }
                        field(4, localizedUiText("Runtime: ${when (draft.runtime) { "short" -> "Under 90 minutes"; "medium" -> "90–120 minutes"; "long" -> "Over 120 minutes"; else -> "Any" }}")) {
                            TvSettingsChoice("Runtime", draft.runtime.orEmpty(), listOf("" to "Any", "short" to "Under 90 minutes", "medium" to "90–120 minutes", "long" to "Over 120 minutes")) { draft = draft.copy(runtime = it.takeIf(String::isNotEmpty)) } }
                        field(5, localizedUiText("Sort: ${when (draft.sort) { "imdb" -> "Highest IMDb rating"; "newest" -> "Newest first"; "oldest" -> "Oldest first"; "title" -> "Title"; else -> "Relevance" }}")) {
                            TvSettingsChoice("Sort", draft.sort, listOf("relevance" to "Relevance", "imdb" to "Highest IMDb rating", "newest" to "Newest first", "oldest" to "Oldest first", "title" to "Title")) { draft = draft.copy(sort = it) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvSearchToolbarButton(localizedUiText("Apply filters"), Modifier.focusRequester(applyRequester), onFocused = {}, onMoveDown = {}, onClick = { apply(draft) })
                        TvSearchToolbarButton(localizedUiText("Clear filters"), onFocused = {}, onMoveDown = {}, onClick = { draft = TvExtensionSearchFilters(type = draft.type) })
                        TvSearchToolbarButton(localizedUiText("Cancel"), onFocused = {}, onMoveDown = {}, onClick = dismiss)
                    }
                } else {
                    val choice = picker!!
                    TvExtensionFilterChoices(choice) { picker = null }
                }
            }
            LaunchedEffect(picker) {
                if (picker == null) { withFrameNanos { }; rows[returnIndex].requestFocus() }
            }
        }
    }
}

@Composable
private fun TvExtensionFilterChoices(choice: TvSettingsChoice, done: () -> Unit) {
    val index = choice.options.indexOfFirst { it.first == choice.selected }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
    val selected = remember(choice) { FocusRequester() }
    BackHandler { done() }
    LazyColumn(Modifier.height(290.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(choice.options, key = { _, item -> item.first }) { itemIndex, item ->
            TvSearchToolbarButton(item.second,
                Modifier.fillMaxWidth().then(if (itemIndex == index) Modifier.focusRequester(selected) else Modifier),
                selected = item.first == choice.selected, onFocused = {}, onMoveDown = null,
                onClick = { choice.select(item.first); done() })
        }
    }
    TvSearchToolbarButton(localizedUiText("Back to filters"), onFocused = {}, onMoveDown = {}, onClick = done)
    LaunchedEffect(choice) { withFrameNanos { }; selected.requestFocus() }
}
