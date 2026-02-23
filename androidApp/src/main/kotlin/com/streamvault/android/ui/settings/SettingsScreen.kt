package com.streamvault.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.StreamQuality
import com.streamvault.data.auth.AuthClient
import com.streamvault.data.auth.AuthUser
import com.streamvault.domain.model.CodecPreference
import com.streamvault.domain.model.HdrMode
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Emerald
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.presentation.addon.AddonViewModel
import com.streamvault.presentation.settings.AppLanguage
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.settings.ThemeMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDownloadsClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
    onProfilesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onPrivacyPolicyClick: () -> Unit = {},
    onTermsClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayMedium,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        // Quick links
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onProfilesClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Profiles")
            }
            OutlinedButton(
                onClick = onSubscriptionClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Subscription")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDownloadsClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Downloads")
            }
            OutlinedButton(
                onClick = onCalendarClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text("Calendar")
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Cloud Service Section ──
        SectionHeader(title = "Cloud Service")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Provider selector
                var providerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = !providerExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Provider", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.debridProvider.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        DebridServiceType.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.label) },
                                onClick = {
                                    viewModel.setDebridProvider(provider)
                                    providerExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (state.debridConnected) {
                    ConnectionStatus(
                        connected = true,
                        label = state.debridUser?.username ?: "Connected",
                        sublabel = state.debridUser?.expiresAt?.let { "Premium until $it" },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectDebrid() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                    ) {
                        Text("Disconnect")
                    }
                } else if (state.debridDeviceCode != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Go to:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.debridDeviceCode!!.verificationUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Enter code: ${state.debridDeviceCode!!.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.isPollingDebrid) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                "Waiting for authorization...",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    SettingsTextField(
                        value = state.debridApiKey,
                        onValueChange = { viewModel.setDebridApiKey(it) },
                        label = "API Key",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.connectDebridWithApiKey() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.debridLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Obsidian,
                            ),
                        ) {
                            if (state.debridLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Obsidian,
                                )
                            } else {
                                Text("Connect")
                            }
                        }
                        if (state.debridProvider == DebridServiceType.REAL_DEBRID ||
                            state.debridProvider == DebridServiceType.ALL_DEBRID
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.startDebridDeviceAuth() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                            ) {
                                Text("Device Auth")
                            }
                        }
                    }
                }

                state.debridError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Trakt Section ──
        SectionHeader(title = "Trakt.tv")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.traktConnected) {
                    ConnectionStatus(
                        connected = true,
                        label = state.traktUser?.username ?: "Connected",
                        sublabel = if (state.traktUser?.vip == true) "VIP" else null,
                    )

                    // Trakt Stats
                    state.traktStats?.let { stats ->
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${stats.moviesWatched}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("Movies", style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${stats.episodesWatched}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("Episodes", style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${stats.minutesWatched / 60}h",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("Watch Time", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Scrobble toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scrobble", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Auto-track what you watch",
                                style = MaterialTheme.typography.bodySmall,
                                color = StreamVault.colors.textSecondary,
                            )
                        }
                        Switch(
                            checked = state.traktScrobbleEnabled,
                            onCheckedChange = { viewModel.setTraktScrobbleEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Amber,
                                checkedTrackColor = Amber.copy(alpha = 0.3f),
                                uncheckedThumbColor = Silver,
                                uncheckedTrackColor = Gunmetal,
                            ),
                        )
                    }

                    // API Status
                    state.traktApiStatus?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (status == "Online") Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (status == "Online") Emerald else Ruby,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "API: $status",
                                style = MaterialTheme.typography.bodySmall,
                                color = StreamVault.colors.textSecondary,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectTrakt() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                    ) {
                        Text("Disconnect")
                    }
                } else if (state.traktDeviceCode != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Go to:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.traktDeviceCode!!.verificationUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Enter code: ${state.traktDeviceCode!!.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.isPollingTrakt) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                "Waiting for authorization...",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.startTraktDeviceAuth() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.traktLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (state.traktLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Obsidian,
                            )
                        } else {
                            Text("Connect Trakt")
                        }
                    }
                }

                state.traktError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── SIMKL Section ──
        SectionHeader(title = "SIMKL")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.simklConnected) {
                    ConnectionStatus(
                        connected = true,
                        label = state.simklUser?.username ?: "Connected",
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectSimkl() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                    ) {
                        Text("Disconnect")
                    }
                } else if (state.simklDeviceCode != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Go to:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.simklDeviceCode!!.verificationUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Enter code: ${state.simklDeviceCode!!.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.isPollingSimkl) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("Waiting for authorization...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    SettingsTextField(
                        value = state.simklClientId,
                        onValueChange = { viewModel.setSimklClientId(it) },
                        label = "Client ID",
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.startSimklDeviceAuth() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.simklLoading && state.simklClientId.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (state.simklLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Obsidian)
                        } else {
                            Text("Authorize with SIMKL")
                        }
                    }
                }

                state.simklError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Ruby)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Stream Quality & Size ──
        SectionHeader(title = "Stream Quality")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Max quality dropdown
                var maxQualityExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = maxQualityExpanded,
                    onExpandedChange = { maxQualityExpanded = !maxQualityExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Max Quality", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.maxQuality.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = maxQualityExpanded,
                        onDismissRequest = { maxQualityExpanded = false },
                    ) {
                        StreamQuality.selectable.forEach { quality ->
                            DropdownMenuItem(
                                text = { Text(quality.label) },
                                onClick = {
                                    viewModel.setMaxQuality(quality)
                                    maxQualityExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Min quality dropdown
                var minQualityExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = minQualityExpanded,
                    onExpandedChange = { minQualityExpanded = !minQualityExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Min Quality", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.minQuality.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = minQualityExpanded,
                        onDismissRequest = { minQualityExpanded = false },
                    ) {
                        StreamQuality.selectable.forEach { quality ->
                            DropdownMenuItem(
                                text = { Text(quality.label) },
                                onClick = {
                                    viewModel.setMinQuality(quality)
                                    minQualityExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Max file size
                var fileSizeText by remember(state.maxFileSizeMb) {
                    mutableStateOf(state.maxFileSizeMb?.toString() ?: "")
                }
                SettingsTextField(
                    value = fileSizeText,
                    onValueChange = { text ->
                        fileSizeText = text.filter { it.isDigit() }
                        val value = fileSizeText.toIntOrNull()
                        viewModel.setMaxFileSizeMb(value)
                    },
                    label = "Max File Size (MB)",
                    placeholder = "No limit",
                )

                Spacer(Modifier.height(12.dp))

                // Cached only toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cached Only", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Only show cached streams for instant playback",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.cachedOnly,
                        onCheckedChange = { viewModel.setCachedOnly(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // HDR toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HDR Content", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Include HDR / Dolby Vision streams",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.hdrEnabled,
                        onCheckedChange = { viewModel.setHdrEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Playback Section ──
        SectionHeader(title = "Playback")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Auto-Play toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Play Best Stream", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Automatically pick and play the best stream",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.autoPlayEnabled,
                        onCheckedChange = { viewModel.setAutoPlayEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Auto-Play Next Episode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Play Next Episode", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Automatically play the next episode when current finishes",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.autoPlayNextEpisodeEnabled,
                        onCheckedChange = { viewModel.setAutoPlayNextEpisodeEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = Silver,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Codec Preference dropdown
                var codecExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = codecExpanded,
                    onExpandedChange = { codecExpanded = !codecExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Codec Preference", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.codecPreference.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = codecExpanded,
                        onDismissRequest = { codecExpanded = false },
                    ) {
                        CodecPreference.entries.forEach { pref ->
                            DropdownMenuItem(
                                text = { Text(pref.label) },
                                onClick = {
                                    viewModel.setCodecPreference(pref)
                                    codecExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // HDR Mode dropdown
                var hdrModeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = hdrModeExpanded,
                    onExpandedChange = { hdrModeExpanded = !hdrModeExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("HDR Mode", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.hdrMode.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = hdrModeExpanded,
                        onDismissRequest = { hdrModeExpanded = false },
                    ) {
                        HdrMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    viewModel.setHdrMode(mode)
                                    hdrModeExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Content Sources Section ──
        val addonViewModel: AddonViewModel = koinInject()
        AddonManagerSection(viewModel = addonViewModel)

        Spacer(Modifier.height(24.dp))

        // ── Kodi Remote ──
        SectionHeader(title = "Kodi Remote")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.kodiHosts.isEmpty()) {
                    Text(
                        "No Kodi hosts configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamVault.colors.textSecondary,
                    )
                } else {
                    state.kodiHosts.forEach { host ->
                        val key = "${host.ip}:${host.port}"
                        val testResult = state.kodiTestResult[key]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(host.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(host.jsonRpcUrl, style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                            }
                            if (testResult != null) {
                                Icon(
                                    if (testResult) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (testResult) Emerald else Ruby,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            TextButton(onClick = { viewModel.testKodiHost(host) }) {
                                Text("Test", color = Amber)
                            }
                            IconButton(onClick = { viewModel.removeKodiHost(host) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp), tint = Ruby)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Add Kodi host
                var showAddKodi by remember { mutableStateOf(false) }
                if (showAddKodi) {
                    var kodiName by remember { mutableStateOf("") }
                    var kodiIp by remember { mutableStateOf("") }
                    var kodiPort by remember { mutableStateOf("8080") }

                    SettingsTextField(
                        value = kodiName,
                        onValueChange = { kodiName = it },
                        label = "Name",
                        placeholder = "Living Room",
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsTextField(
                            value = kodiIp,
                            onValueChange = { kodiIp = it },
                            label = "IP Address",
                            placeholder = "192.168.1.100",
                            modifier = Modifier.weight(2f),
                        )
                        SettingsTextField(
                            value = kodiPort,
                            onValueChange = { kodiPort = it.filter { c -> c.isDigit() } },
                            label = "Port",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (kodiName.isNotBlank() && kodiIp.isNotBlank()) {
                                    viewModel.addKodiHost(kodiName, kodiIp, kodiPort.toIntOrNull() ?: 8080)
                                    showAddKodi = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Obsidian,
                            ),
                        ) {
                            Text("Add")
                        }
                        OutlinedButton(
                            onClick = { showAddKodi = false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            Text("Cancel")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showAddKodi = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Kodi Host")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Appearance ──
        SectionHeader(title = "Appearance")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Theme mode
                var themeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = !themeExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Theme", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    themeExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Language
                var langExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = langExpanded,
                    onExpandedChange = { langExpanded = !langExpanded },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Language", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.appLanguage.displayName, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                        AppLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.displayName) },
                                onClick = {
                                    viewModel.setAppLanguage(lang)
                                    langExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Storage & Cache ──
        SectionHeader(title = "Storage")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var showClearConfirm by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Clear Cache", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Remove cached metadata and images",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    if (state.cacheCleared) {
                        Text("Cleared!", style = MaterialTheme.typography.bodySmall, color = Emerald)
                    } else {
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            Text("Clear")
                        }
                    }
                }

                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        containerColor = Charcoal,
                        title = { Text("Clear Cache", color = Snow) },
                        text = { Text("This will remove all cached metadata. You may need to reload content.", color = StreamVault.colors.textSecondary) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.clearCache()
                                    showClearConfirm = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Ruby, contentColor = Snow),
                            ) {
                                Text("Clear")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) {
                                Text("Cancel", color = StreamVault.colors.textSecondary)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Backup & Sync ──
        SectionHeader(title = "Backup & Sync")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            val backupContext = LocalContext.current

            // File picker for import
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                uri?.let {
                    try {
                        val jsonStr = backupContext.contentResolver.openInputStream(it)
                            ?.bufferedReader()?.use { r -> r.readText() } ?: return@let
                        viewModel.importBackup(jsonStr)
                    } catch (_: Exception) { }
                }
            }

            // File creator for export
            var pendingExportJson by remember { mutableStateOf<String?>(null) }
            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                uri?.let {
                    try {
                        val json = pendingExportJson ?: return@let
                        backupContext.contentResolver.openOutputStream(it)?.use { out ->
                            out.write(json.toByteArray())
                        }
                        pendingExportJson = null
                    } catch (_: Exception) { }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                // Last sync time
                state.lastSyncTime?.let { time ->
                    val dateStr = remember(time) {
                        java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(time))
                    }
                    Text(
                        "Last backup: $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = StreamVault.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Export button
                Button(
                    onClick = {
                        viewModel.exportBackup { json ->
                            pendingExportJson = json
                            exportLauncher.launch("torve_backup.json")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSyncing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = Obsidian,
                    ),
                ) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Obsidian,
                        )
                    } else {
                        Text("Export Backup")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Import button
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSyncing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Text("Import Backup")
                }

                // Success message
                state.syncSuccess?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Emerald,
                    )
                }

                // Error message
                state.syncError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Export your addons, preferences, watch progress, and IPTV favorites to a file. API keys and tokens are never included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Account ──
        val authClient: AuthClient = koinInject()
        val scope = rememberCoroutineScope()
        var authUser by remember { mutableStateOf<AuthUser?>(null) }
        var showDeleteConfirm by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            authUser = authClient.getCurrentUser()
        }

        SectionHeader(title = "Account")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (authUser != null) {
                    Text(
                        authUser!!.displayName ?: authUser!!.email,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        authUser!!.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = StreamVault.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                authClient.logout()
                                authUser = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    ) {
                        Text("Log Out")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                    ) {
                        Text("Delete Account")
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            containerColor = Charcoal,
                            title = { Text("Delete Account", color = Snow) },
                            text = { Text("This will permanently delete your account and all associated data. This action cannot be undone.", color = StreamVault.colors.textSecondary) },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            authClient.deleteAccount()
                                            authUser = null
                                            showDeleteConfirm = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Ruby, contentColor = Snow),
                                ) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text("Cancel", color = StreamVault.colors.textSecondary)
                                }
                            },
                        )
                    }
                } else {
                    Text(
                        "Not logged in",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamVault.colors.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create an account to sync your preferences across devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StreamVault.colors.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── About & Legal ──
        SectionHeader(title = "About")
        Spacer(Modifier.height(8.dp))

        val context = LocalContext.current

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsLinkItem(
                    title = "Privacy Policy",
                    onClick = onPrivacyPolicyClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = "Terms & Conditions",
                    onClick = onTermsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = "Help & Documentation",
                    onClick = onHelpClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = "Share App",
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Torve")
                            putExtra(Intent.EXTRA_TEXT, "Check out Torve - a beautiful media player and content organizer! https://torve.app")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Torve"))
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Torve v0.5.0",
            style = MaterialTheme.typography.bodySmall,
            color = StreamVault.colors.textTertiary,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Amber,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SettingsLinkItem(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = StreamVault.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ">",
            style = MaterialTheme.typography.bodyMedium,
            color = StreamVault.colors.textTertiary,
        )
    }
}

@Composable
private fun ConnectionStatus(
    connected: Boolean,
    label: String,
    sublabel: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (connected) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (connected) Emerald else Ruby,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            sublabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = label,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
            singleLine = true,
            cursorBrush = SolidColor(Amber),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gunmetal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = StreamVault.colors.textHint)
                    }
                    innerTextField()
                }
            },
        )
    }
}
