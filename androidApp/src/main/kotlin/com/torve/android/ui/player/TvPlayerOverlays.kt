package com.torve.android.ui.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.player.AudioEqualizer
import com.torve.android.player.EqPreset
import com.torve.android.tv.settings.rememberTvReduceMotionPreference
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Sapphire
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.data.subtitles.languageInfo
import com.torve.data.subtitles.SubtitleEvidence
import com.torve.data.subtitles.SubtitleEvidenceState
import com.torve.data.subtitles.SubtitleMatchQuality
import com.torve.data.subtitles.SubtitleMatchTier
import com.torve.data.subtitles.SubtitleSortMode
import com.torve.data.subtitles.humanReadableSubtitleName
import com.torve.data.subtitles.parseSubtitleRelease
import com.torve.data.subtitles.subtitleLanguagesMatch
import com.torve.domain.player.TrackDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TrackTab {
    SUBTITLES,
    AUDIO,
}

@Composable
fun TvTrackSelectionOverlay(
    subtitleTracks: List<TrackDescription>,
    audioTracks: List<TrackDescription>,
    showSubtitlesOnly: Boolean = true,
    initialTab: Int = 0,
    onSelectSubtitle: (TrackDescription?) -> Unit,
    onSelectAudio: (TrackDescription) -> Unit,
    onDismiss: () -> Unit,
    onDownloadSubtitles: (() -> Unit)? = null,
    onSubtitleDelay: (() -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)

    val subtitlesLabel = stringResource(R.string.player_subtitles)
    val audioLabel = stringResource(R.string.player_audio)

    // Never show tabs — each button opens the relevant section directly.
    // selectedTab is derived from which button was pressed.
    val selectedTab = if (showSubtitlesOnly) TrackTab.SUBTITLES else TrackTab.AUDIO
    val tabs = listOf(selectedTab)

    val tabRequesters = remember(tabs) { List(tabs.size) { FocusRequester() } }
    val firstRowRequester = remember { FocusRequester() }
    var dismissKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(tabs) {
        if (tabs.size > 1) {
            requestFocusWithRetry(tabRequesters.firstOrNull())
        } else {
            requestFocusWithRetry(firstRowRequester)
        }
    }

    // Auto-dismiss after 5s of inactivity; resets on every user interaction.
    LaunchedEffect(dismissKey) {
        delay(3_000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.96f))
            .onPreviewKeyEvent { dismissKey++; false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.player_track_selection),
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (showSubtitlesOnly) subtitlesLabel else audioLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
                modifier = Modifier.padding(top = 6.dp),
            )

            val showSubtitles = showSubtitlesOnly

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showSubtitles) {
                    val allOff = subtitleTracks.none { it.isSelected }
                    item(key = "subtitle_off") {
                        TvTrackRow(
                            label = stringResource(R.string.common_off),
                            isSelected = allOff,
                            modifier = Modifier.focusRequester(firstRowRequester),
                            onClick = { onSelectSubtitle(null) },
                        )
                    }
                    items(subtitleTracks, key = { "sub_${it.id}_${it.label}" }) { track ->
                        SubtitleTrackRow(
                            track = track,
                            onClick = { onSelectSubtitle(track) },
                        )
                    }
                    if (onSubtitleDelay != null) {
                        item(key = "subtitle_delay") {
                            TvTrackRow(
                                label = "Subtitle Delay",
                                isSelected = false,
                                leadingIcon = Icons.Default.Timer,
                                onClick = { dismissKey++; onSubtitleDelay() },
                            )
                        }
                    }
                    if (onDownloadSubtitles != null) {
                        item(key = "download_subtitles") {
                            TvTrackRow(
                                label = stringResource(R.string.player_download_subtitles),
                                isSelected = false,
                                leadingIcon = Icons.Default.FileDownload,
                                onClick = { dismissKey++; onDownloadSubtitles() },
                            )
                        }
                    }
                } else {
                    if (audioTracks.isEmpty()) {
                        item(key = "no_audio_tracks") {
                            Text(
                                text = "No audio tracks available.",
                                color = Silver,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            )
                        }
                    }
                    items(audioTracks, key = { "audio_${it.id}_${it.label}" }) { track ->
                        AudioTrackRow(
                            track = track,
                            modifier = if (tabs.size <= 1 && audioTracks.firstOrNull() == track) {
                                Modifier.focusRequester(firstRowRequester)
                            } else {
                                Modifier
                            },
                            onClick = { onSelectAudio(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TvAudioDelayOverlay(
    currentDelayMs: Int,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var localDelay by remember(currentDelayMs) { mutableIntStateOf(currentDelayMs) }
    val sliderFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        requestFocusWithRetry(sliderFocusRequester)
    }

    val delayLabel = stringResource(R.string.player_audio_delay_value, localDelay)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .clip(RoundedCornerShape(18.dp))
                .background(Charcoal)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.player_audio_delay),
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Left/Right adjusts by 100 ms. Save applies and closes.",
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
            )
            TvDpadSlider(
                value = localDelay,
                range = -2000..2000,
                step = 100,
                label = stringResource(R.string.player_audio_delay),
                formatValue = { delayLabel },
                focusRequester = sliderFocusRequester,
                onValueChange = { value ->
                    localDelay = value
                },
                onCenterClick = {
                    onSave(localDelay)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvOverlayActionButton(
                    label = "Save",
                    selected = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onSave(localDelay) },
                )
                TvOverlayActionButton(
                    label = stringResource(R.string.common_reset),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        localDelay = 0
                        onReset()
                    },
                )
                TvOverlayActionButton(
                    label = stringResource(R.string.common_close),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
            }
            Text(
                text = "Use positive values if audio is ahead, negative if audio is behind.",
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
            )
        }
    }
}
@Composable
fun TvSubtitleDelayOverlay(
    currentDelayMs: Int,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    onPreviewDelay: ((Int) -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    var localDelay by remember(currentDelayMs) { mutableIntStateOf(currentDelayMs) }
    val sliderFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { requestFocusWithRetry(sliderFocusRequester) }

    val delayLabel = run {
        val sign = if (localDelay >= 0) "+" else ""
        "$sign${localDelay / 1000}.${(kotlin.math.abs(localDelay) % 1000) / 100}s"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .clip(RoundedCornerShape(18.dp))
                .background(Charcoal)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Subtitle Delay",
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Left/Right adjusts by 100 ms. Positive = subtitles appear later.",
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
            )
            TvDpadSlider(
                value = localDelay,
                range = -10000..10000,
                step = 100,
                label = "Subtitle Delay",
                formatValue = { delayLabel },
                focusRequester = sliderFocusRequester,
                onValueChange = {
                    localDelay = it
                    onPreviewDelay?.invoke(it)
                },
                onCenterClick = { onSave(localDelay) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvOverlayActionButton(
                    label = "Save",
                    selected = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onSave(localDelay) },
                )
                TvOverlayActionButton(
                    label = stringResource(R.string.common_reset),
                    modifier = Modifier.weight(1f),
                    onClick = { localDelay = 0; onReset() },
                )
                TvOverlayActionButton(
                    label = stringResource(R.string.common_close),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
fun TvEqualizerOverlay(
    equalizer: AudioEqualizer,
    onDismiss: () -> Unit,
    onStateChanged: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var enabled by remember { mutableStateOf(equalizer.enabled) }
    var selectedPreset by remember { mutableStateOf<EqPreset?>(null) }
    var bandLevels by remember { mutableStateOf(equalizer.bandLevels) }
    var bassBoost by remember { mutableIntStateOf(equalizer.bassBoostStrength) }
    var virtualizer by remember { mutableIntStateOf(equalizer.virtualizerStrength) }

    val firstFocusRequester = remember { FocusRequester() }
    val freqLabels = remember(equalizer) {
        equalizer.bandFrequencies.map { hz ->
            if (hz >= 1000) "${hz / 1000}k" else "$hz"
        }
    }
    var dismissKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        requestFocusWithRetry(firstFocusRequester)
    }

    LaunchedEffect(dismissKey) {
        delay(3_000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.96f))
            .onPreviewKeyEvent { dismissKey++; false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.eq_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Use D-pad Left/Right to adjust. Back closes.",
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "enabled_toggle") {
                    TvOverlayActionButton(
                        label = stringResource(R.string.eq_enabled),
                        value = if (enabled) "On" else "Off",
                        modifier = Modifier.focusRequester(firstFocusRequester),
                        onClick = {
                            enabled = !enabled
                            equalizer.setEnabled(enabled)
                            onStateChanged(equalizer.toStateString())
                        },
                    )
                }

                item(key = "preset_header") {
                    Text(
                        text = "Presets",
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }

                items(EqPreset.entries.toList(), key = { "preset_${it.name}" }) { preset ->
                    TvOverlayActionButton(
                        label = preset.label,
                        value = if (preset == selectedPreset) stringResource(R.string.player_selected) else null,
                        selected = preset == selectedPreset,
                        onClick = {
                            selectedPreset = preset
                            equalizer.applyPreset(preset)
                            bandLevels = equalizer.bandLevels
                            bassBoost = equalizer.bassBoostStrength
                            virtualizer = equalizer.virtualizerStrength
                            onStateChanged(equalizer.toStateString())
                        },
                    )
                }

                item(key = "bands_header") {
                    Text(
                        text = "Bands",
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }

                if (equalizer.bandCount > 0) {
                    items(equalizer.bandCount, key = { "band_$it" }) { bandIndex ->
                        val label = freqLabels.getOrElse(bandIndex) { "Band ${bandIndex + 1}" }
                        TvDpadSlider(
                            value = bandLevels.getOrElse(bandIndex) { 0 },
                            range = equalizer.minLevel..equalizer.maxLevel,
                            step = 100,
                            label = label,
                            formatValue = { "${it / 100} dB" },
                            enabled = enabled,
                            onValueChange = { level ->
                                equalizer.setBandLevel(bandIndex, level)
                                bandLevels = equalizer.bandLevels
                                selectedPreset = null
                                onStateChanged(equalizer.toStateString())
                            },
                        )
                    }
                } else {
                    item(key = "eq_not_available") {
                        Text(
                            text = stringResource(R.string.eq_not_available),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Silver,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }

                item(key = "bass_slider") {
                    TvDpadSlider(
                        value = bassBoost,
                        range = 0..1000,
                        step = 25,
                        label = stringResource(R.string.eq_bass),
                        formatValue = { "${it / 10}%" },
                        enabled = enabled,
                        onValueChange = { value ->
                            bassBoost = value
                            equalizer.setBassBoostStrength(value)
                            selectedPreset = null
                            onStateChanged(equalizer.toStateString())
                        },
                    )
                }

                item(key = "virtualizer_slider") {
                    TvDpadSlider(
                        value = virtualizer,
                        range = 0..1000,
                        step = 25,
                        label = stringResource(R.string.eq_surround),
                        formatValue = { "${it / 10}%" },
                        enabled = enabled,
                        onValueChange = { value ->
                            virtualizer = value
                            equalizer.setVirtualizerStrength(value)
                            selectedPreset = null
                            onStateChanged(equalizer.toStateString())
                        },
                    )
                }

                item(key = "actions_row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TvOverlayActionButton(
                            label = stringResource(R.string.common_reset),
                            selected = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                equalizer.applyPreset(EqPreset.FLAT)
                                selectedPreset = EqPreset.FLAT
                                bandLevels = equalizer.bandLevels
                                bassBoost = equalizer.bassBoostStrength
                                virtualizer = equalizer.virtualizerStrength
                                onStateChanged(equalizer.toStateString())
                            },
                        )
                        TvOverlayActionButton(
                            label = stringResource(R.string.common_close),
                            selected = false,
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TvDpadSlider(
    value: Int,
    range: IntRange,
    step: Int,
    label: String,
    formatValue: (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onCenterClick: (() -> Unit)? = null,
) {
    var repeatDirection by remember { mutableIntStateOf(0) }
    var repeatCount by remember { mutableIntStateOf(0) }
    var repeatLastAtMs by remember { mutableLongStateOf(0L) }

    fun applyDelta(delta: Int) {
        if (!enabled) return
        val newValue = (value + delta).coerceIn(range.first, range.last)
        if (newValue != value) onValueChange(newValue)
    }

    fun acceleratedStep(direction: Int): Int {
        val nowMs = SystemClock.uptimeMillis()
        val isRepeatBurst = repeatDirection == direction && (nowMs - repeatLastAtMs) <= 320L
        repeatCount = if (isRepeatBurst) repeatCount + 1 else 0
        repeatDirection = direction
        repeatLastAtMs = nowMs
        val multiplier = PlayerNavigationMath.seekAccelerationMultiplier(repeatCount).toInt()
        return step * multiplier * direction
    }

    fun handleDirectionalKeys(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !enabled) return false
        return when (event.key) {
            Key.DirectionLeft -> {
                applyDelta(acceleratedStep(direction = -1))
                true
            }
            Key.DirectionRight -> {
                applyDelta(acceleratedStep(direction = 1))
                true
            }
            else -> {
                repeatDirection = 0
                repeatCount = 0
                repeatLastAtMs = 0L
                false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Graphite.copy(alpha = if (enabled) 0.65f else 0.45f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) Snow else Silver,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvStepButton(
                text = "-",
                enabled = enabled,
                onStep = { applyDelta(-step) },
                onDirectionalKey = ::handleDirectionalKeys,
            )
            TvDpadSliderValueCard(
                valueText = formatValue(value),
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    ),
                onCenterClick = onCenterClick,
                onDirectionalKey = ::handleDirectionalKeys,
            )
            TvStepButton(
                text = "+",
                enabled = enabled,
                onStep = { applyDelta(step) },
                onDirectionalKey = ::handleDirectionalKeys,
            )
        }
    }
}

@Composable
private fun TvTrackRow(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f, label = "trackScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isSelected -> Amber.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        label = "trackBorder",
    )
    val bg = when {
        focused -> Gunmetal
        isSelected -> AmberSubtle
        else -> Graphite.copy(alpha = 0.65f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isConfirmKey(event.key)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) Amber else Silver,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected || focused) Snow else Silver,
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.player_selected),
                tint = Amber,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TvOverlayTabChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (reduceMotion) 1f else if (focused) 1.04f else 1f, label = "tabScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isSelected -> Amber.copy(alpha = 0.65f)
            else -> Color.Transparent
        },
        label = "tabBorder",
    )
    val bg = when {
        focused -> Gunmetal
        isSelected -> Graphite
        else -> Charcoal
    }

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }
                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected || focused) Snow else Silver,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TvOverlayActionButton(
    label: String,
    value: String? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f, label = "actionScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber
            selected -> Amber.copy(alpha = 0.55f)
            else -> Color.Transparent
        },
        label = "actionBorder",
    )
    val bg = when {
        focused -> Gunmetal
        selected -> AmberSubtle
        else -> Graphite.copy(alpha = 0.65f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isConfirmKey(event.key)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused || selected) Snow else Silver,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused || selected) Amber else Silver,
            )
        }
    }
}

