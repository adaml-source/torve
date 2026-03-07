package com.torve.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.player.AudioEqualizer
import com.torve.android.player.EqPreset
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberDark
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

@Composable
fun EqualizerSheet(
    equalizer: AudioEqualizer,
    onDismiss: () -> Unit,
    onStateChanged: (String) -> Unit,
) {
    var enabled by remember { mutableStateOf(equalizer.enabled) }
    var selectedPreset by remember { mutableStateOf<EqPreset?>(null) }
    var bandLevels by remember { mutableStateOf(equalizer.bandLevels) }
    var bassBoost by remember { mutableIntStateOf(equalizer.bassBoostStrength) }
    var virtualizer by remember { mutableIntStateOf(equalizer.virtualizerStrength) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val freqLabels = remember(equalizer) {
        equalizer.bandFrequencies.map { hz ->
            if (hz >= 1000) "${hz / 1000}k" else "$hz"
        }
    }

    // Landscape-friendly: side panel on the right, scrollable content
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onDismiss() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.85f)
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .background(Charcoal)
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { /* consume click */ }
                .padding(12.dp),
        ) {
            // Left column: controls (header, preset, bass/surround, reset)
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                Text(stringResource(R.string.eq_title), color = Snow, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // Enable toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.eq_enabled), color = Silver, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            equalizer.setEnabled(it)
                            onStateChanged(equalizer.toStateString())
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = AmberDark.copy(alpha = 0.5f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                            uncheckedBorderColor = Silver,
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Preset
                PresetSelector(
                    selected = selectedPreset,
                    onSelect = { preset ->
                        selectedPreset = preset
                        equalizer.applyPreset(preset)
                        bandLevels = equalizer.bandLevels
                        bassBoost = equalizer.bassBoostStrength
                        virtualizer = equalizer.virtualizerStrength
                        refreshKey++
                        onStateChanged(equalizer.toStateString())
                    },
                )

                Spacer(Modifier.height(12.dp))

                // Bass boost
                EffectSlider(
                    label = stringResource(R.string.eq_bass),
                    value = bassBoost,
                    enabled = enabled,
                    onValueChange = {
                        bassBoost = it
                        equalizer.setBassBoostStrength(it)
                        onStateChanged(equalizer.toStateString())
                    },
                )

                Spacer(Modifier.height(4.dp))

                // Virtualizer
                EffectSlider(
                    label = stringResource(R.string.eq_surround),
                    value = virtualizer,
                    enabled = enabled,
                    onValueChange = {
                        virtualizer = it
                        equalizer.setVirtualizerStrength(it)
                        onStateChanged(equalizer.toStateString())
                    },
                )

                Spacer(Modifier.height(12.dp))

                // Close + Reset
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = {
                        equalizer.applyPreset(EqPreset.FLAT)
                        bandLevels = equalizer.bandLevels
                        bassBoost = 0
                        virtualizer = 0
                        selectedPreset = EqPreset.FLAT
                        refreshKey++
                        onStateChanged(equalizer.toStateString())
                    }) {
                        Text(stringResource(R.string.common_reset), color = Silver, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close), color = Amber, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Right area: band sliders (horizontal layout, each band is a horizontal row)
            if (equalizer.bandCount > 0) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (i in 0 until equalizer.bandCount) {
                        val label = freqLabels.getOrElse(i) { "$i" }
                        HorizontalBandSlider(
                            label = label,
                            level = bandLevels.getOrElse(i) { 0 },
                            minLevel = equalizer.minLevel,
                            maxLevel = equalizer.maxLevel,
                            enabled = enabled,
                            onLevelChange = { newLevel ->
                                equalizer.setBandLevel(i, newLevel)
                                bandLevels = equalizer.bandLevels
                                selectedPreset = null
                                onStateChanged(equalizer.toStateString())
                            },
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.eq_not_available),
                        color = Silver,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetSelector(
    selected: EqPreset?,
    onSelect: (EqPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Gunmetal)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.label ?: stringResource(R.string.eq_custom),
                color = Snow,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(4.dp))
            Text("\u25BE", color = Silver, fontSize = 12.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            EqPreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            preset.label,
                            color = if (preset == selected) Amber else Color.Unspecified,
                        )
                    },
                    onClick = {
                        onSelect(preset)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Horizontal band slider — one row per frequency band. Landscape-friendly. */
@Composable
private fun HorizontalBandSlider(
    label: String,
    level: Int,
    minLevel: Int,
    maxLevel: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Frequency label
        Text(
            text = label,
            color = Silver,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End,
        )

        Spacer(Modifier.width(8.dp))

        // Horizontal slider
        Slider(
            value = level.toFloat(),
            onValueChange = { onLevelChange(it.toInt()) },
            valueRange = minLevel.toFloat()..maxLevel.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) Amber else Silver,
                activeTrackColor = if (enabled) Amber else Silver.copy(alpha = 0.5f),
                inactiveTrackColor = Graphite,
            ),
        )

        Spacer(Modifier.width(4.dp))

        // dB value
        Text(
            text = "${level / 100}dB",
            color = if (enabled) Amber else Silver,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun EffectSlider(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            "$label: ${value / 10}%",
            color = if (enabled) Snow else Silver,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..1000f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) Amber else Silver,
                activeTrackColor = if (enabled) Amber else Silver.copy(alpha = 0.5f),
                inactiveTrackColor = Graphite,
            ),
        )
    }
}
