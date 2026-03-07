package com.torve.android.ui.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
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
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.player.TrackDescription
import kotlinx.coroutines.delay

private enum class TrackTab {
    SUBTITLES,
    AUDIO,
}

@Composable
fun TvTrackSelectionOverlay(
    subtitleTracks: List<TrackDescription>,
    audioTracks: List<TrackDescription>,
    onSelectSubtitle: (TrackDescription?) -> Unit,
    onSelectAudio: (TrackDescription) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val subtitlesLabel = stringResource(R.string.player_subtitles)
    val audioLabel = stringResource(R.string.player_audio)
    val tabs = remember(subtitleTracks, audioTracks) {
        buildList {
            if (subtitleTracks.isNotEmpty()) add(TrackTab.SUBTITLES)
            if (audioTracks.isNotEmpty()) add(TrackTab.AUDIO)
        }
    }

    var selectedTab by remember(tabs) {
        mutableStateOf(tabs.firstOrNull() ?: TrackTab.SUBTITLES)
    }

    val tabRequesters = remember(tabs) { List(tabs.size) { FocusRequester() } }
    val firstRowRequester = remember { FocusRequester() }

    LaunchedEffect(tabs) {
        if (tabs.size > 1) {
            requestFocusWithRetry(tabRequesters.firstOrNull())
        } else {
            requestFocusWithRetry(firstRowRequester)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.96f)),
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
                text = "Back to close. Press Enter to select.",
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (tabs.size > 1) {
                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        TvOverlayTabChip(
                            label = if (tab == TrackTab.SUBTITLES) subtitlesLabel else audioLabel,
                            isSelected = selectedTab == tab,
                            modifier = Modifier.focusRequester(tabRequesters[index]),
                            onClick = { selectedTab = tab },
                            onMoveLeft = {
                                if (index > 0) {
                                    selectedTab = tabs[index - 1]
                                    requestFocusSafely(tabRequesters[index - 1])
                                }
                            },
                            onMoveRight = {
                                if (index < tabs.lastIndex) {
                                    selectedTab = tabs[index + 1]
                                    requestFocusSafely(tabRequesters[index + 1])
                                }
                            },
                        )
                    }
                }
            }

            val showSubtitles = selectedTab == TrackTab.SUBTITLES

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
                        TvTrackRow(
                            label = track.label,
                            isSelected = track.isSelected,
                            onClick = { onSelectSubtitle(track) },
                        )
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
                        TvTrackRow(
                            label = track.label,
                            isSelected = track.isSelected,
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

    LaunchedEffect(Unit) {
        requestFocusWithRetry(firstFocusRequester)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.96f)),
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected || focused) Snow else Silver,
        )
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