@Composable
private fun TvStepButton(
    text: String,
    enabled: Boolean,
    onStep: () -> Unit,
    onDirectionalKey: (KeyEvent) -> Boolean,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (reduceMotion) 1f else if (focused) 1.04f else 1f, label = "stepScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && enabled -> Amber
            else -> Color.Transparent
        },
        label = "stepBorder",
    )

    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 46.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                when {
                    onDirectionalKey(event) -> true
                    enabled && event.type == KeyEventType.KeyDown && isConfirmKey(event.key) -> {
                        onStep()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onStep,
            )
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Graphite else Graphite.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) Snow else Silver,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TvDpadSliderValueCard(
    valueText: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCenterClick: (() -> Unit)?,
    onDirectionalKey: (KeyEvent) -> Boolean,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f, label = "valueScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && enabled -> Amber
            else -> Color.Transparent
        },
        label = "valueBorder",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                when {
                    onDirectionalKey(event) -> true
                    enabled && event.type == KeyEventType.KeyDown && isConfirmKey(event.key) && onCenterClick != null -> {
                        onCenterClick.invoke()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                enabled = enabled && onCenterClick != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCenterClick?.invoke() },
            )
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Charcoal else Charcoal.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Snow else Silver,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun isConfirmKey(key: Key): Boolean {
    return key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
}

