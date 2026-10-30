package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import com.torve.android.tv.components.tvGlassControl
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.remote.TorveRemotePopup as Popup
import androidx.compose.ui.window.PopupProperties
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** Keep the normal route to results short; history and view choices belong in popups. */
@Composable
internal fun TvSearchToolbar(
    screenId: String,
    query: String,
    aiMode: Boolean,
    hasAiKey: Boolean,
    filterType: String?,
    activeFilterCount: Int,
    density: TvSearchDensity,
    recentSearches: List<String>,
    inputRequester: FocusRequester,
    allRequester: FocusRequester,
    movieRequester: FocusRequester,
    tvRequester: FocusRequester,
    filtersRequester: FocusRequester,
    viewRequester: FocusRequester,
    railRequester: FocusRequester,
    resultsRequester: FocusRequester,
    openSearchNonce: Int = 0,
    onMoveDown: () -> Unit,
    onQueryCommitted: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onToggleAi: () -> Unit,
    onFilterTypeChange: (String?) -> Unit,
    onOpenFilters: () -> Unit,
    onDensityChange: (TvSearchDensity) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    suggestTitles: (String) -> Flow<List<MediaItem>> = { flowOf(emptyList()) },
) {
    val controller = rememberTvModalFocusRestoreController("$screenId:toolbar")
    val searchTarget = remember(screenId) { TvFocusTargetId(screenId, "toolbar", "search", 0, 0, "button") }
    val viewTarget = remember(screenId) { TvFocusTargetId(screenId, "toolbar", "view", 0, 5, "button") }
    val searchRequester = rememberRegisteredTvFocusRequester(controller, searchTarget, inputRequester)
    val sizeRequester = rememberRegisteredTvFocusRequester(controller, viewTarget, viewRequester)
    var searchOpen by remember { mutableStateOf(false) }
    var viewOpen by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    fun dismiss() {
        searchOpen = false
        viewOpen = false
        controller.requestRestore(reason = "search_toolbar_popup_dismiss")
    }
    LaunchedEffect(searchOpen, viewOpen, controller.pendingRestore?.restoreToken, windowInfo.isWindowFocused) {
        if (!searchOpen && !viewOpen && windowInfo.isWindowFocused) controller.restorePendingFocus(screenId)
    }
    LaunchedEffect(openSearchNonce) {
        if (openSearchNonce > 0) {
            controller.captureOrigin(searchTarget, reason = "search_feedback_edit")
            searchOpen = true
        }
    }
    Row(Modifier.fillMaxWidth().height(44.dp).testTag("search_toolbar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TvSearchToolbarButton(
            label = localizedUiText(query.ifBlank { "Search" }), icon = true,
            modifier = Modifier.weight(1f).focusRequester(searchRequester)
                .focusProperties { left = railRequester; right = allRequester; down = resultsRequester },
            onMoveDown = onMoveDown,
            onFocused = { controller.markFocused(searchTarget); onContentFocused(searchRequester) },
            onClick = { controller.captureOrigin(searchTarget, reason = "search_query_open"); searchOpen = true },
        )
        listOf(Triple("All", null, allRequester), Triple("Movies", "movie", movieRequester),
            Triple("Shows", "tv", tvRequester)).forEachIndexed { index, (label, type, requester) ->
            TvSearchToolbarButton(label, selected = filterType == type,
                modifier = Modifier.focusRequester(requester).focusProperties {
                    left = listOf(searchRequester, allRequester, movieRequester)[index]
                    right = listOf(movieRequester, tvRequester, filtersRequester)[index]
                    down = resultsRequester
                }, onMoveDown = onMoveDown, onFocused = { onContentFocused(requester) },
                onClick = { onFilterTypeChange(type) })
        }
        TvSearchToolbarButton(localizedUiText(if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters"),
            modifier = Modifier.focusRequester(filtersRequester).focusProperties {
                left = tvRequester; right = sizeRequester; down = resultsRequester
            }, onMoveDown = onMoveDown, onFocused = { onContentFocused(filtersRequester) }, onClick = onOpenFilters)
        TvSearchToolbarButton(localizedUiText("View"), modifier = Modifier.focusRequester(sizeRequester).focusProperties {
            left = filtersRequester; right = sizeRequester; down = resultsRequester
        }, onMoveDown = onMoveDown,
            onFocused = { controller.markFocused(viewTarget); onContentFocused(sizeRequester) },
            onClick = { controller.captureOrigin(viewTarget, reason = "search_view_open"); viewOpen = true })
    }
    if (searchOpen) {
        TvSearchQueryPopup(query, aiMode, hasAiKey, recentSearches,
            onSubmit = { onQueryCommitted(it); dismiss() },
            onRemoveRecent = onRemoveRecent, onClearRecent = onClearRecent,
            onToggleAi = onToggleAi, onDismiss = ::dismiss, suggestTitles = suggestTitles)
    }
    if (viewOpen) {
        TvSearchViewPopup(density, onSelect = { onDensityChange(it); dismiss() }, onDismiss = ::dismiss)
    }
}

@Composable
internal fun TvSearchToolbarButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: Boolean = false,
    onMoveDown: (() -> Unit)? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.height(44.dp).tvGlassControl(focused, selected, cornerRadius = 10.dp)
        .semantics { this.selected = selected }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionDown && onMoveDown != null) { if (event.type == KeyEventType.KeyDown) onMoveDown(); true } else false
        }.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon) Icon(Icons.Default.Search, contentDescription = null, tint = if (focused) AmberLight else Silver,
            modifier = Modifier.size(20.dp))
        Text(localizedUiText(label), color = if (focused) AmberLight else if (selected) Snow else Silver,
            style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TvSearchQueryPopup(
    query: String,
    aiMode: Boolean,
    hasAiKey: Boolean,
    recentSearches: List<String>,
    onSubmit: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onToggleAi: () -> Unit,
    onDismiss: () -> Unit,
    suggestTitles: (String) -> Flow<List<MediaItem>> = { flowOf(emptyList()) },
) {
    var draft by remember { mutableStateOf(query) }
    var suggestions by remember(draft, aiMode) { mutableStateOf(emptyList<MediaItem>()) }
    val suggestionSource by rememberUpdatedState(suggestTitles)
    LaunchedEffect(draft, aiMode) {
        val text = draft.trim()
        if (aiMode || text.length < 2) return@LaunchedEffect
        delay(250)
        try {
            withTimeoutOrNull(2_500) {
                suggestionSource(text).collect { items ->
                    suggestions = mergeTvTitleSearch(text, emptyList(), items).take(4)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { suggestions = emptyList() }
    }
    val input = remember { FocusRequester() }
    val submit = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val mode = remember { FocusRequester() }
    val clear = remember { FocusRequester() }
    val terms = remember { mutableMapOf<String, FocusRequester>() }
    val removes = remember { mutableMapOf<String, FocusRequester>() }
    val firstRecent = recentSearches.firstOrNull()?.let { terms.getOrPut(it) { FocusRequester() } }
    val suggestionRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val firstSuggestion = suggestions.firstOrNull()?.let { suggestionRequesters.getOrPut(it.id) { FocusRequester() } }
    val firstChoice = firstSuggestion ?: firstRecent ?: submit
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        onDispose {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    TvSearchPopup(input, onDismiss) {
        Text(localizedUiText("Search"), color = Snow, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(localizedUiText(if (aiMode) "Describe what you would like to watch" else "Find a movie or show by title"),
            color = Silver, style = MaterialTheme.typography.bodyMedium)
        TvSearchCompactInput(draft, { draft = it }, localizedUiText("Enter a title or description"),
            onMoveDown = { firstChoice.requestFocus(); true }, restingFocusRequester = input,
            modifier = Modifier.testTag("search_query_input").fillMaxWidth().height(48.dp).background(Gunmetal, RoundedCornerShape(10.dp))
                .focusRequester(input).focusProperties { down = firstChoice; right = if (hasAiKey) mode else cancel },
            onSubmit = { onSubmit(draft.trim()) })
        if (suggestions.isNotEmpty()) {
            Text(localizedUiText("Suggestions"), color = Silver, style = MaterialTheme.typography.labelLarge)
            Column(Modifier.fillMaxWidth().heightIn(max = 174.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestions.forEachIndexed { index, item -> key(item.id) {
                    val requester = suggestionRequesters.getOrPut(item.id) { FocusRequester() }
                    val up = suggestions.getOrNull(index - 1)?.let { suggestionRequesters.getOrPut(it.id) { FocusRequester() } } ?: input
                    val down = suggestions.getOrNull(index + 1)?.let { suggestionRequesters.getOrPut(it.id) { FocusRequester() } } ?: (firstRecent ?: submit)
                    TvSearchToolbarButton(item.title, Modifier.fillMaxWidth().testTag("search_suggestion:${item.id}")
                        .focusRequester(requester).focusProperties { this.up = up; this.down = down; left = requester; right = requester },
                        onMoveDown = { down.requestFocus() }, onFocused = {}, onClick = { onSubmit(item.title) })
                } }
            }
        }
        if (recentSearches.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(localizedUiText("Recent searches"), color = Silver, style = MaterialTheme.typography.labelLarge)
                TvSearchToolbarButton(localizedUiText("Clear history"), modifier = Modifier.focusRequester(clear).focusProperties {
                    up = input; down = firstRecent ?: submit; left = input; right = clear
                }, onMoveDown = { (firstRecent ?: submit).requestFocus() }, onFocused = {},
                    onClick = { input.requestFocus(); onClearRecent() })
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 174.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recentSearches.forEachIndexed { index, term -> key(term) {
                    val requester = terms.getOrPut(term) { FocusRequester() }
                    val remove = removes.getOrPut(term) { FocusRequester() }
                    val up = recentSearches.getOrNull(index - 1)?.let { terms.getOrPut(it) { FocusRequester() } }
                        ?: suggestions.lastOrNull()?.let { suggestionRequesters.getOrPut(it.id) { FocusRequester() } } ?: input
                    val down = recentSearches.getOrNull(index + 1)?.let { terms.getOrPut(it) { FocusRequester() } } ?: submit
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TvSearchToolbarButton(term, Modifier.weight(1f).focusRequester(requester)
                            .focusProperties { this.up = up; this.down = down; left = requester; right = remove },
                            onMoveDown = { down.requestFocus() }, onFocused = {}, onClick = { onSubmit(term) })
                        TvSearchRemoveRecentButton(term, Modifier.focusRequester(remove).focusProperties {
                            this.up = up; this.down = down; left = requester; right = remove
                        }, onClick = { input.requestFocus(); onRemoveRecent(term) })
                    }
                } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvActionButton(com.torve.android.tv.components.tvSettingsLabel("Search"), Modifier.focusRequester(submit).focusProperties {
                up = recentSearches.lastOrNull()?.let { terms.getOrPut(it) { FocusRequester() } }
                    ?: suggestions.lastOrNull()?.let { suggestionRequesters.getOrPut(it.id) { FocusRequester() } } ?: input
                right = cancel; left = submit; down = submit
            }, isPrimary = true, onFocused = {}, onClick = { onSubmit(draft.trim()) })
            TvActionButton(com.torve.android.tv.components.tvSettingsLabel("Cancel"), Modifier.focusRequester(cancel).focusProperties {
                up = input; left = submit; right = if (hasAiKey) mode else cancel; down = cancel
            }, onFocused = {}, onClick = onDismiss)
            if (hasAiKey) {
                TvSearchToolbarButton(localizedUiText(if (aiMode) "AI discovery: On" else "AI discovery: Off"),
                    modifier = Modifier.focusRequester(mode).focusProperties {
                        up = input; left = cancel; right = mode; down = mode
                    }, onMoveDown = {}, onFocused = {}, onClick = onToggleAi)
            }
        }
    }
}

@Composable
private fun TvSearchRemoveRecentButton(term: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(44.dp).background(if (focused) Gunmetal else Charcoal, RoundedCornerShape(10.dp))
        .border(2.dp, if (focused) AmberLight else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Close, contentDescription = localizedUiText("Remove recent search: $term"),
            tint = if (focused) AmberLight else Silver, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun TvSearchViewPopup(density: TvSearchDensity, onSelect: (TvSearchDensity) -> Unit, onDismiss: () -> Unit) {
    val requesters = remember { TvSearchDensity.entries.associateWith { FocusRequester() } }
    val close = remember { FocusRequester() }
    // Keep the entry requester stable while a setting callback recomposes the popup.
    val initial = remember { requesters.getValue(density) }
    TvSearchPopup(initial, onDismiss) {
        Text(localizedUiText("Poster size"), color = Snow, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        TvSearchDensity.entries.forEachIndexed { index, option ->
            val requester = requesters.getValue(option)
            TvSearchToolbarButton(localizedUiText("${option.label} · ${option.columns} per row"), selected = density == option,
                modifier = Modifier.fillMaxWidth().focusRequester(requester).focusProperties {
                    up = TvSearchDensity.entries.getOrNull(index - 1)?.let { requesters.getValue(it) } ?: requester
                    down = TvSearchDensity.entries.getOrNull(index + 1)?.let { requesters.getValue(it) } ?: close
                    left = requester; right = requester
                }, onMoveDown = { (TvSearchDensity.entries.getOrNull(index + 1)?.let { requesters.getValue(it) } ?: close).requestFocus() },
                onFocused = {}, onClick = { onSelect(option) })
        }
        TvActionButton(com.torve.android.tv.components.tvSettingsLabel("Close"), Modifier.focusRequester(close).focusProperties {
            up = requesters.getValue(TvSearchDensity.COMPACT); down = close
        }, onFocused = {}, onClick = onDismiss)
    }
}

@Composable
internal fun TvSearchPopup(initialRequester: FocusRequester, onDismiss: () -> Unit, framed: Boolean = true, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Popup(alignment = Alignment.Center,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false),
        onDismissRequest = onDismiss) {
        val windowInfo = LocalWindowInfo.current
        var contentHasFocus by remember { mutableStateOf(false) }
        var selectStartedHere by remember { mutableStateOf(false) }
        LaunchedEffect(windowInfo.isWindowFocused, initialRequester) {
            if (!windowInfo.isWindowFocused) return@LaunchedEffect
            repeat(24) {
                withFrameNanos { }
                runCatching { initialRequester.requestFocus() }
                withFrameNanos { }
                if (contentHasFocus) return@LaunchedEffect
            }
        }
        Box(Modifier.fillMaxSize().background(Obsidian.copy(alpha = 0.88f)).padding(24.dp)
            .onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup()
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) { selectStartedHere = true; false }
                        else { val openingRelease = !selectStartedHere; selectStartedHere = false; openingRelease }
                    }
                    Key.Back, Key.Escape -> { if (event.type == KeyEventType.KeyUp) onDismiss(); true }
                    else -> false
                }
            }, contentAlignment = Alignment.Center) {
            if (framed) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Charcoal).border(1.dp, Steel, RoundedCornerShape(16.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = { content() })
            } else content()
        }
    }
}
