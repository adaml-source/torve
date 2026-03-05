package com.streamvault.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.sync.RequireSync
import com.streamvault.android.ui.sync.SyncGateReason
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.android.ui.theme.Emerald
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.sync.AccountSyncManager
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    onSyncRequired: () -> Unit = {},
    viewModel: SettingsViewModel = koinInject(),
    accountSyncManager: AccountSyncManager = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val syncStatus by accountSyncManager.status.collectAsState()
    val context = LocalContext.current
    val secretStore: IntegrationSecretStore = koinInject()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)?.let { stored ->
            viewModel.setJellyfinApiKey(stored)
        }
        viewModel.loadJellyfinProfiles()
        secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)?.let { stored ->
            viewModel.setPlexAccessToken(stored)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackButton(onClick = onBack)
        Spacer(Modifier.height(8.dp))
        Text(
            "Integrations",
            style = MaterialTheme.typography.titleLarge,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Connect external services to sync your library and progress.",
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
        )

        Spacer(Modifier.height(16.dp))

        // ── OMDB ──
        IntegrationCard(
            title = "OMDB",
            description = "Free API key for IMDb, Rotten Tomatoes, and Metacritic ratings. Get yours at omdbapi.com",
        ) {
            IntegrationTextField(
                label = "API Key",
                value = state.omdbApiKey,
                onValueChange = { value ->
                    viewModel.setOmdbApiKey(value)
                    scope.launch {
                        if (value.isBlank()) {
                            secretStore.remove(IntegrationSecretKey.OMDB_API_KEY)
                        } else {
                            secretStore.put(IntegrationSecretKey.OMDB_API_KEY, value)
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.validateOmdbApiKey() },
                    enabled = !state.omdbValidating && state.omdbApiKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                ) {
                    if (state.omdbValidating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Obsidian, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.omdbValidating) "Testing..." else "Save & Test")
                }
                state.omdbValidationResult?.let { result ->
                    when (result) {
                        "valid" -> Text("Key is valid", color = Emerald, style = MaterialTheme.typography.bodySmall)
                        "invalid" -> Text("Key is invalid or inactive", color = Ruby, style = MaterialTheme.typography.bodySmall)
                        else -> Text(result, color = Ruby, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Get a free key at omdbapi.com (1,000 requests/day).",
                color = Silver.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        RequireSync(
            reason = SyncGateReason.CLOUD_PROVIDER_TOKENS,
            status = syncStatus,
            onProceed = onSyncRequired,
        ) {
        // ── Trakt ──
        IntegrationCard(
            title = "Trakt",
            description = "OAuth device authorization and two-way sync (watchlist/history/progress).",
        ) {
            Text(
                if (state.traktConnected) "Status: Connected" else "Status: Not connected",
                color = if (state.traktConnected) Snow else Silver,
                style = MaterialTheme.typography.bodySmall,
            )
            state.traktLastSyncTime?.let { lastSync ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last sync: " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(lastSync)),
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.traktDeviceCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                DeviceCodeSection(
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!state.traktConnected) {
                    Button(
                        onClick = { viewModel.startTraktDeviceAuth() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.traktLoading) "Connecting..." else "Connect") }
                } else {
                    Button(
                        onClick = { viewModel.syncTraktNow() },
                        enabled = !state.traktSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) {
                        if (state.traktSyncing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Obsidian,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Syncing...")
                        } else if (state.traktSyncSuccess) {
                            Text("Sync complete")
                        } else {
                            Text("Sync now")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.disconnectTrakt() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                    ) { Text("Disconnect") }
                }
            }
            state.traktError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── SIMKL ──
        IntegrationCard(
            title = "SIMKL",
            description = "Automatic anime and TV show tracking with device code authorization.",
        ) {
            Text(
                if (state.simklConnected) "Status: Connected" + (state.simklUser?.username?.let { " ($it)" } ?: "") else "Status: Not connected",
                color = if (state.simklConnected) Snow else Silver,
                style = MaterialTheme.typography.bodySmall,
            )
            state.simklDeviceCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                DeviceCodeSection(
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!state.simklConnected) {
                    Button(
                        onClick = { viewModel.startSimklDeviceAuth() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.simklLoading) "Connecting..." else "Connect") }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.disconnectSimkl() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                    ) { Text("Disconnect") }
                }
            }
            state.simklError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Jellyfin ──
        IntegrationCard(
            title = "Jellyfin",
            description = "Connect to your Jellyfin server for library sync and continue-watching overlay.",
        ) {
            IntegrationTextField(
                label = "Server URL",
                value = state.jellyfinServerUrl,
                onValueChange = viewModel::setJellyfinServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = "API Key",
                value = state.jellyfinApiKey,
                onValueChange = { value ->
                    viewModel.setJellyfinApiKey(value)
                    scope.launch {
                        if (value.isBlank()) {
                            secretStore.remove(IntegrationSecretKey.JELLYFIN_API_KEY)
                        } else {
                            secretStore.put(IntegrationSecretKey.JELLYFIN_API_KEY, value)
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.testJellyfinConnection() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Test Connection")
            }
            state.jellyfinStatusMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
            }

            // Profile selector (visible after successful connection)
            if (state.jellyfinProfiles.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("User Profile", color = Snow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                state.jellyfinProfiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectJellyfinProfile(profile.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.selectedJellyfinUserId == profile.id,
                            onClick = { viewModel.selectJellyfinProfile(profile.id) },
                            colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Silver),
                        )
                        Text(
                            text = profile.name + if (profile.isAdmin) " (Admin)" else "",
                            color = Snow,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Plex ──
        IntegrationCard(
            title = "Plex",
            description = "Connect your Plex server for library badges and continue watching.",
        ) {
            IntegrationTextField(
                label = "Server URL (e.g. http://192.168.1.100:32400)",
                value = state.plexServerUrl,
                onValueChange = viewModel::setPlexServerUrl,
            )
            Spacer(Modifier.height(8.dp))
            IntegrationTextField(
                label = "Access Token",
                value = state.plexAccessToken,
                onValueChange = { value ->
                    viewModel.setPlexAccessToken(value)
                    scope.launch {
                        if (value.isBlank()) {
                            secretStore.remove(IntegrationSecretKey.PLEX_ACCESS_TOKEN)
                        } else {
                            secretStore.put(IntegrationSecretKey.PLEX_ACCESS_TOKEN, value)
                        }
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Find your token at plex.tv/claim or in Plex app settings.",
                color = Silver.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            if (state.plexConnected) {
                Text(
                    "Status: Connected",
                    color = Snow,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
            }

            state.plexError?.let {
                Text(it, color = Silver, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }

            Row {
                if (!state.plexConnected) {
                    Button(
                        onClick = { viewModel.testPlexConnection() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text(if (state.plexLoading) "Testing..." else "Connect") }
                } else {
                    Button(
                        onClick = { viewModel.testPlexConnection() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
                    ) { Text("Test Connection") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.disconnectPlex() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                    ) { Text("Disconnect") }
                }
            }
        }
        }
    }
}

/** Reusable device-code auth section: clickable URL, copyable code, open-in-browser button. */
@Composable
private fun DeviceCodeSection(
    userCode: String,
    verificationUrl: String,
) {
    val context = LocalContext.current

    // Clickable verification URL
    Text(
        text = verificationUrl,
        color = Amber,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
        },
    )

    Spacer(Modifier.height(8.dp))

    // Code with copy button
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Gunmetal, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Your code", style = MaterialTheme.typography.bodySmall, color = Silver)
            Text(
                text = userCode,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                letterSpacing = MaterialTheme.typography.titleLarge.letterSpacing,
            )
        }
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device Code", userCode))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy code",
                tint = Amber,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Open in browser button
    Button(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
    ) {
        Icon(
            Icons.Default.OpenInBrowser,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Open in Browser")
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    description: String,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Snow, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Silver, style = MaterialTheme.typography.bodySmall)
            if (content != null) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun IntegrationTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
            cursorBrush = SolidColor(Amber),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gunmetal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (value.isBlank()) {
                        Text(label, color = StreamVault.colors.textHint, style = MaterialTheme.typography.bodySmall)
                    }
                    inner()
                }
            },
        )
        }
    }
}