/**
 * A subtitle result ready to display. Either [directUrl] (Stremio addon, URL usable immediately)
 * or [osFileId] (OpenSubtitles, requires a getDownloadUrl() call before playback) will be set.
 */
data class SubtitleCandidate(
    val flagEmoji: String,
    val languageName: String,
    val languageCode: String,
    val displayLabel: String,
    val directUrl: String? = null,
    val mimeType: String? = null,
    val osFileId: Int? = null,
    val releaseName: String? = null,
    val provider: String,
    val fps: Double? = null,
    val downloadCount: Int? = null,
    val recentDownloadCount: Int? = null,
    val fromTrusted: Boolean? = null,
    val uploaderName: String? = null,
    val uploaderRank: String? = null,
    val hearingImpaired: Boolean? = null,
    val forced: Boolean? = null,
    val aiTranslated: Boolean? = null,
    val machineTranslated: Boolean? = null,
    val ratings: Double? = null,
    val voteCount: Int? = null,
    val uploadDate: String? = null,
    val matchTier: SubtitleMatchTier,
    val matchScore: Int,
    val qualityScore: Int,
    val rankingReasons: List<String> = emptyList(),
    val contentIdentityScore: Int = 0,
    val releaseMatchScore: Int? = null,
    val syncConfidenceScore: Int = matchScore,
    val matchQuality: SubtitleMatchQuality = SubtitleMatchQuality.POSSIBLE,
    val matchExplanation: String = "Release information unavailable",
    val evidence: List<SubtitleEvidence> = emptyList(),
    val subtitleFormat: String? = null,
    val sourceType: String? = null,
    val resolutionHeight: Int? = null,
    val videoCodec: String? = null,
    val releaseGroup: String? = null,
)

sealed class SubtitleFetchState {
    data object Idle : SubtitleFetchState()
    data object Loading : SubtitleFetchState()
    data class Results(
        val subtitles: List<SubtitleCandidate>,
        val matchingRelease: String,
        val movieHashAvailable: Boolean,
        val hasStrongMatch: Boolean,
        val providerStatus: String,
        val openSubtitlesPageLimit: Int,
        val canLoadMore: Boolean,
        val isLoadingMore: Boolean = false,
    ) : SubtitleFetchState()
    data object NoKey : SubtitleFetchState()
    data object Empty : SubtitleFetchState()
    data object Error : SubtitleFetchState()
}

private data class SubtitleDisplayEntry(
    val section: String,
    val key: String,
    val candidate: SubtitleCandidate,
)

