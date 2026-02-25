package com.streamvault.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints as UiConstraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.player.AudioEqualizer
import com.streamvault.android.player.EqPreset
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberDark
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Graphite
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow

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
        equalizer.bandFrequencies.map { freqMHz ->
            val hz = freqMHz / 1000
            if (hz >= 1000) "${hz / 1000}k" else "$hz"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.55f)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Charcoal),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header: title + enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Equalizer", color = Snow, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Amber)
                    }
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
                        ),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Preset selector
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

            // Band sliders — vertical columns
            if (equalizer.bandCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (i in 0 until equalizer.bandCount) {
                        val label = freqLabels.getOrElse(i) { "$i" }
                        BandSlider(
                            label = label,
                            level = bandLevels.getOrElse(i) { 0 },
                            minLevel = equalizer.minLevel,
                            maxLevel = equalizer.maxLevel,
                            enabled = enabled,
                            onLevelChange = { newLevel ->
                                equalizer.setBandLevel(i, newLevel)
                                bandLevels = equalizer.bandLevels
                                selectedPreset = null // custom
                                onStateChanged(equalizer.toStateString())
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Equalizer not available for this audio session",
                        color = Silver,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bass boost + Virtualizer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EffectSlider(
                    label = "Bass",
                    value = bassBoost,
                    enabled = enabled,
                    onValueChange = {
                        bassBoost = it
                        equalizer.setBassBoostStrength(it)
                        onStateChanged(equalizer.toStateString())
                    },
                    modifier = Modifier.weight(1f),
                )
                EffectSlider(
                    label = "Surround",
                    value = virtualizer,
                    enabled = enabled,
                    onValueChange = {
                        virtualizer = it
                        equalizer.setVirtualizerStrength(it)
                        onStateChanged(equalizer.toStateString())
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Reset button
            TextButton(
                onClick = {
                    equalizer.applyPreset(EqPreset.FLAT)
                    bandLevels = equalizer.bandLevels
                    bassBoost = 0
                    virtualizer = 0
                    selectedPreset = EqPreset.FLAT
                    refreshKey++
                    onStateChanged(equalizer.toStateString())
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Reset to Flat", color = Amber, style = MaterialTheme.typography.labelMedium)
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
                text = selected?.label ?: "Custom",
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

@Composable
private fun BandSlider(
    label: String,
    level: Int,
    minLevel: Int,
    maxLevel: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // dB label
        Text(
            text = "${level / 100}",
            color = if (enabled) Amber else Silver,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )

        // Vertical slider (horizontal slider rotated -90 degrees)
        Box(
            modifier = Modifier
                .weight(1f)
                .width(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = level.toFloat(),
                onValueChange = { onLevelChange(it.toInt()) },
                valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                enabled = enabled,
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = 270f
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            UiConstraints(
                                minWidth = constraints.minHeight,
                                maxWidth = constraints.maxHeight,
                                minHeight = constraints.minWidth,
                                maxHeight = constraints.maxWidth,
                            ),
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(-placeable.width, 0)
                        }
                    },
                colors = SliderDefaults.colors(
                    thumbColor = if (enabled) Amber else Silver,
                    activeTrackColor = if (enabled) Amber else Silver.copy(alpha = 0.5f),
                    inactiveTrackColor = Graphite,
                ),
            )
        }

        // Frequency label
        Text(
            text = label,
            color = Silver,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
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
