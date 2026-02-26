package com.streamvault.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.presentation.settings.SettingsUiState
import com.streamvault.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrElse { "unknown" }
    val diagnostics = buildDiagnosticsText(
        appVersion = appVersion,
        buildType = "release",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        settingsState = state,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackButton(onClick = onBack)
        Spacer(Modifier.height(8.dp))
        Text("Diagnostics", style = MaterialTheme.typography.titleLarge, color = Snow, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Device & App info
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("App: $appVersion", color = Snow, style = MaterialTheme.typography.bodyMedium)
                Text("Build: release", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Region: ${state.regionCode}", color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Service Health
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Service Health", color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                StatusRow("Debrid", state.debridConnected)
                StatusRow("Trakt", state.traktConnected)
                StatusRow("SIMKL", state.simklConnected)
                StatusRow("Jellyfin", state.jellyfinApiKey.isNotBlank())
                StatusRow("Plex", state.plexConnected)
                StatusRow("AI (${state.aiProvider.name})", state.activeAiApiKey.isNotBlank())
                StatusRow("MDBList", state.mdblistApiKey.isNotBlank())
            }
        }
        Spacer(Modifier.height(12.dp))

        // Sync timestamps
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sync Status", color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Trakt last sync: ${formatTimestamp(state.traktLastSyncTime)}", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Availability last sync: ${formatTimestamp(state.availabilityLastSyncTime)}", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Library overlay last sync: ${formatTimestamp(state.libraryOverlayLastSyncTime)}", color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Raw diagnostics
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Text(
                diagnostics,
                modifier = Modifier.padding(12.dp),
                color = Silver,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Torve diagnostics")
                        putExtra(Intent.EXTRA_TEXT, diagnostics)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text("Share")
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Torve diagnostics", diagnostics))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Copy")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusRow(label: String, connected: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Silver, style = MaterialTheme.typography.bodySmall)
        Text(
            if (connected) "Connected" else "Not configured",
            color = if (connected) Snow else Silver,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun formatTimestamp(epochMs: Long?): String {
    if (epochMs == null) return "Never"
    return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
}

private fun buildDiagnosticsText(
    appVersion: String,
    buildType: String,
    deviceModel: String,
    androidVersion: String,
    settingsState: SettingsUiState,
): String {
    val redacted = mapOf(
        "traktConnected" to settingsState.traktConnected.toString(),
        "debridConnected" to settingsState.debridConnected.toString(),
        "simklConnected" to settingsState.simklConnected.toString(),
        "jellyfinConfigured" to settingsState.jellyfinApiKey.isNotBlank().toString(),
        "plexConnected" to settingsState.plexConnected.toString(),
        "regionCode" to settingsState.regionCode,
        "traktLastSync" to (settingsState.traktLastSyncTime?.toString() ?: ""),
        "availabilityLastSync" to (settingsState.availabilityLastSyncTime?.toString() ?: ""),
        "libraryOverlayLastSync" to (settingsState.libraryOverlayLastSyncTime?.toString() ?: ""),
        "ratingProviders" to settingsState.ratingPrefs.enabledProviders.joinToString(",") { it.name },
    )
    return buildString {
        appendLine("Torve diagnostics")
        appendLine("appVersion=$appVersion")
        appendLine("buildType=$buildType")
        appendLine("device=$deviceModel")
        appendLine("android=$androidVersion")
        appendLine("settings=${redacted.entries.joinToString(";") { "${it.key}=${it.value}" }}")
    }
}
