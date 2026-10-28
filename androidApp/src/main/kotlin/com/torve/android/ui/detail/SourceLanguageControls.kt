package com.torve.android.ui.detail

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.player.AndroidSourceTrackInspector
import com.torve.data.addon.*
import com.torve.domain.player.PlaybackLanguagePolicy
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Stable
class SourceLanguagePicker internal constructor(
    private val inspector: AndroidSourceTrackInspector,
    private val scope: CoroutineScope,
) {
    var language by mutableStateOf("SETTING")
    var provider by mutableStateOf("ALL")
    private var activelyChecking by mutableStateOf(false)
    var externalChecking by mutableStateOf(false); internal set
    val checking get() = activelyChecking || externalChecking
    var unavailable by mutableIntStateOf(0); private set
    var remaining by mutableIntStateOf(0); private set
    private val attempted = mutableSetOf<String>()
    private var budget = AndroidSourceTrackInspector.BATCH_SIZE
    private var job: Job? = null
    var streams by mutableStateOf<List<ParsedStream>>(emptyList()); internal set
    var preferredLanguage by mutableStateOf(""); internal set
    var allowSubtitles by mutableStateOf(false); internal set
    val effectiveLanguage get() = if (language == "SETTING") preferredLanguage else language
    val visible get() = streams.filter {
        SourceFilters.matches(it, provider) &&
            SourceTrackLanguagePolicy.matchesForBrowsing(it, effectiveLanguage, allowSubtitles)
    }
    fun select(stream: ParsedStream, onSelected: (ParsedStream) -> Unit) {
        stop()
        inspector.selectLanguage(stream, effectiveLanguage, allowSubtitles)
        onSelected(stream)
    }

    fun checkMore(retry: Boolean = false) {
        job?.cancel()
        if (retry) { attempted.clear(); inspector.retryFailed(); unavailable = 0; budget = AndroidSourceTrackInspector.BATCH_SIZE }
        job = scope.launch { checkBatch() }
    }

    internal fun start() { if (job?.isActive != true) job = scope.launch { checkBatch() } }
    internal fun stop() { job?.cancel(); job = null }

    internal suspend fun checkBatch() {
        fun pending() = streams.filter { !inspector.cached(it).trackLanguagesVerified && inspector.canInspect(it) }
            .distinctBy(inspector::key).filter { inspector.key(it) !in attempted }
        activelyChecking = true
        try {
            while (attempted.size < budget) {
                val stream = pending().firstOrNull() ?: break
                val result = inspector.inspect(stream)
                inspector.key(stream)?.let(attempted::add)
                if (!result.trackLanguagesVerified) unavailable += 1
            }
        } finally { remaining = pending().size; activelyChecking = false }
    }

    fun nextBatch() { budget += AndroidSourceTrackInspector.BATCH_SIZE; checkMore() }
}

@Composable
fun rememberSourceLanguagePicker(streams: List<ParsedStream>, enabled: Boolean = true,
    sessionKey: String = "", externalChecking: Boolean = false): SourceLanguagePicker {
    val inspector = koinInject<AndroidSourceTrackInspector>()
    val settings = koinInject<SettingsViewModel>().state.collectAsState().value
    val revision by inspector.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val owner = inspector.owner()
    val picker = remember(owner, sessionKey) { SourceLanguagePicker(inspector, scope) }
    picker.streams = remember(streams, revision, owner) { streams.map(inspector::cached) }
    picker.preferredLanguage = PlaybackLanguagePolicy.preferredLanguage(settings.preferredAudioLanguage, settings.appLanguage.code)
    picker.allowSubtitles = settings.allowSubtitleLanguageFallback
    picker.externalChecking = externalChecking
    // Metadata updates do not start another batch; only new source identities do.
    val identities = streams.map(inspector::key)
    LaunchedEffect(identities, enabled, picker, externalChecking) {
        if (enabled && !externalChecking) picker.start() else picker.stop()
    }
    DisposableEffect(picker) { onDispose { picker.stop() } }
    return picker
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceLanguageControls(picker: SourceLanguagePicker, modifier: Modifier = Modifier,
    languageButtonModifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface) {
    var languagesOpen by remember { mutableStateOf(false) }
    var providersOpen by remember { mutableStateOf(false) }
    val options = SourceLanguages.allOptions()
    val languageLabel = localizedUiText(options.firstOrNull { it.code == picker.effectiveLanguage }?.let { "${it.label} (${it.code})" }
        ?: stringResource(R.string.source_language_any))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                SourceFilterButton(onClick = { languagesOpen = true }, modifier = languageButtonModifier.testTag("source_language_picker")) {
                    Text(localizedUiText(if (picker.language == "SETTING") stringResource(R.string.source_language_setting, languageLabel) else languageLabel))
                }
                DropdownMenu(languagesOpen, { languagesOpen = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                    val choices = listOf("SETTING" to stringResource(R.string.source_language_use_setting), "" to stringResource(R.string.source_language_any)) +
                        options.map { it.code to "${it.label} (${it.code})" }
                    choices.forEach { (code, label) -> DropdownMenuItem(text = { Text(localizedUiText(label)) },
                        modifier = Modifier.testTag("source_language_$code"), onClick = { picker.language = code; languagesOpen = false }) }
                }
            }
            Box {
                SourceFilterButton(onClick = { providersOpen = true }, modifier = Modifier.testTag("source_provider_picker")) {
                    Text(localizedUiText(SourceFilters.options(picker.streams).firstOrNull { it.key == picker.provider }?.label ?: "All sources"))
                }
                DropdownMenu(providersOpen, { providersOpen = false }) {
                    SourceFilters.options(picker.streams).forEach { option ->
                        DropdownMenuItem(text = { Text(if (option.key == "PANDA") localizedUiText("Usenet service") else if (option.key in listOf("ALL", "TORRENT", "USENET")) localizedUiText(option.label) else option.label) }, modifier = Modifier.testTag("source_provider_${option.key}"),
                            onClick = { picker.provider = option.key; providersOpen = false })
                    }
                }
            }
        }
        Text(stringResource(if (picker.allowSubtitles) R.string.source_language_audio_or_subtitles else R.string.source_language_audio_only), style = MaterialTheme.typography.bodySmall, color = textColor)
        if (picker.checking) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.source_tracks_checking), style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        } else if (picker.remaining > 0 || picker.unavailable > 0) {
            TextButton(onClick = { if (picker.remaining > 0) picker.nextBatch() else picker.checkMore(retry = true) }) {
                Text(stringResource(if (picker.remaining > 0) R.string.source_tracks_more else R.string.source_tracks_retry))
            }
        }
        if (!picker.checking && picker.visible.isEmpty()) {
            Text(stringResource(R.string.source_language_no_match), style = MaterialTheme.typography.bodyMedium, color = textColor)
        }
    }
}

@Composable
private fun SourceFilterButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Button(onClick, modifier, interactionSource = interactions,
        colors = ButtonDefaults.buttonColors(containerColor = if (focused) Amber else Gunmetal,
            contentColor = if (focused) Obsidian else Snow), content = content)
}

@Composable
fun SourceTrackLabels(stream: ParsedStream, textColor: Color = MaterialTheme.colorScheme.onSurface,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(stringResource(R.string.source_audio_label, SourceLanguages.label(stream).split(" · ").map { localizedUiText(it) }.joinToString(" · ")),
        style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    Text(stringResource(if (stream.trackLanguagesVerified) R.string.source_tracks_verified else R.string.source_tracks_hint),
        style = MaterialTheme.typography.labelSmall, color = detailColor)
}
