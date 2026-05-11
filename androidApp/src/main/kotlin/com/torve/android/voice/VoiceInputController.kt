package com.torve.android.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

enum class VoiceInputPhase {
    Idle,
    Listening,
    Processing,
    Error,
    Unsupported,
}

@Immutable
data class VoiceInputUiState(
    val phase: VoiceInputPhase = VoiceInputPhase.Idle,
    val message: String? = null,
)

class VoiceInputController internal constructor(
    private val launchInternal: () -> Unit,
    private val clearInternal: () -> Unit,
    val uiState: State<VoiceInputUiState>,
) {
    fun launch() {
        launchInternal()
    }

    fun clearState() {
        clearInternal()
    }
}

@Composable
fun rememberVoiceInputController(
    prompt: String,
    onTranscript: (String) -> Unit,
): VoiceInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onTranscriptUpdated by rememberUpdatedState(onTranscript)
    val state = remember { mutableStateOf(VoiceInputUiState()) }
    val noVoiceInputMessage = stringResource(R.string.voice_input_no_input)
    val voiceUnavailableMessage = stringResource(R.string.voice_input_unavailable)
    val voiceFailedMessage = stringResource(R.string.voice_input_failed_start)

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            state.value = VoiceInputUiState()
            return@rememberLauncherForActivityResult
        }
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isBlank()) {
            state.value = VoiceInputUiState(
                phase = VoiceInputPhase.Error,
                message = noVoiceInputMessage,
            )
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            state.value = VoiceInputUiState(phase = VoiceInputPhase.Processing)
            onTranscriptUpdated(transcript)
            delay(250)
            state.value = VoiceInputUiState()
        }
    }

    return remember(context, prompt, speechLauncher) {
        VoiceInputController(
            launchInternal = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                }
                val supported = context.packageManager.resolveActivity(intent, 0) != null
                if (!supported) {
                    state.value = VoiceInputUiState(
                        phase = VoiceInputPhase.Unsupported,
                        message = voiceUnavailableMessage,
                    )
                } else {
                    state.value = VoiceInputUiState(phase = VoiceInputPhase.Listening)
                    try {
                        speechLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        state.value = VoiceInputUiState(
                            phase = VoiceInputPhase.Unsupported,
                            message = voiceUnavailableMessage,
                        )
                    } catch (_: Throwable) {
                        state.value = VoiceInputUiState(
                            phase = VoiceInputPhase.Error,
                            message = voiceFailedMessage,
                        )
                    }
                }
            },
            clearInternal = { state.value = VoiceInputUiState() },
            uiState = state,
        )
    }
}
