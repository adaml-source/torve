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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import com.streamvault.android.R
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
import com.streamvault.presentation.iptv.IptvViewModel
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
    onStreamingServicesClick: () -> Unit = {},
    onAddonCatalogClick: () -> Unit = {},
    onRegexPatternsClick: () -> Unit = {},
    onStreamGroupsClick: () -> Unit = {},
    onHomeLayoutClick: () -> Unit = {},
    onMdbListClick: () -> Unit = {},
    onRatingSettingsClick: () -> Unit = {},
    onCardStyleClick: () -> Unit = {},
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
            text = stringResource(R.string.settings_title),
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
                Text(stringResource(R.string.settings_profiles))
            }
            OutlinedButton(
                onClick = onSubscriptionClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text(stringResource(R.string.settings_subscription))
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
                Text(stringResource(R.string.settings_downloads))
            }
            OutlinedButton(
                onClick = onCalendarClick,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text(stringResource(R.string.settings_calendar))
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Cloud Service Section ──
        SectionHeader(title = stringResource(R.string.settings_cloud_service))
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
                                Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
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
                        label = state.debridUser?.username ?: stringResource(R.string.settings_connected),
                        sublabel = state.debridUser?.expiresAt?.let { stringResource(R.string.settings_premium_until, it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectDebrid() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                    ) {
                        Text(stringResource(R.string.common_disconnect))
                    }
                } else if (state.debridDeviceCode != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.settings_go_to), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.debridDeviceCode!!.verificationUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${stringResource(R.string.settings_enter_code)} ${state.debridDeviceCode!!.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.isPollingDebrid) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                stringResource(R.string.settings_waiting_auth),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    SettingsTextField(
                        value = state.debridApiKey,
                        onValueChange = { viewModel.setDebridApiKey(it) },
                        label = stringResource(R.string.settings_api_key),
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
                                Text(stringResource(R.string.common_connect))
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
                                Text(stringResource(R.string.settings_device_auth))
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
        SectionHeader(title = stringResource(R.string.settings_trakt))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.traktConnected) {
                    ConnectionStatus(
                        connected = true,
                        label = state.traktUser?.username ?: stringResource(R.string.settings_connected),
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
                                Text(stringResource(R.string.settings_movies), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${stats.episodesWatched}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(stringResource(R.string.settings_episodes), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${stats.minutesWatched / 60}h",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(stringResource(R.string.settings_watch_time), style = MaterialTheme.typography.bodySmall)
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
                            Text(stringResource(R.string.settings_scrobble), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.settings_scrobble_desc),
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
                                stringResource(R.string.settings_api_status, status),
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
                        Text(stringResource(R.string.common_disconnect))
                    }
                } else if (state.traktDeviceCode != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.settings_go_to), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.traktDeviceCode!!.verificationUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${stringResource(R.string.settings_enter_code)} ${state.traktDeviceCode!!.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.isPollingTrakt) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                stringResource(R.string.settings_waiting_auth),
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
                            Text(stringResource(R.string.settings_connect_trakt))
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
        SectionHeader(title = stringResource(R.string.settings_simkl))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.simklConnected) {
                    ConnectionStatus(
                        connected = true,
                        label = state.simklUser?.username ?: stringResource(R.string.settings_connected),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectSimkl() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                    ) {
                        Text(stringResource(R.string.common_disconnect))
                    }
                } else if (state.simklDeviceCode != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.settings_go_to), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.simklDeviceCode!!.verificationUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${stringResource(R.string.settings_enter_code)} ${state.simklDeviceCode!!.userCode}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.isPollingSimkl) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.settings_waiting_auth), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.startSimklDeviceAuth() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.simklLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (state.simklLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Obsidian)
                        } else {
                            Text(stringResource(R.string.settings_connect_simkl))
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

        // ── Live TV / IPTV Section ──
        LiveTvSettingsSection()

        Spacer(Modifier.height(24.dp))

        // ── Stream Quality & Size ──
        SectionHeader(title = stringResource(R.string.settings_stream_quality))
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
                                Text(stringResource(R.string.settings_max_quality), style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
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
                                Text(stringResource(R.string.settings_min_quality), style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
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
                    label = stringResource(R.string.settings_max_file_size),
                    placeholder = stringResource(R.string.settings_no_limit),
                )

                Spacer(Modifier.height(12.dp))

                // Cached only toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cached_only), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_cached_only_desc),
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
                        Text(stringResource(R.string.settings_hdr_content), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_hdr_content_desc),
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

                Spacer(Modifier.height(8.dp))

                // Deduplicate results toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_dedupe_results), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_dedupe_results_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.dedupeResults,
                        onCheckedChange = { viewModel.setDedupeResultsEnabled(it) },
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
        SectionHeader(title = stringResource(R.string.settings_playback))
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
                        Text(stringResource(R.string.settings_auto_play), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_auto_play_desc),
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
                        Text(stringResource(R.string.settings_auto_play_next), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_auto_play_next_desc),
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
                                Text(stringResource(R.string.settings_codec_preference), style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
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
                                Text(stringResource(R.string.settings_hdr_mode), style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
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

        // ── Content Management ──
        val addonViewModel: AddonViewModel = koinInject()
        val addonState by addonViewModel.state.collectAsState()
        SectionHeader(title = "Content Management")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsNavRow(
                    title = "Home Layout",
                    subtitle = "Section order & poster style",
                    onClick = onHomeLayoutClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Card Style",
                    subtitle = "Size, hover zoom, watched indicators, appearance",
                    onClick = onCardStyleClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Addons & Content Sources",
                    subtitle = "${addonState.addons.size} installed · Browse & manage",
                    onClick = onAddonCatalogClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Streaming Services",
                    subtitle = "Personalize home services",
                    onClick = onStreamingServicesClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Stream Groups",
                    subtitle = "${state.streamGroups.size} groups",
                    onClick = onStreamGroupsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Regex Patterns",
                    subtitle = "${state.regexPatterns.size} patterns",
                    onClick = onRegexPatternsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "MDBList",
                    subtitle = if (state.mdblistApiKey.isNotBlank()) "Connected" else "Curated community lists",
                    onClick = onMdbListClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Ratings",
                    subtitle = "Multi-source rating pills",
                    onClick = onRatingSettingsClick,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Kodi Remote ──
        SectionHeader(title = stringResource(R.string.settings_kodi_remote))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.kodiHosts.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_kodi_hosts),
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
                                Text(stringResource(R.string.common_test), color = Amber)
                            }
                            IconButton(onClick = { viewModel.removeKodiHost(host) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_remove), modifier = Modifier.size(18.dp), tint = Ruby)
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
                        label = stringResource(R.string.settings_kodi_name),
                        placeholder = stringResource(R.string.settings_kodi_name_hint),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsTextField(
                            value = kodiIp,
                            onValueChange = { kodiIp = it },
                            label = stringResource(R.string.settings_kodi_ip),
                            placeholder = "192.168.1.100",
                            modifier = Modifier.weight(2f),
                        )
                        SettingsTextField(
                            value = kodiPort,
                            onValueChange = { kodiPort = it.filter { c -> c.isDigit() } },
                            label = stringResource(R.string.settings_kodi_port),
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
                            Text(stringResource(R.string.common_add))
                        }
                        OutlinedButton(
                            onClick = { showAddKodi = false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            Text(stringResource(R.string.common_cancel))
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
                        Text(stringResource(R.string.settings_add_kodi_host))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── AI Features ──
        SectionHeader(title = "AI Features")
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Choose your AI provider and add your API key to enable AI-powered search. Your provider account must have active billing or credits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
                Spacer(Modifier.height(8.dp))

                // Provider selector
                var aiProviderExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = aiProviderExpanded,
                    onExpandedChange = { aiProviderExpanded = !aiProviderExpanded },
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
                                Text("AI Provider", style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.aiProvider.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = aiProviderExpanded,
                        onDismissRequest = { aiProviderExpanded = false },
                    ) {
                        com.streamvault.data.ai.AiProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.label) },
                                onClick = {
                                    viewModel.setAiProvider(provider)
                                    aiProviderExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = state.activeAiApiKey,
                    onValueChange = { viewModel.setActiveAiApiKey(it) },
                    label = "${state.aiProvider.label} API Key",
                    placeholder = state.aiProvider.keyPlaceholder,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "API keys only work when the provider account has funds. If your balance is zero, AI search will fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textSecondary,
                )
                if (state.activeAiApiKey.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AI search enabled (${state.aiProvider.label})",
                        style = MaterialTheme.typography.labelSmall,
                        color = Emerald,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Appearance ──
        SectionHeader(title = stringResource(R.string.settings_appearance))
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
                                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodySmall, color = StreamVault.colors.textSecondary)
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

                Spacer(Modifier.height(12.dp))

                // Language selector
                val languageContext = LocalContext.current
                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = !languageExpanded },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        color = Gunmetal,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_language),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StreamVault.colors.textSecondary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.appLanguage.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = StreamVault.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        AppLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.displayName) },
                                onClick = {
                                    viewModel.setAppLanguage(lang)
                                    languageExpanded = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val lm = languageContext.getSystemService(LocaleManager::class.java)
                                        lm.applicationLocales = LocaleList.forLanguageTags(lang.code)
                                    }
                                },
                            )
                        }
                    }
                }

            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Storage & Cache ──
        SectionHeader(title = stringResource(R.string.settings_storage))
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
                        Text(stringResource(R.string.settings_clear_cache), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_clear_cache_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamVault.colors.textSecondary,
                        )
                    }
                    if (state.cacheCleared) {
                        Text(stringResource(R.string.settings_cleared), style = MaterialTheme.typography.bodySmall, color = Emerald)
                    } else {
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            Text(stringResource(R.string.common_clear))
                        }
                    }
                }

                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        containerColor = Charcoal,
                        title = { Text(stringResource(R.string.settings_clear_cache), color = Snow) },
                        text = { Text(stringResource(R.string.settings_clear_cache_confirm), color = StreamVault.colors.textSecondary) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.clearCache()
                                    showClearConfirm = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Ruby, contentColor = Snow),
                            ) {
                                Text(stringResource(R.string.common_clear))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) {
                                Text(stringResource(R.string.common_cancel), color = StreamVault.colors.textSecondary)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Backup & Sync ──
        SectionHeader(title = stringResource(R.string.settings_backup_sync))
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
                        stringResource(R.string.settings_last_backup, dateStr),
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
                        Text(stringResource(R.string.settings_export_backup))
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
                    Text(stringResource(R.string.settings_import_backup))
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
                    stringResource(R.string.settings_backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Account ──
        SectionHeader(title = stringResource(R.string.settings_account))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_account_coming_soon),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamVault.colors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_account_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── About & Legal ──
        SectionHeader(title = stringResource(R.string.settings_about))
        Spacer(Modifier.height(8.dp))

        val context = LocalContext.current

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsLinkItem(
                    title = stringResource(R.string.settings_privacy_policy),
                    onClick = onPrivacyPolicyClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_terms),
                    onClick = onTermsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_help),
                    onClick = onHelpClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = stringResource(R.string.settings_share_app),
                    onClick = {
                        val shareTitle = context.getString(R.string.settings_share_title)
                        val shareText = context.getString(R.string.settings_share_text)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_version, "0.5.0"),
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

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Snow,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StreamVault.colors.textSecondary,
            )
        }
        Text(">", style = MaterialTheme.typography.bodyMedium, color = StreamVault.colors.textTertiary)
    }
}

