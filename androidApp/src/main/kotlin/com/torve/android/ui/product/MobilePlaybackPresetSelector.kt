package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.ui.system.TorveModalBottomSheet as ModalBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torve.domain.model.PlaybackPreset
import com.torve.domain.model.StreamPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobilePlaybackPresetSelector(preferences: StreamPreferences, onChoose: (PlaybackPreset) -> Unit) {
    val selected = PlaybackPreset.recognize(preferences)
    var open by remember { mutableStateOf(false) }
    ProductToolRow(localizedUiText("Playback preset · ${selected.label}"), selected.description, Icons.Rounded.PlayCircleOutline, { open = true })
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(localizedUiText("How would you like to watch?"), style = MaterialTheme.typography.headlineSmall) }
            items(PlaybackPreset.entries, key = { it.name }) { preset ->
                ProductToolRow(preset.label, localizedUiText(if (preset.isAvailable(preferences)) preset.description else "Choose an audio language before using this preset."),
                    Icons.Rounded.PlayCircleOutline, {
                        if (preset != PlaybackPreset.CUSTOM) onChoose(preset)
                        open = false
                    }, enabled = preset.isAvailable(preferences), selected = preset == selected)
            }
            item { Text(localizedUiText("Custom keeps your current individual settings. Presets preserve your chosen audio language and other preferences they do not control."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}
