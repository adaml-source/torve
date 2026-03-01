package com.streamvault.android.ui.search

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.voice.VoiceInputPhase
import com.streamvault.android.voice.VoiceInputUiState
import com.streamvault.android.voice.rememberVoiceInputController
import com.streamvault.android.ui.theme.Amber

@Composable
fun VoiceSearchButton(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    onStateChanged: (VoiceInputUiState) -> Unit = {},
) {
    val controller = rememberVoiceInputController(
        prompt = "Search for movies or shows",
        onTranscript = onResult,
    )
    LaunchedEffect(controller.uiState.value) {
        onStateChanged(controller.uiState.value)
    }

    IconButton(
        onClick = {
            if (
                controller.uiState.value.phase == VoiceInputPhase.Error ||
                controller.uiState.value.phase == VoiceInputPhase.Unsupported
            ) {
                controller.clearState()
            }
            controller.launch()
        },
        modifier = modifier,
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = "Voice search",
            tint = Amber,
            modifier = Modifier.size(24.dp),
        )
    }
}
