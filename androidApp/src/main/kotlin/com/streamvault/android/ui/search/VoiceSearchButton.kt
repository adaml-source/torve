package com.streamvault.android.ui.search

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.Amber
import java.util.Locale

@Composable
fun VoiceSearchButton(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(true) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        matches?.firstOrNull()?.let { onResult(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            launchSpeechRecognizer(speechLauncher)
        }
    }

    IconButton(
        onClick = {
            if (hasPermission) {
                launchSpeechRecognizer(speechLauncher)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
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

private fun launchSpeechRecognizer(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Search for movies or shows...")
    }
    try {
        launcher.launch(intent)
    } catch (_: Exception) {
        // Speech recognizer not available
    }
}
