package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.torve.android.R
import com.torve.android.remote.TorveRemoteDialog
import com.torve.android.tv.screens.TvSettingsChoice
import com.torve.android.tv.screens.TvSettingsChoiceDialog
import com.torve.android.ui.detail.SourceLanguagePicker
import com.torve.android.ui.detail.streamUiKey
import com.torve.android.ui.theme.*
import com.torve.data.addon.*
import kotlinx.coroutines.flow.first
import java.util.Locale

/** A dedicated remote-controlled window. Detail rows cannot scroll under its fixed controls. */
@Composable
internal fun TvStreamBrowser(
    title: String, picker: SourceLanguagePicker, sort: StreamSortMode,
    onSort: (StreamSortMode) -> Unit, loading: Boolean,
    presentation: (ParsedStream) -> TvStreamPresentation,
    onRefresh: () -> Unit, onSelect: (ParsedStream, FocusRequester) -> Unit, onDismiss: () -> Unit,
    errorMessage: String? = null,
) {
    TorveRemoteDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TvSettingsStyle {
            Box(Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Back) { onDismiss(); true } else false
            }) {
                TvStreamBrowserContent(title, picker, sort, onSort, loading, presentation, onRefresh, onSelect, errorMessage)
            }
        }
    }
}

@Composable
internal fun TvStreamBrowserContent(
    title: String, picker: SourceLanguagePicker, sort: StreamSortMode,
    onSort: (StreamSortMode) -> Unit, loading: Boolean,
    presentation: (ParsedStream) -> TvStreamPresentation,
    onRefresh: () -> Unit, onSelect: (ParsedStream, FocusRequester) -> Unit,
    errorMessage: String? = null,
) {
    var quality by rememberSaveable { mutableStateOf("") }
    var cached by rememberSaveable { mutableStateOf(false) }
    var choice by remember { mutableStateOf<TvSettingsChoice?>(null) }
    var origin by remember { mutableStateOf<FocusRequester?>(null) }
    val audio = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    val locale = LocalConfiguration.current.locales[0]
    fun languageName(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    val anyLanguage = stringResource(R.string.source_language_any)
    val allSources = stringResource(R.string.tv_source_all)
    val usenet = "Usenet"
    val languageLabel = picker.effectiveLanguage.takeIf { it.isNotEmpty() }?.let(::languageName) ?: anyLanguage
    val sortOptions = listOf(StreamSortMode.DEFAULT to stringResource(R.string.tv_source_best),
        StreamSortMode.SIZE_DESC to stringResource(R.string.tv_source_largest),
        StreamSortMode.SIZE_ASC to stringResource(R.string.tv_source_smallest))
    val sourceOptions = SourceFilters.options(picker.streams).map {
        it.key to when (it.key) { "ALL" -> allSources; "USENET" -> usenet; else -> it.label }
    }
    val audioOptions = listOf("SETTING" to stringResource(R.string.source_language_use_setting), "" to anyLanguage) +
        SourceLanguages.allOptions().map { it.code to "${it.flag} ${languageName(it.code)}" }
    val sortTitle = stringResource(R.string.tv_source_sort)
    val qualityLabel = stringResource(R.string.tv_source_quality)
    val qualityOptions = listOf("" to stringResource(R.string.tv_source_all_qualities)) +
        picker.streams.map { it.quality }.filter { it.isNotBlank() }.distinct().map { it to it }
    val visible = sortStreamResults(picker.visible.filter { (quality.isEmpty() || it.quality == quality) && (!cached || it.isCached) }, sort)
    LaunchedEffect(Unit) {
        snapshotFlow { window.isWindowFocused }.first { it }
        withFrameNanos { }
        audio.requestFocus()
    }
    LaunchedEffect(choice) {
        if (choice == null) origin?.let {
            snapshotFlow { window.isWindowFocused }.first { it }
            withFrameNanos { }
            it.requestFocus(); origin = null
        }
    }
    choice?.let { TvSettingsChoiceDialog(it) { choice = null } }
    val panelShape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth(.92f).fillMaxHeight(.9f).focusGroup()
        .clip(panelShape).background(Charcoal)
        .border(1.dp, Steel, panelShape)
        .padding(24.dp).testTag("tv.sources"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(40.dp).background(Amber, RoundedCornerShape(50)))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tv_streams_pick), color = Snow,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(title, color = Silver, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.tv_source_count, visible.size, picker.streams.size), color = Silver,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("tv.sources.count")
                    .background(Graphite, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().testTag("tv.sources.filters"), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TvSourceControl(languageLabel, Modifier.focusRequester(audio).testTag("source_language_picker"), !picker.effectiveLanguage.isEmpty(), icon = Icons.Default.Language) { req ->
                origin = req; choice = TvSettingsChoice(anyLanguage, picker.language, audioOptions) { picker.language = it }
            }
            TvSourceControl(sourceOptions.firstOrNull { it.first == picker.provider }?.second ?: allSources,
                Modifier.testTag("source_provider_picker"), picker.provider != "ALL", icon = Icons.Default.Dns) { req ->
                origin = req; choice = TvSettingsChoice(allSources, picker.provider, sourceOptions) { picker.provider = it }
            }
            TvSourceControl(sortOptions.first { it.first == sort }.second, Modifier.testTag("tv.sources.sort"), icon = Icons.Default.Sort) { req ->
                origin = req; choice = TvSettingsChoice(sortTitle, sort.name,
                    sortOptions.map { it.first.name to it.second }) { onSort(StreamSortMode.valueOf(it)) }
            }
            TvSourceControl(quality.ifBlank { qualityLabel }, Modifier.testTag("tv.sources.quality"), quality.isNotBlank(), icon = Icons.Default.HighQuality) { req ->
                origin = req; choice = TvSettingsChoice(qualityLabel, quality, qualityOptions) { quality = it }
            }
            TvSourceControl(stringResource(R.string.tv_source_cached), Modifier.testTag("tv.sources.cached"), cached, icon = Icons.Default.CloudDone) { cached = !cached }
            TvSourceControl(stringResource(R.string.channels_refresh), Modifier.testTag("tv.sources.refresh"), enabled = !loading, icon = Icons.Default.Refresh) { onRefresh() }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tv_source_hints), color = Silver, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f), maxLines = 2)
            if (loading || picker.checking) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Amber, strokeWidth = 2.dp)
                Text(stringResource(if (loading) R.string.common_loading else R.string.source_tracks_checking),
                    color = Silver, style = MaterialTheme.typography.labelSmall)
            } else if (picker.remaining > 0 || picker.unavailable > 0) {
                TvSourceControl(stringResource(R.string.source_tracks_more), Modifier.testTag("tv.sources.check")) {
                    if (picker.remaining > 0) picker.nextBatch() else picker.checkMore(retry = true)
                }
            }
        }
        HorizontalDivider(color = Steel)
        errorMessage?.let { Text(localizedUiText(it), color = Ruby, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
        LazyColumn(Modifier.weight(1f).testTag("tv.sources.list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.streamUiKey() }) { stream ->
                val requester = remember(stream.streamUiKey()) { FocusRequester() }
                TvStreamResultRow(presentation(stream), Modifier.focusRequester(requester).testTag("tv.source.${stream.streamUiKey()}"),
                    onClick = { onSelect(stream, requester) })
            }
            if (visible.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.source_language_no_match), color = Snow)
                    TvSourceControl(stringResource(R.string.tv_source_clear), Modifier.testTag("tv.sources.clear")) {
                        picker.language = ""; picker.provider = "ALL"; quality = ""; cached = false
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSourceControl(label: String, modifier: Modifier = Modifier, active: Boolean = false,
    enabled: Boolean = true, icon: ImageVector? = null, onClick: (FocusRequester) -> Unit) {
    val requester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val foreground = when { !enabled -> Ash; active -> Amber; else -> Snow }
    // One clipped surface owns both focus and selection. The default indication
    // would paint a rectangular focus fill outside the rounded pill on Fire TV.
    Row(modifier = modifier.heightIn(min = 42.dp).clip(shape)
        .background(when { focused -> Gunmetal; active -> AmberGlow; else -> Graphite })
        .border(if (focused) 2.dp else 1.dp,
            if (focused) Amber else if (active) Amber.copy(alpha = .45f) else Steel, shape)
        .focusRequester(requester).onFocusChanged { focused = it.isFocused }
        .semantics { selected = active }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick(requester) }
        .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(17.dp))
        Text(localizedUiText(label), color = foreground, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
