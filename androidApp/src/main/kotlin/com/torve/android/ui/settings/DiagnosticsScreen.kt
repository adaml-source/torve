package com.torve.android.ui.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.settings.SettingsViewModel
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
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleLarge, color = Snow, fontWeight = FontWeight.Bold)
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
                Text(stringResource(R.string.diagnostics_service_health), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                StatusRow("Cloud", state.debridConnected)
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
                Text(stringResource(R.string.diagnostics_sync_status), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Trakt last sync: ${formatTimestamp(state.traktLastSyncTime)}", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Availability last sync: ${formatTimestamp(state.availabilityLastSyncTime)}", color = Silver, style = MaterialTheme.typography.bodySmall)
                Text("Library overlay last sync: ${formatTimestamp(state.libraryOverlayLastSyncTime)}", color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Content & Configuration
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.diagnostics_content_config), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                DiagRow("Card style presets", "${state.cardStylePresets.size}")
                DiagRow("Global default preset", state.globalDefaultPresetId ?: "default")
                DiagRow("Regex patterns", "${state.regexPatterns.size} active")
                DiagRow("Stream groups", "${state.streamGroups.size}")
                DiagRow("Kodi devices", "${state.kodiHosts.size}")
                DiagRow("AI provider", state.aiProvider.name)
                DiagRow("AI key configured", if (state.activeAiApiKey.isNotBlank()) "Yes" else "No")
                DiagRow("Cloud provider", state.debridProvider.name)
                DiagRow("Connected cloud services", "${state.connectedDebridProviders.size}")
            }
        }
        Spacer(Modifier.height(12.dp))

        // Stream Preferences
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.diagnostics_stream_prefs), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                DiagRow("Max quality", state.maxQuality.name)
                DiagRow("Min quality", state.minQuality.name)
                DiagRow("Max file size", state.maxFileSizeMb?.let { "${it} MB" } ?: "No limit")
                DiagRow("Cached only", state.cachedOnly.toString())
                DiagRow("Codec preference", state.codecPreference.name)
                DiagRow("HDR mode", state.hdrMode.name)
                DiagRow("Dedupe results", state.dedupeResults.toString())
            }
        }
        Spacer(Modifier.height(12.dp))

        // Ratings
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.diagnostics_rating_config), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                DiagRow("Enabled providers", state.ratingPrefs.enabledProviders.joinToString(", ") { it.name })
                DiagRow("Pill style", state.ratingPrefs.pillStyle.name)
                DiagRow("Pill position", state.ratingPrefs.pillPosition.displayName)
                DiagRow("Max ratings on card", "${state.ratingPrefs.maxRatingsOnCard}")
                DiagRow("Show on detail page", state.ratingPrefs.showRatingsOnDetailPage.toString())
                DiagRow("Torve on cards", state.ratingPrefs.showTorveScoreOnCards.toString())
                DiagRow("Landscape ratings", state.ratingPrefs.allowRatingsOnLandscapeCards.toString())
            }
        }
        Spacer(Modifier.height(12.dp))

        // Integrations detail
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Charcoal)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.diagnostics_integrations_detail), color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                DiagRow("Jellyfin URL", if (state.jellyfinServerUrl.isNotBlank()) state.jellyfinServerUrl else "Not configured")
                DiagRow("Jellyfin profiles", "${state.jellyfinProfiles.size}")
                DiagRow("Plex URL", if (state.plexServerUrl.isNotBlank()) state.plexServerUrl else "Not configured")
                DiagRow("Plex connected", state.plexConnected.toString())
                DiagRow("Trakt scrobble", state.traktScrobbleEnabled.toString())
                DiagRow("Theme", state.themeMode.name)
                DiagRow("Language", state.appLanguage.displayName)
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
        val copiedMessage = stringResource(R.string.diagnostics_copied)
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
                Text(stringResource(R.string.common_share))
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Torve diagnostics", diagnostics))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text(stringResource(R.string.diagnostics_copy))
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
            if (connected) stringResource(R.string.diagnostics_connected) else stringResource(R.string.diagnostics_not_configured),
            color = if (connected) Snow else Silver,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Silver, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = Snow, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
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
        "debridProvider" to settingsState.debridProvider.name,
        "connectedDebridCount" to settingsState.connectedDebridProviders.size.toString(),
        "simklConnected" to settingsState.simklConnected.toString(),
        "jellyfinConfigured" to settingsState.jellyfinApiKey.isNotBlank().toString(),
        "jellyfinUrl" to settingsState.jellyfinServerUrl.takeIf { it.isNotBlank() }.orEmpty(),
        "plexConnected" to settingsState.plexConnected.toString(),
        "plexUrl" to settingsState.plexServerUrl.takeIf { it.isNotBlank() }.orEmpty(),
        "regionCode" to settingsState.regionCode,
        "traktLastSync" to (settingsState.traktLastSyncTime?.toString() ?: ""),
        "availabilityLastSync" to (settingsState.availabilityLastSyncTime?.toString() ?: ""),
        "libraryOverlayLastSync" to (settingsState.libraryOverlayLastSyncTime?.toString() ?: ""),
        "ratingProviders" to settingsState.ratingPrefs.enabledProviders.joinToString(",") { it.name },
        "ratingPillStyle" to settingsState.ratingPrefs.pillStyle.name,
        "ratingPillPosition" to settingsState.ratingPrefs.pillPosition.name,
        "maxRatingsOnCard" to settingsState.ratingPrefs.maxRatingsOnCard.toString(),
        "cardStylePresets" to settingsState.cardStylePresets.size.toString(),
        "globalDefaultPreset" to (settingsState.globalDefaultPresetId ?: "default"),
        "regexPatterns" to settingsState.regexPatterns.size.toString(),
        "streamGroups" to settingsState.streamGroups.size.toString(),
        "kodiHosts" to settingsState.kodiHosts.size.toString(),
        "aiProvider" to settingsState.aiProvider.name,
        "aiKeyConfigured" to settingsState.activeAiApiKey.isNotBlank().toString(),
        "mdblistConfigured" to settingsState.mdblistApiKey.isNotBlank().toString(),
        "maxQuality" to settingsState.maxQuality.name,
        "minQuality" to settingsState.minQuality.name,
        "maxFileSizeMb" to (settingsState.maxFileSizeMb?.toString() ?: "unlimited"),
        "cachedOnly" to settingsState.cachedOnly.toString(),
        "codecPreference" to settingsState.codecPreference.name,
        "hdrMode" to settingsState.hdrMode.name,
        "traktScrobble" to settingsState.traktScrobbleEnabled.toString(),
        "dedupeResults" to settingsState.dedupeResults.toString(),
        "theme" to settingsState.themeMode.name,
        "language" to settingsState.appLanguage.code,
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
