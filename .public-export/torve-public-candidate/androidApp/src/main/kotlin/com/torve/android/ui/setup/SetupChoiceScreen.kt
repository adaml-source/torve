package com.torve.android.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SetupChoiceScreen(
    onGuidedSetup: () -> Unit,
    onManualSetup: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "How do you want to set up Torve?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Use the guide if you want Torve to walk you through sources. Choose manual if you already know where your Debrid, Usenet, IPTV, or Plex/Jellyfin settings live.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGuidedSetup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use guided setup")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onManualSetup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Set up manually in Settings")
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onExit) {
            Text("Not now - go to app")
        }
    }
}
