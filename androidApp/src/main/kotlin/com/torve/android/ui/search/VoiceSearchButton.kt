package com.torve.android.ui.search

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.voice.VoiceInputPhase
import com.torve.android.voice.VoiceInputUiState
import com.torve.android.voice.rememberVoiceInputController
import com.torve.android.ui.theme.Amber

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
            contentDescription = stringResource(R.string.common_voice_search_cd),
            tint = Amber,
            modifier = Modifier.size(24.dp),
        )
    }
}