@Composable
private fun LiveTvSettingsSection() {
    val iptvViewModel: IptvViewModel = koinInject()
    val iptvState by iptvViewModel.state.collectAsState()

    SectionHeader(title = stringResource(R.string.settings_live_tv))
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (iptvState.playlists.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamVault.colors.textTertiary,
                )
            } else {
                iptvState.playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow,
                            )
                            Text(
                                text = stringResource(R.string.settings_channels_count, playlist.channelCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = StreamVault.colors.textTertiary,
                            )
                        }
                        IconButton(
                            onClick = { iptvViewModel.deletePlaylist(playlist.id) },
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = Ruby,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { iptvViewModel.showAddPlaylistDialog() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Obsidian,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_add_playlist), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // Add Playlist Dialog (shared with IptvScreen)
    if (iptvState.showAddPlaylist) {
        com.streamvault.android.ui.iptv.AddPlaylistDialog(
            name = iptvState.newPlaylistName,
            url = iptvState.newPlaylistUrl,
            epgUrl = iptvState.newPlaylistEpgUrl,
            playlistType = iptvState.newPlaylistType,
            xtreamServer = iptvState.newXtreamServer,
            xtreamUsername = iptvState.newXtreamUsername,
            xtreamPassword = iptvState.newXtreamPassword,
            isLoading = iptvState.isAddingPlaylist,
            onNameChange = { iptvViewModel.setNewPlaylistName(it) },
            onUrlChange = { iptvViewModel.setNewPlaylistUrl(it) },
            onEpgUrlChange = { iptvViewModel.setNewPlaylistEpgUrl(it) },
            onTypeChange = { iptvViewModel.setNewPlaylistType(it) },
            onXtreamServerChange = { iptvViewModel.setNewXtreamServer(it) },
            onXtreamUsernameChange = { iptvViewModel.setNewXtreamUsername(it) },
            onXtreamPasswordChange = { iptvViewModel.setNewXtreamPassword(it) },
            onConfirm = { iptvViewModel.addPlaylist() },
            onDismiss = { iptvViewModel.dismissAddPlaylistDialog() },
        )
    }
}