@Composable
fun TvSubtitleSearchOverlay(
    state: SubtitleFetchState,
    onSelect: (SubtitleCandidate) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    preferredLanguage: String? = null,
) {
    BackHandler(onBack = onDismiss)
    val releaseFocusKey = (state as? SubtitleFetchState.Results)?.matchingRelease ?: state::class.simpleName
    val filterKeys = remember {
        listOf("sort", "load-more", "strong", "trusted", "automated", "rating", "sdh", "forced", "poor", "rejected")
    }
    val filterRequesters = remember { List(filterKeys.size) { FocusRequester() } }
    val languageRequesterStore = remember { mutableMapOf<String, FocusRequester>() }
    val resultRequesterStore = remember { mutableMapOf<String, FocusRequester>() }
    val filterListState = rememberLazyListState()
    val languageListState = rememberLazyListState()
    val resultListState = rememberLazyListState()
    val focusScope = rememberCoroutineScope()

    var selectedLanguage by remember(releaseFocusKey) { mutableStateOf<String?>(null) }
    var preferredLanguageApplied by remember(releaseFocusKey) { mutableStateOf(false) }
    var strongOnly by remember(releaseFocusKey) { mutableStateOf(false) }
    var trustedOnly by remember(releaseFocusKey) { mutableStateOf(false) }
    var hearingImpairedOnly by remember(releaseFocusKey) { mutableStateOf(false) }
    var forcedOnly by remember(releaseFocusKey) { mutableStateOf(false) }
    var excludeAutomated by remember(releaseFocusKey) { mutableStateOf(false) }
    var minimumRating by remember(releaseFocusKey) { mutableStateOf<Double?>(null) }
    var showPoorMatches by remember(releaseFocusKey) { mutableStateOf(true) }
    var showRejected by remember(releaseFocusKey) { mutableStateOf(false) }
    var sortMode by remember(releaseFocusKey) { mutableStateOf(SubtitleSortMode.SMART_MATCH) }
    var focusedCandidate by remember(releaseFocusKey) { mutableStateOf<SubtitleCandidate?>(null) }
    var focusedResultKey by remember(releaseFocusKey) { mutableStateOf<String?>(null) }
    var focusedResultIndex by remember(releaseFocusKey) { mutableIntStateOf(0) }
    var activeFocusTarget by remember(releaseFocusKey) { mutableStateOf<SubtitlePickerFocusTarget?>(null) }
    var confirmedFocusTarget by remember(releaseFocusKey) { mutableStateOf<SubtitlePickerFocusTarget?>(null) }
    var focusRequestSequence by remember(releaseFocusKey) { mutableIntStateOf(0) }
    var lastControlTarget by remember(releaseFocusKey) {
        mutableStateOf(SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, 0))
    }
    var initialFocusSeen by remember(releaseFocusKey) { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Obsidian.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_download_subtitles),
                    style = MaterialTheme.typography.titleLarge,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                )
                Text("Smart Match · OpenSubtitles + Addons", style = MaterialTheme.typography.bodySmall, color = Silver)
            }

            when (state) {
                SubtitleFetchState.Loading -> SubtitlePickerMessage("Searching for subtitles…", onDismiss, showProgress = true)
                SubtitleFetchState.NoKey -> SubtitlePickerMessage(
                    "No subtitle source available.\n\nInstall a subtitle addon, or add an OpenSubtitles.com API key in Settings → Advanced.",
                    onDismiss,
                )
                SubtitleFetchState.Error -> SubtitlePickerMessage(
                    "Subtitle search failed.\nCheck your connection, subtitle addons, and OpenSubtitles key.",
                    onDismiss,
                )
                SubtitleFetchState.Empty -> SubtitlePickerMessage(
                    "No subtitles found for this title from the configured providers.",
                    onDismiss,
                )
                SubtitleFetchState.Idle -> Unit
                is SubtitleFetchState.Results -> {
                    val languages = remember(state.subtitles, preferredLanguage) {
                        state.subtitles
                            .map { it.languageCode to "${it.flagEmoji} ${it.languageName}" }
                            .distinctBy { it.first }
                            .sortedWith(
                                compareByDescending<Pair<String, String>> {
                                    subtitleLanguagesMatch(it.first, preferredLanguage)
                                }.thenBy { it.second },
                            )
                    }
                    val preferredCode = languages.firstOrNull {
                        subtitleLanguagesMatch(it.first, preferredLanguage)
                    }?.first
                    val languageOptions = remember(languages) {
                        listOf<Pair<String?, String>>(null to "All languages") + languages
                    }
                    val languageRowCount = languageOptions.size.takeIf { it > 1 } ?: 0
                    val languageRequesters = languageOptions.map { (code, _) ->
                        languageRequesterStore.getOrPut(code ?: "all") { FocusRequester() }
                    }
                    val filtered = remember(
                        state.subtitles,
                        selectedLanguage,
                        strongOnly,
                        trustedOnly,
                        hearingImpairedOnly,
                        forcedOnly,
                        excludeAutomated,
                        minimumRating,
                        showPoorMatches,
                        showRejected,
                        sortMode,
                    ) {
                        val accepted = state.subtitles.filter { subtitle ->
                            (selectedLanguage == null || subtitleLanguagesMatch(subtitle.languageCode, selectedLanguage)) &&
                                (!strongOnly || subtitle.matchTier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority) &&
                                (!trustedOnly || subtitle.fromTrusted == true) &&
                                (!hearingImpairedOnly || subtitle.hearingImpaired == true) &&
                                (!forcedOnly || subtitle.forced == true) &&
                                (!excludeAutomated || (subtitle.aiTranslated != true && subtitle.machineTranslated != true)) &&
                                (minimumRating == null || (subtitle.ratings ?: -1.0) >= minimumRating!!) &&
                                (showPoorMatches || subtitle.matchTier != SubtitleMatchTier.POOR_MATCH) &&
                                (showRejected || subtitle.matchTier != SubtitleMatchTier.REJECTED)
                        }
                        when (sortMode) {
                            SubtitleSortMode.SMART_MATCH -> accepted
                            SubtitleSortMode.RATING -> accepted.sortedWith(
                                compareByDescending<SubtitleCandidate> { it.qualityScore }.thenBy { it.matchTier.priority },
                            )
                            SubtitleSortMode.DOWNLOADS -> accepted.sortedWith(
                                compareByDescending<SubtitleCandidate> { it.downloadCount ?: -1 }.thenBy { it.matchTier.priority },
                            )
                            SubtitleSortMode.NEWEST -> accepted.sortedWith(
                                compareByDescending<SubtitleCandidate> { it.uploadDate.orEmpty() }.thenBy { it.matchTier.priority },
                            )
                        }
                    }
                    val displayEntries = remember(filtered) {
                        listOf(
                            "BEST MATCHES" to filtered.filter {
                                it.matchTier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority
                            },
                            "MORE RESULTS" to filtered.filter {
                                it.matchTier in setOf(SubtitleMatchTier.COMPATIBLE_RELEASE, SubtitleMatchTier.GENERIC_MATCH)
                            },
                            "POOR RELEASE MATCHES" to filtered.filter { it.matchTier == SubtitleMatchTier.POOR_MATCH },
                            "REJECTED — WRONG TITLE OR EPISODE" to filtered.filter {
                                it.matchTier == SubtitleMatchTier.REJECTED
                            },
                        ).flatMap { (section, values) ->
                            values.map { SubtitleDisplayEntry(section, subtitleCandidateKey(it), it) }
                        }
                    }
                    val resultRequesters = displayEntries.map {
                        resultRequesterStore.getOrPut(it.key) { FocusRequester() }
                    }
                    val loadMoreEnabled = state.canLoadMore && !state.isLoadingMore
                    val enabledFilters = remember(loadMoreEnabled) {
                        filterKeys.indices.filterTo(mutableSetOf()) { it != 1 || loadMoreEnabled }
                    }
                    val focusGraph = SubtitlePickerFocusGraph(
                        filterCount = filterKeys.size,
                        enabledFilterIndexes = enabledFilters,
                        languageCount = languageRowCount,
                        resultCount = displayEntries.size,
                        resultExitTarget = lastControlTarget,
                    )

                    fun requestFocus(target: SubtitlePickerFocusTarget) {
                        focusRequestSequence += 1
                        val requestSequence = focusRequestSequence
                        focusScope.launch {
                            val requester = when (target.row) {
                                SubtitlePickerFocusRow.FILTERS -> {
                                    val index = target.index.coerceIn(filterKeys.indices)
                                    filterRequesters[index]
                                }
                                SubtitlePickerFocusRow.LANGUAGES -> {
                                    if (languageRowCount == 0) return@launch
                                    val index = target.index.coerceIn(0, languageRowCount - 1)
                                    languageRequesters[index]
                                }
                                SubtitlePickerFocusRow.RESULTS -> {
                                    if (displayEntries.isEmpty()) return@launch
                                    val index = target.index.coerceIn(displayEntries.indices)
                                    resultRequesters[index]
                                }
                            }
                            // Focus an already-visible neighbour without first
                            // moving the lazy viewport. This avoids disposing the
                            // very chip that is about to receive focus.
                            requestFocusSafely(requester)
                            delay(16)
                            if (focusRequestSequence != requestSequence || confirmedFocusTarget == target) {
                                return@launch
                            }
                            when (target.row) {
                                SubtitlePickerFocusRow.FILTERS -> filterListState.scrollToItem(target.index.coerceIn(filterKeys.indices))
                                SubtitlePickerFocusRow.LANGUAGES -> languageListState.scrollToItem(target.index.coerceIn(0, languageRowCount - 1))
                                SubtitlePickerFocusRow.RESULTS -> resultListState.scrollToItem(target.index.coerceIn(displayEntries.indices))
                            }
                            // Lazy containers may dispose and recreate the target
                            // while scrolling. Retry across frames, but cancel as
                            // soon as a newer D-pad move supersedes this request.
                            repeat(24) {
                                if (focusRequestSequence != requestSequence || confirmedFocusTarget == target) {
                                    return@launch
                                }
                                requestFocusSafely(requester)
                                delay(50)
                            }
                        }
                    }

                    fun navigate(target: SubtitlePickerFocusTarget, direction: SubtitlePickerDirection) {
                        // Advance the logical cursor synchronously. Fire remotes
                        // can deliver another key before a LazyRow scroll/focus
                        // request reaches the next frame; using the logical
                        // cursor keeps rapid LEFT/RIGHT/UP/DOWN presses ordered.
                        val origin = activeFocusTarget?.takeIf { it.row == target.row } ?: target
                        val destination = focusGraph.move(origin, direction)
                        activeFocusTarget = destination
                        requestFocus(destination)
                    }

                    LaunchedEffect(releaseFocusKey, preferredCode, languageRowCount) {
                        if (!preferredLanguageApplied) {
                            selectedLanguage = preferredCode
                            preferredLanguageApplied = true
                        }
                        focusedCandidate = focusedCandidate ?: displayEntries.firstOrNull()?.candidate
                        if (!initialFocusSeen) {
                            val preferredIndex = preferredCode?.let { code ->
                                languageOptions.indexOfFirst { it.first == code }.takeIf { it >= 0 }
                            }
                            val target = preferredIndex
                                ?.let { SubtitlePickerFocusTarget(SubtitlePickerFocusRow.LANGUAGES, it) }
                                ?: SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, 0)
                            // Cold Fire TV launches can take several seconds to
                            // attach and focus the window. Retry until a node
                            // confirms focus rather than treating requestFocus()
                            // returning normally as success.
                            repeat(80) {
                                when (target.row) {
                                    SubtitlePickerFocusRow.LANGUAGES -> requestFocusSafely(languageRequesters.getOrNull(target.index))
                                    SubtitlePickerFocusRow.FILTERS -> requestFocusSafely(filterRequesters.getOrNull(target.index))
                                    SubtitlePickerFocusRow.RESULTS -> Unit
                                }
                                delay(100)
                                if (initialFocusSeen) return@LaunchedEffect
                            }
                        }
                    }

                    LaunchedEffect(loadMoreEnabled) {
                        if (!loadMoreEnabled && activeFocusTarget == SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, 1)) {
                            requestFocus(SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, 2))
                        }
                    }

                    val displayKeys = displayEntries.map { it.key }
                    LaunchedEffect(displayKeys) {
                        if (activeFocusTarget?.row == SubtitlePickerFocusRow.RESULTS) {
                            val retainedIndex = focusedResultKey?.let(displayKeys::indexOf) ?: -1
                            if (retainedIndex >= 0) {
                                activeFocusTarget = SubtitlePickerFocusTarget(SubtitlePickerFocusRow.RESULTS, retainedIndex)
                                focusedResultIndex = retainedIndex
                            } else if (displayEntries.isNotEmpty()) {
                                requestFocus(
                                    SubtitlePickerFocusTarget(
                                        SubtitlePickerFocusRow.RESULTS,
                                        focusedResultIndex.coerceIn(displayEntries.indices),
                                    ),
                                )
                            } else {
                                requestFocus(lastControlTarget)
                            }
                        }
                    }

                    Text("MATCHING AGAINST", color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.matchingRelease,
                        color = Snow,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.movieHashAvailable) "Exact-file hash available" else "Release-name matching (file hash unavailable)",
                        color = Silver,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = state.providerStatus,
                        color = Silver,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    if (!state.hasStrongMatch) {
                        Text(
                            "No strong release match found. Showing best available subtitles.",
                            color = AmberLight,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }

                    LazyRow(
                        state = filterListState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).focusGroup(),
                    ) {
                        items(filterKeys.size, key = { filterKeys[it] }) { index ->
                            val label = subtitleFilterLabel(index, sortMode, minimumRating, state)
                            val selected = subtitleFilterSelected(
                                index,
                                strongOnly,
                                trustedOnly,
                                excludeAutomated,
                                minimumRating,
                                hearingImpairedOnly,
                                forcedOnly,
                                showPoorMatches,
                                showRejected,
                            )
                            val target = SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, index)
                            SubtitleFilterPill(
                                label = label,
                                isSelected = selected,
                                enabled = index != 1 || loadMoreEnabled,
                                modifier = Modifier.focusRequester(filterRequesters[index]),
                                onFocused = {
                                    activeFocusTarget = target
                                    confirmedFocusTarget = target
                                    lastControlTarget = target
                                    focusedResultKey = null
                                    initialFocusSeen = true
                                },
                                onLeft = { navigate(target, SubtitlePickerDirection.LEFT) },
                                onRight = { navigate(target, SubtitlePickerDirection.RIGHT) },
                                onUp = { navigate(target, SubtitlePickerDirection.UP) },
                                onDown = { navigate(target, SubtitlePickerDirection.DOWN) },
                                onClick = {
                                    when (index) {
                                        0 -> sortMode = SubtitleSortMode.entries[(sortMode.ordinal + 1) % SubtitleSortMode.entries.size]
                                        1 -> onLoadMore()
                                        2 -> strongOnly = !strongOnly
                                        3 -> trustedOnly = !trustedOnly
                                        4 -> excludeAutomated = !excludeAutomated
                                        5 -> minimumRating = when (minimumRating) { null -> 8.0; 8.0 -> 9.0; else -> null }
                                        6 -> hearingImpairedOnly = !hearingImpairedOnly
                                        7 -> forcedOnly = !forcedOnly
                                        8 -> showPoorMatches = !showPoorMatches
                                        9 -> showRejected = !showRejected
                                    }
                                },
                            )
                        }
                    }

                    if (languageRowCount > 0) {
                        LazyRow(
                            state = languageListState,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).focusGroup(),
                        ) {
                            items(languageOptions.size, key = { languageOptions[it].first ?: "all" }) { index ->
                                val (code, label) = languageOptions[index]
                                val target = SubtitlePickerFocusTarget(SubtitlePickerFocusRow.LANGUAGES, index)
                                SubtitleFilterPill(
                                    label = label,
                                    isSelected = if (code == null) selectedLanguage == null else subtitleLanguagesMatch(selectedLanguage, code),
                                    modifier = Modifier.focusRequester(languageRequesters[index]),
                                    onFocused = {
                                        activeFocusTarget = target
                                        confirmedFocusTarget = target
                                        lastControlTarget = target
                                        focusedResultKey = null
                                        initialFocusSeen = true
                                    },
                                    onLeft = { navigate(target, SubtitlePickerDirection.LEFT) },
                                    onRight = { navigate(target, SubtitlePickerDirection.RIGHT) },
                                    onUp = { navigate(target, SubtitlePickerDirection.UP) },
                                    onDown = { navigate(target, SubtitlePickerDirection.DOWN) },
                                    onClick = {
                                        selectedLanguage = if (code != null && subtitleLanguagesMatch(selectedLanguage, code)) null else code
                                    },
                                )
                            }
                        }
                    }

                    LazyColumn(
                        state = resultListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(displayEntries, key = { _, entry -> entry.key }) { index, entry ->
                            Column {
                                if (index == 0 || displayEntries[index - 1].section != entry.section) {
                                    Text(entry.section, color = Amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                val target = SubtitlePickerFocusTarget(SubtitlePickerFocusRow.RESULTS, index)
                                SmartSubtitleResultRow(
                                    candidate = entry.candidate,
                                    modifier = Modifier.focusRequester(resultRequesters[index]),
                                    onFocused = {
                                        focusedCandidate = entry.candidate
                                        focusedResultKey = entry.key
                                        focusedResultIndex = index
                                        activeFocusTarget = target
                                        confirmedFocusTarget = target
                                        initialFocusSeen = true
                                    },
                                    onNavigate = { navigate(target, it) },
                                    onClick = { onSelect(entry.candidate) },
                                    enabled = entry.candidate.matchTier != SubtitleMatchTier.REJECTED,
                                )
                            }
                        }
                        if (displayEntries.isEmpty()) {
                            item {
                                Text(
                                    if (state.isLoadingMore) "Searching additional OpenSubtitles pages…"
                                    else "No subtitles match the active filters. Choose Search more or relax a filter.",
                                    color = Silver,
                                )
                            }
                        }
                    }

                    focusedCandidate?.let { candidate ->
                        val explanation = candidate.evidence.take(7).joinToString("  ·  ") {
                            val marker = when (it.state) {
                                SubtitleEvidenceState.MATCH -> "✓"
                                SubtitleEvidenceState.UNKNOWN -> "?"
                                SubtitleEvidenceState.MISMATCH -> "✕"
                            }
                            "$marker ${it.description}"
                        }.ifBlank { candidate.matchExplanation }
                        Text(
                            text = "Why this matches  ·  $explanation",
                            color = Silver,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SubtitlePickerMessage(
    message: String,
    onDismiss: () -> Unit,
    showProgress: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    var focusSeen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(80) {
            requestFocusSafely(requester)
            delay(100)
            if (focusSeen) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                CircularProgressIndicator(color = Amber, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
            }
            Text(
                message,
                color = Silver,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            SubtitleFilterPill(
                label = "Back",
                isSelected = false,
                modifier = Modifier.focusRequester(requester),
                onFocused = { focusSeen = true },
                onLeft = {},
                onRight = {},
                onUp = {},
                onDown = {},
                onClick = onDismiss,
            )
        }
    }
}

private fun subtitleFilterLabel(
    index: Int,
    sortMode: SubtitleSortMode,
    minimumRating: Double?,
    state: SubtitleFetchState.Results,
): String = when (index) {
    0 -> when (sortMode) {
        SubtitleSortMode.SMART_MATCH -> "Smart Match"
        SubtitleSortMode.RATING -> "Rating"
        SubtitleSortMode.DOWNLOADS -> "Downloads"
        SubtitleSortMode.NEWEST -> "Newest"
    }
    1 -> when {
        state.isLoadingMore -> "Searching more…"
        state.canLoadMore -> "Search more"
        else -> "All available pages loaded"
    }
    2 -> "Strong only"
    3 -> "Trusted"
    4 -> "Exclude AI/MT"
    5 -> minimumRating?.let { "Rating ${it.toInt()}+" } ?: "Any rating"
    6 -> "SDH"
    7 -> "Forced"
    8 -> "Include poor"
    else -> "Show rejected"
}

private fun subtitleFilterSelected(
    index: Int,
    strongOnly: Boolean,
    trustedOnly: Boolean,
    excludeAutomated: Boolean,
    minimumRating: Double?,
    hearingImpairedOnly: Boolean,
    forcedOnly: Boolean,
    showPoorMatches: Boolean,
    showRejected: Boolean,
): Boolean = when (index) {
    0 -> true
    1 -> false
    2 -> strongOnly
    3 -> trustedOnly
    4 -> excludeAutomated
    5 -> minimumRating != null
    6 -> hearingImpairedOnly
    7 -> forcedOnly
    8 -> showPoorMatches
    else -> showRejected
}

private fun subtitleCandidateKey(candidate: SubtitleCandidate): String = listOf(
    candidate.provider,
    candidate.osFileId?.toString().orEmpty(),
    candidate.directUrl.orEmpty(),
    candidate.releaseName.orEmpty(),
    candidate.displayLabel,
    candidate.languageCode,
).joinToString("|")

private fun displaySubtitleSourceType(value: String?): String? = when (value?.lowercase()) {
    "web-dl" -> "WEB-DL"
    "webrip" -> "WEBRip"
    "bluray" -> "BluRay"
    "bdrip" -> "BDRip"
    "hdtv" -> "HDTV"
    "dvdrip" -> "DVDRip"
    "remux" -> "REMUX"
    null, "" -> null
    else -> value
}

private fun displaySubtitleCodec(value: String?): String? = when (value?.lowercase()) {
    "h264", "x264", "avc" -> "x264"
    "h265", "x265", "hevc" -> "HEVC"
    "av1" -> "AV1"
    null, "" -> null
    else -> value
}

@Composable
private fun SmartSubtitleResultRow(
    candidate: SubtitleCandidate,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onNavigate: (SubtitlePickerDirection) -> Unit,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f,
        label = "smartSubtitleScale",
    )
    val background by animateColorAsState(
        targetValue = if (focused) Amber else Graphite.copy(alpha = 0.75f),
        label = "smartSubtitleBackground",
    )
    val primary = if (focused) Obsidian else Snow
    val secondary = if (focused) Obsidian.copy(alpha = 0.78f) else Silver
    val humanRelease = humanReadableSubtitleName(candidate.releaseName)
        ?: humanReadableSubtitleName(candidate.displayLabel)
        ?: humanReadableSubtitleName(
            candidate.directUrl?.substringBefore('?')?.substringAfterLast('/'),
        )
    val sourceSummary = listOfNotNull(
        displaySubtitleSourceType(candidate.sourceType),
        candidate.resolutionHeight?.let { "${it}p" },
        displaySubtitleCodec(candidate.videoCodec),
        candidate.releaseGroup,
    ).distinct().joinToString(" · ").ifBlank { null }
    val reputation = buildList {
        candidate.ratings?.let { rating ->
            add(buildString {
                append("★ ${String.format("%.1f", rating)}")
                candidate.voteCount?.let { append(" ($it)") }
            })
        }
        candidate.downloadCount?.let { add("${compactCount(it)} downloads") }
        if (candidate.fromTrusted == true) add("Trusted")
        if (candidate.hearingImpaired == true) add("SDH")
        if (candidate.forced == true) add("Forced")
        if (candidate.aiTranslated == true) add("AI translated")
        if (candidate.machineTranslated == true) add("Machine translated")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when (event.key) {
                    Key.DirectionLeft -> { onNavigate(SubtitlePickerDirection.LEFT); true }
                    Key.DirectionRight -> { onNavigate(SubtitlePickerDirection.RIGHT); true }
                    Key.DirectionUp -> { onNavigate(SubtitlePickerDirection.UP); true }
                    Key.DirectionDown -> { onNavigate(SubtitlePickerDirection.DOWN); true }
                    else -> if (isConfirmKey(event.key)) {
                        if (enabled) onClick()
                        true
                    } else {
                        false
                    }
                }
            }
            .focusable()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (enabled) onClick(action = { onClick(); true }) else disabled()
            }
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Snow else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .background(background)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "${candidate.flagEmoji}  ${candidate.languageName}",
                style = MaterialTheme.typography.titleSmall,
                color = primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                candidate.matchQuality.displayLabel,
                color = if (focused) Obsidian else Amber,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            if (!enabled) Text("NOT SELECTABLE", color = secondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            candidate.matchExplanation,
            color = secondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        (humanRelease ?: sourceSummary)?.let { releaseOrSource ->
            Text(
                releaseOrSource,
                color = secondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (humanRelease != null && sourceSummary != null) {
                Text(sourceSummary, color = secondary, style = MaterialTheme.typography.labelSmall)
            }
            reputation.forEach { Text(it, color = secondary, style = MaterialTheme.typography.labelSmall) }
            candidate.fps?.let { Text("$it FPS", color = secondary, style = MaterialTheme.typography.labelSmall) }
            candidate.subtitleFormat?.let { Text(it.uppercase(), color = secondary, style = MaterialTheme.typography.labelSmall) }
            Text(candidate.provider, color = secondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LegacyTvSubtitleSearchOverlay(
    state: SubtitleFetchState,
    onSelect: (SubtitleCandidate) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    preferredLanguage: String? = null,
) {
    BackHandler(onBack = onDismiss)
    val firstRowRequester = remember { FocusRequester() }
    val loadMoreRequester = remember { FocusRequester() }
    val strongFilterRequester = remember { FocusRequester() }
    val firstLanguageRequester = remember { FocusRequester() }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var strongOnly by remember { mutableStateOf(false) }
    var trustedOnly by remember { mutableStateOf(false) }
    var hearingImpairedOnly by remember { mutableStateOf(false) }
    var forcedOnly by remember { mutableStateOf(false) }
    var excludeAutomated by remember { mutableStateOf(false) }
    var minimumRating by remember { mutableStateOf<Double?>(null) }
    var showPoorMatches by remember { mutableStateOf(true) }
    var showRejected by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SubtitleSortMode.SMART_MATCH) }
    var focusedCandidate by remember { mutableStateOf<SubtitleCandidate?>(null) }
    var focusedFilterKey by remember { mutableStateOf<String?>(null) }
    val releaseFocusKey = (state as? SubtitleFetchState.Results)?.matchingRelease ?: state::class.simpleName
    var initialSortFocusSeen by remember(releaseFocusKey) { mutableStateOf(false) }

    LaunchedEffect(releaseFocusKey) {
        if (state is SubtitleFetchState.Results) {
            selectedLanguage = null
            focusedCandidate = state.subtitles.firstOrNull()
            // The result branch and its LazyRow may not be attached during the
            // first composition frame (especially on slower Fire TV devices).
            // Keep trying until the sort pill confirms that it received focus,
            // then stop so a fast D-pad action cannot be stolen back.
            initialSortFocusSeen = false
            repeat(8) {
                requestFocusSafely(firstRowRequester)
                delay(50)
                if (initialSortFocusSeen) return@LaunchedEffect
            }
        }
    }

    val loadMoreIsFocusable = (state as? SubtitleFetchState.Results)
        ?.let { it.canLoadMore && !it.isLoadingMore }
        ?: false
    LaunchedEffect(loadMoreIsFocusable) {
        if (!loadMoreIsFocusable && focusedFilterKey == "load-more") {
            requestFocusWithRetry(strongFilterRequester)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.96f))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else when {
                    focusedFilterKey == "sort" && event.key == Key.DirectionRight -> {
                        requestFocusSafely(if (loadMoreIsFocusable) loadMoreRequester else strongFilterRequester)
                        true
                    }
                    focusedFilterKey == "load-more" && event.key == Key.DirectionLeft -> {
                        requestFocusSafely(firstRowRequester)
                        true
                    }
                    focusedFilterKey == "load-more" && event.key == Key.DirectionRight -> {
                        requestFocusSafely(strongFilterRequester)
                        true
                    }
                    focusedFilterKey == "strong" && event.key == Key.DirectionLeft -> {
                        requestFocusSafely(if (loadMoreIsFocusable) loadMoreRequester else firstRowRequester)
                        true
                    }
                    focusedFilterKey == "load-more" && isConfirmKey(event.key) &&
                        state is SubtitleFetchState.Results && state.canLoadMore && !state.isLoadingMore -> {
                        onLoadMore()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_download_subtitles),
                    style = MaterialTheme.typography.titleLarge,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Smart Match · OpenSubtitles + Addons",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
            }

            when (state) {
                SubtitleFetchState.Loading -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Amber, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Searching for subtitles…", color = Silver, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                SubtitleFetchState.NoKey -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            "No subtitle source available.\n\nEither install a subtitle addon in Settings → Addons,\nor add an OpenSubtitles.com API key in Settings → Advanced.",
                            color = Silver,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                SubtitleFetchState.Error -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            "Subtitle search failed.\nCheck your connection, subtitle addons, and OpenSubtitles key.",
                            color = Silver,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                SubtitleFetchState.Empty -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            "No subtitles found for this title from the configured providers.",
                            color = Silver,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                is SubtitleFetchState.Results -> {
                    val languages = remember(state.subtitles) {
                        state.subtitles
                            .map { it.languageCode to "${it.flagEmoji} ${it.languageName}" }
                            .distinctBy { it.first }
                    }
                    val filtered = remember(
                        state.subtitles,
                        selectedLanguage,
                        strongOnly,
                        trustedOnly,
                        hearingImpairedOnly,
                        forcedOnly,
                        excludeAutomated,
                        minimumRating,
                        showPoorMatches,
                        showRejected,
                        sortMode,
                    ) {
                        val accepted = state.subtitles.filter { subtitle ->
                            (selectedLanguage == null || subtitle.languageCode == selectedLanguage) &&
                                (!strongOnly || subtitle.matchTier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority) &&
                                (!trustedOnly || subtitle.fromTrusted == true) &&
                                (!hearingImpairedOnly || subtitle.hearingImpaired == true) &&
                                (!forcedOnly || subtitle.forced == true) &&
                                (!excludeAutomated || (subtitle.aiTranslated != true && subtitle.machineTranslated != true)) &&
                                (minimumRating == null || (subtitle.ratings ?: -1.0) >= minimumRating!!) &&
                                (showPoorMatches || subtitle.matchTier != SubtitleMatchTier.POOR_MATCH) &&
                                (showRejected || subtitle.matchTier != SubtitleMatchTier.REJECTED)
                        }
                        when (sortMode) {
                            SubtitleSortMode.SMART_MATCH -> accepted
                            SubtitleSortMode.RATING -> accepted.sortedWith(
                                compareByDescending<SubtitleCandidate> { it.qualityScore }.thenBy { it.matchTier.priority },
                            )
                            SubtitleSortMode.DOWNLOADS -> accepted.sortedWith(
                                compareByDescending<SubtitleCandidate> { it.downloadCount ?: -1 }.thenBy { it.matchTier.priority },
                            )
                            SubtitleSortMode.NEWEST -> accepted.sortedWith(
                                compareByDescending<SubtitleCandidate> { it.uploadDate.orEmpty() }.thenBy { it.matchTier.priority },
                            )
                        }
                    }

                    Text(
                        text = "MATCHING AGAINST",
                        color = Amber,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = state.matchingRelease,
                        color = Snow,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.movieHashAvailable) "Exact-file hash available" else "Release-name matching (file hash unavailable)",
                        color = Silver,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(
                        text = state.providerStatus,
                        color = Silver,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    if (!state.hasStrongMatch) {
                        Text(
                            text = "No strong release match found. Showing best available subtitles.",
                            color = AmberLight,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }

                    val moveToLanguageRow = if (languages.size > 1) {
                        Modifier.focusProperties { down = firstLanguageRequester }
                    } else {
                        Modifier
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 3.dp)
                            .focusGroup(),
                        state = rememberLazyListState(),
                    ) {
                        item(key = "sort") {
                            SubtitleFilterPill(
                                label = when (sortMode) {
                                    SubtitleSortMode.SMART_MATCH -> "Smart Match"
                                    SubtitleSortMode.RATING -> "Rating"
                                    SubtitleSortMode.DOWNLOADS -> "Downloads"
                                    SubtitleSortMode.NEWEST -> "Newest"
                                },
                                isSelected = true,
                                modifier = Modifier
                                    .focusRequester(firstRowRequester)
                                    .focusProperties {
                                        right = if (loadMoreIsFocusable) loadMoreRequester else strongFilterRequester
                                    }
                                    .then(moveToLanguageRow),
                                onFocused = {
                                    focusedFilterKey = "sort"
                                    initialSortFocusSeen = true
                                },
                                onRight = {
                                    requestFocusSafely(if (loadMoreIsFocusable) loadMoreRequester else strongFilterRequester)
                                },
                                onClick = {
                                    sortMode = SubtitleSortMode.entries[(sortMode.ordinal + 1) % SubtitleSortMode.entries.size]
                                },
                            )
                        }
                        item(key = "load-more") {
                            SubtitleFilterPill(
                                label = when {
                                    state.isLoadingMore -> "Searching more…"
                                    state.canLoadMore -> "Search more"
                                    else -> "All available pages loaded"
                                },
                                isSelected = false,
                                modifier = Modifier
                                    .focusRequester(loadMoreRequester)
                                    .focusProperties {
                                        left = firstRowRequester
                                        right = strongFilterRequester
                                    }
                                    .then(moveToLanguageRow),
                                enabled = state.canLoadMore && !state.isLoadingMore,
                                onFocused = { focusedFilterKey = "load-more" },
                                onLeft = { requestFocusSafely(firstRowRequester) },
                                onRight = { requestFocusSafely(strongFilterRequester) },
                                onClick = onLoadMore,
                            )
                        }
                        item(key = "strong") {
                            SubtitleFilterPill(
                                "Strong only",
                                strongOnly,
                                modifier = Modifier
                                    .focusRequester(strongFilterRequester)
                                    .focusProperties {
                                        left = if (loadMoreIsFocusable) loadMoreRequester else firstRowRequester
                                    }
                                    .then(moveToLanguageRow),
                                onFocused = { focusedFilterKey = "strong" },
                                onLeft = {
                                    requestFocusSafely(if (loadMoreIsFocusable) loadMoreRequester else firstRowRequester)
                                },
                                onClick = { strongOnly = !strongOnly },
                            )
                        }
                        item(key = "trusted") { SubtitleFilterPill("Trusted", trustedOnly, modifier = moveToLanguageRow, onClick = { trustedOnly = !trustedOnly }) }
                        item(key = "automated") { SubtitleFilterPill("Exclude AI/MT", excludeAutomated, modifier = moveToLanguageRow, onClick = { excludeAutomated = !excludeAutomated }) }
                        item(key = "rating") {
                            SubtitleFilterPill(
                                minimumRating?.let { "Rating ${it.toInt()}+" } ?: "Any rating",
                                minimumRating != null,
                                modifier = moveToLanguageRow,
                                onClick = { minimumRating = when (minimumRating) { null -> 8.0; 8.0 -> 9.0; else -> null } },
                            )
                        }
                        item(key = "sdh") { SubtitleFilterPill("SDH", hearingImpairedOnly, modifier = moveToLanguageRow, onClick = { hearingImpairedOnly = !hearingImpairedOnly }) }
                        item(key = "forced") { SubtitleFilterPill("Forced", forcedOnly, modifier = moveToLanguageRow, onClick = { forcedOnly = !forcedOnly }) }
                        item(key = "poor") { SubtitleFilterPill("Include poor", showPoorMatches, modifier = moveToLanguageRow, onClick = { showPoorMatches = !showPoorMatches }) }
                        item(key = "rejected") { SubtitleFilterPill("Show rejected", showRejected, modifier = moveToLanguageRow, onClick = { showRejected = !showRejected }) }
                    }

                    if (languages.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 3.dp)
                                .focusGroup(),
                            state = rememberLazyListState(),
                        ) {
                            item(key = "all") {
                                SubtitleFilterPill(
                                    "All languages",
                                    selectedLanguage == null,
                                    modifier = Modifier
                                        .focusRequester(firstLanguageRequester)
                                        .focusProperties { up = firstRowRequester },
                                    onClick = { selectedLanguage = null },
                                )
                            }
                            items(languages, key = { it.first }) { (code, label) ->
                                SubtitleFilterPill(
                                    label,
                                    selectedLanguage == code,
                                    modifier = Modifier.focusProperties { up = firstRowRequester },
                                    onClick = {
                                        selectedLanguage = if (selectedLanguage == code) null else code
                                    },
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val best = filtered.filter { it.matchTier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority }
                        val more = filtered.filter { it.matchTier in setOf(SubtitleMatchTier.COMPATIBLE_RELEASE, SubtitleMatchTier.GENERIC_MATCH) }
                        val poor = filtered.filter { it.matchTier == SubtitleMatchTier.POOR_MATCH }
                        val rejected = filtered.filter { it.matchTier == SubtitleMatchTier.REJECTED }
                        fun addSection(label: String, values: List<SubtitleCandidate>) {
                            if (values.isEmpty()) return
                            item(key = "header-$label") {
                                Text(label, color = Amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            itemsIndexed(values, key = { idx, sub -> "$label-$idx-${sub.osFileId}-${sub.directUrl}" }) { idx, sub ->
                                SubtitleResultRow(
                                    index = idx + 1,
                                    candidate = sub,
                                    onFocused = { focusedCandidate = sub },
                                    onClick = { onSelect(sub) },
                                    enabled = sub.matchTier != SubtitleMatchTier.REJECTED,
                                )
                            }
                        }
                        addSection("BEST MATCHES", best)
                        addSection("MORE RESULTS", more)
                        addSection("POOR RELEASE MATCHES", poor)
                        addSection("REJECTED — WRONG TITLE OR EPISODE", rejected)
                        if (filtered.isEmpty()) item {
                            Text(
                                if (state.isLoadingMore) "Searching additional OpenSubtitles pages…"
                                else "No subtitles match the active filters. Choose Search more or relax a filter.",
                                color = Silver,
                            )
                        }
                    }
                    focusedCandidate?.let { candidate ->
                        Text(
                            text = buildString {
                                append(if (candidate.rankingReasons.any { it.startsWith("-") }) "Match evidence: " else "Why Torve ranked this: ")
                                append(candidate.rankingReasons.take(4).joinToString("  ·  "))
                            },
                            color = Silver,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                SubtitleFetchState.Idle -> { /* nothing shown */ }
            }
        }
    }
}


@Composable
private fun SubtitleTrackRow(
    track: TrackDescription,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f,
        label = "subTrackScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            focused          -> Amber
            track.isSelected -> AmberSubtle
            else             -> Graphite.copy(alpha = 0.75f)
        },
        label = "subTrackBg",
    )
    val textColor = if (focused) Obsidian else Snow
    val subColor  = if (focused) Obsidian.copy(alpha = 0.75f) else Silver

    // Language info from track.language code; fall back to parsing the label
    val (flag, langName) = remember(track.language, track.label) {
        when {
            !track.language.isNullOrBlank() -> languageInfo(track.language!!)
            else -> {
                // Label might be "🇬🇧 English · filename" — extract language part
                val lang = track.label.substringBefore('·').trim()
                if (lang.isNotBlank()) ("" to lang) else ("🌐" to track.label)
            }
        }
    }
    // Detail line: anything after the first '·' in the label (e.g. filename from download)
    val detail = track.label.substringAfter('·', "").trim().ifBlank { null }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && isConfirmKey(ev.key)) { onClick(); true } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (flag.isNotBlank()) "$flag  $langName" else langName,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (track.isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (focused) Obsidian else Amber,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun audioCodecLabel(mimeType: String?): String? = when (mimeType?.lowercase()) {
    "audio/eac3-joc"                      -> "Dolby Atmos"
    "audio/eac3"                          -> "Dolby Digital+"
    "audio/ac3"                           -> "Dolby Digital"
    "audio/truehd"                        -> "Dolby TrueHD"
    "audio/vnd.dts.uhd", "audio/dts-uhd" -> "DTS:X"
    "audio/vnd.dts.hd"                   -> "DTS-HD"
    "audio/vnd.dts", "audio/x-dts"       -> "DTS"
    "audio/aac", "audio/mp4a-latm"        -> "AAC"
    "audio/mpeg"                          -> "MP3"
    "audio/opus"                          -> "Opus"
    "audio/vorbis"                        -> "Vorbis"
    "audio/flac"                          -> "FLAC"
    "audio/raw", "audio/l16"             -> "PCM"
    else                                  -> null
}

private fun channelLayoutLabel(count: Int?): String? = when (count) {
    1    -> "Mono"
    2    -> "Stereo"
    6    -> "5.1"
    8    -> "7.1"
    10   -> "7.1.2"
    12   -> "7.1.4"
    else -> count?.let { "${it}ch" }
}

@Composable
private fun AudioTrackRow(
    track: TrackDescription,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f,
        label = "audioScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            focused       -> Amber
            track.isSelected -> AmberSubtle
            else          -> Graphite.copy(alpha = 0.75f)
        },
        label = "audioBg",
    )
    val textColor = if (focused) Obsidian else Snow
    val subColor  = if (focused) Obsidian.copy(alpha = 0.75f) else Silver

    val (flag, langName) = remember(track.language) {
        languageInfo(track.language ?: track.label)
    }
    val codec   = audioCodecLabel(track.formatHint)
    val layout  = channelLayoutLabel(track.channelCount)
    val details = listOfNotNull(codec, layout).joinToString(" · ")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && isConfirmKey(ev.key)) { onClick(); true } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "$flag  $langName",
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
            )
            if (track.isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (focused) Obsidian else Amber,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun SubtitleFilterPill(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Graphite.copy(alpha = 0.45f)
            isFocused -> Sapphire
            isSelected -> Amber
            else -> Graphite
        },
        label = "pillBg",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { ev ->
                if (!enabled || ev.type != KeyEventType.KeyDown) {
                    false
                } else when {
                    isConfirmKey(ev.key) -> { onClick(); true }
                    ev.key == Key.DirectionLeft && onLeft != null -> { onLeft(); true }
                    ev.key == Key.DirectionRight && onRight != null -> { onRight(); true }
                    ev.key == Key.DirectionUp && onUp != null -> { onUp(); true }
                    ev.key == Key.DirectionDown && onDown != null -> { onDown(); true }
                    else -> false
                }
            }
            // Keep one explicit focus target. Relying on clickable's implicit
            // focus handling is not reliable in directional focus search on Fire TV.
            .focusable(enabled = enabled)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = isSelected
                if (enabled) {
                    onClick(action = { onClick(); true })
                } else {
                    disabled()
                }
            }
            .clip(shape)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> Snow
                    isSelected -> AmberLight
                    else -> Color.Transparent
                },
                shape = shape,
            )
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (isSelected) "✓ $label" else label,
            color = when {
                !enabled -> Silver.copy(alpha = 0.45f)
                isFocused || isSelected -> Obsidian
                else -> Silver
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SubtitleResultRow(
    index: Int,
    candidate: SubtitleCandidate,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (reduceMotion) 1f else if (focused) 1.02f else 1f, label = "subScale")
    val bg by animateColorAsState(
        targetValue = if (focused) Amber else Graphite.copy(alpha = 0.75f),
        label = "subBg",
    )
    val textColor = if (focused) Obsidian else Snow
    val subColor = if (focused) Obsidian.copy(alpha = 0.75f) else Silver

    // Bottom-line label: prefer the subtitle's own label if it's meaningful,
    // then a filename from the URL, then "Subtitle N" so rows are always distinct.
    val resolvedLabel: String = run {
        val base = candidate.displayLabel.trim()
        val langName = candidate.languageName
        if (base.isNotBlank() && base != langName) return@run base
        val urlSegment = (candidate.directUrl ?: "")
            .substringBefore('?').substringAfterLast('/').ifBlank { null }
        if (urlSegment != null && urlSegment.contains('.')) return@run urlSegment
        "Subtitle $index"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && isConfirmKey(ev.key)) {
                    if (enabled) onClick()
                    true
                } else false
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${candidate.flagEmoji}  ${candidate.languageName}",
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = candidate.matchTier.displayLabel,
                color = if (focused) Obsidian else Amber,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Match ${candidate.matchScore}/100",
                color = subColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!enabled) {
                Text(
                    text = "NOT SELECTABLE",
                    color = if (focused) Obsidian else Silver,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (candidate.fromTrusted == true) {
                Text("✓ Trusted", color = if (focused) Obsidian else Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            if (candidate.hearingImpaired == true) {
                Text("SDH", color = subColor, style = MaterialTheme.typography.labelSmall)
            }
            if (candidate.forced == true) {
                Text("Forced", color = subColor, style = MaterialTheme.typography.labelSmall)
            }
            if (candidate.aiTranslated == true) {
                Text("AI", color = subColor, style = MaterialTheme.typography.labelSmall)
            }
            if (candidate.machineTranslated == true) Text("MT", color = subColor, style = MaterialTheme.typography.labelSmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 3.dp),
        ) {
            Text(
                text = resolvedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            candidate.ratings?.let { rating ->
                Text(
                    text = buildString {
                        append(String.format("%.1f ★", rating))
                        candidate.voteCount?.let { append(" ($it votes)") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) Obsidian else Amber,
                )
            }
            candidate.downloadCount?.let { downloads ->
                Text(
                    text = "${compactCount(downloads)} downloads",
                    style = MaterialTheme.typography.labelSmall,
                    color = subColor,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            candidate.releaseName?.takeIf(String::isNotBlank)?.let { release ->
                Text(
                    text = release,
                    style = MaterialTheme.typography.labelSmall,
                    color = subColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            candidate.fps?.let { Text("$it FPS", color = subColor, style = MaterialTheme.typography.labelSmall) }
            Text(candidate.provider, color = subColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun compactCount(value: Int): String = when {
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
    else -> value.toString()
}

private suspend fun requestFocusWithRetry(requester: FocusRequester?) {
    if (requester == null) return
    repeat(8) {
        val requested = runCatching { requester.requestFocus(); true }.getOrDefault(false)
        if (requested) return
        delay(50)
    }
}
private fun requestFocusSafely(requester: FocusRequester?) {
    if (requester == null) return
    runCatching { requester.requestFocus() }
}

