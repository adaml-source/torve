package com.torve.android.ui.settings

import com.torve.android.BuildConfig
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.torve.android.R
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.StreamQuality
import com.torve.data.auth.AuthClient
import com.torve.data.auth.AuthUser
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.HdrMode
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.android.sync.SyncCoordinator
import com.torve.domain.sync.SyncRepository
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.ThemeMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isLifetimeUnlocked: Boolean = false,
    onLockedFeatureClick: (PremiumFeature) -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
    onProfilesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onDevicesClick: () -> Unit = {},
    onManageDevicesClick: () -> Unit = {},
    onLoginClick: () -> Unit = {},
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
    onIntegrationsClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    viewModel: SettingsViewModel = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    syncRepository: SyncRepository = koinInject(),
    authClient: AuthClient = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val syncState by syncCoordinator.state.collectAsState()
    val accessTier = remember(isLifetimeUnlocked) { PremiumAccess.tierFrom(isLifetimeUnlocked) }
    val isLocked: (PremiumFeature) -> Boolean = remember(accessTier) {
        { feature -> PremiumAccess.isPremiumLocked(feature, accessTier) }
    }
    val onPremiumAction: (PremiumFeature, () -> Unit) -> Unit = remember(isLocked, onLockedFeatureClick) {
        { feature, allowedAction ->
            if (isLocked(feature)) {
                onLockedFeatureClick(feature)
            } else {
                allowedAction()
            }
        }
    }
    val accountSetupLocked = isLocked(PremiumFeature.ACCOUNT_SETUP)
    val accountSignInLocked = isLocked(PremiumFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD)
    val deviceLinkingLocked = isLocked(PremiumFeature.DEVICE_LINKING)
    val pairingLocked = isLocked(PremiumFeature.PHONE_PAIRING)
    val cloudProviderLocked = isLocked(PremiumFeature.CLOUD_PROVIDER_SETUP)
    val customSourcesLocked = isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)
    val addonLocked = isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)
    val mdbListLocked = isLocked(PremiumFeature.MDBLIST_SETUP)
    val traktLocked = isLocked(PremiumFeature.TRAKT_CONNECT)
    val diagnosticsLocked = isLocked(PremiumFeature.DIAGNOSTICS)
    val kodiLocked = isLocked(PremiumFeature.KODI_SETUP)
    val aiProviderLocked = isLocked(PremiumFeature.AI_PROVIDER_SETUP)
    val collectionsLocked = isLocked(PremiumFeature.PERSISTENT_COLLECTIONS)
    val customLayoutLocked = isLocked(PremiumFeature.SYNC_CUSTOM_LAYOUTS)
    val backupLocked = isLocked(PremiumFeature.CLOUD_BACKUP_RESTORE)
    var showSyncSheet by remember { mutableStateOf(false) }
    var authUser by remember { mutableStateOf<AuthUser?>(null) }
    LaunchedEffect(Unit) {
        authUser = authClient.getCurrentUser()
    }
    LaunchedEffect(Unit) {
        if (state.regionCode.isBlank() || state.regionCode == "US") {
            val country = Locale.getDefault().country.uppercase()
            if (country.length == 2 && country != state.regionCode) {
                viewModel.setRegionCode(country)
            }
        }
    }

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

        // Account card (top of settings)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (authUser != null) {
                    Text(
                        text = "Signed in as ${authUser?.email ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                onPremiumAction(PremiumFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) { onLoginClick() }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Obsidian,
                            ),
                        ) {
                            if (accountSignInLocked) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (accountSignInLocked) "Account Settings (Locked)" else "Account Settings")
                        }
                        OutlinedButton(
                            onClick = {
                                onPremiumAction(PremiumFeature.DEVICE_LINKING) { onManageDevicesClick() }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        ) {
                            if (deviceLinkingLocked) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (deviceLinkingLocked) "Manage Devices (Locked)" else "Manage Devices")
                        }
                    }
                } else {
                    Text(
                        text = if (accountSignInLocked) {
                            "Account tools are locked on Free. Unlock Lifetime Access to sign in and sync."
                        } else {
                            "Sign in to manage your subscription and devices."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            onPremiumAction(PremiumFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD) { onLoginClick() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (accountSignInLocked) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (accountSignInLocked) PremiumAccess.UNLOCK_WITH_LIFETIME_LABEL else "Sign In / Create Account")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Quick links
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (BuildConfig.HAS_BILLING) {
                OutlinedButton(
                    onClick = onSubscriptionClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Text(stringResource(R.string.settings_subscription))
                }
            }
            OutlinedButton(
                onClick = {
                    onPremiumAction(PremiumFeature.ACCOUNT_SETUP) { onProfilesClick() }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                if (accountSetupLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (accountSetupLocked) {
                        "${stringResource(R.string.settings_profiles)} (${PremiumAccess.LOCKED_LABEL})"
                    } else {
                        stringResource(R.string.settings_profiles)
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onPremiumAction(PremiumFeature.DOWNLOADS) { onDownloadsClick() }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                if (isLocked(PremiumFeature.DOWNLOADS)) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (isLocked(PremiumFeature.DOWNLOADS)) {
                        "${stringResource(R.string.settings_downloads)} (${PremiumAccess.LOCKED_LABEL})"
                    } else {
                        stringResource(R.string.settings_downloads)
                    },
                )
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

        if (cloudProviderLocked) {
            LockedSettingsCard(
                title = stringResource(R.string.settings_cloud_service),
                description = "Connect cloud providers and debrid services with Lifetime Access.",
                onUnlock = { onLockedFeatureClick(PremiumFeature.CLOUD_PROVIDER_SETUP) },
            )
        } else {
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
                                Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.debridProvider.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
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

                // Show connected provider badges
                if (state.connectedDebridProviders.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.connectedDebridProviders.keys.forEach { p ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (p == state.debridProvider) Amber.copy(alpha = 0.2f) else Gunmetal,
                            ) {
                                Text(
                                    p.label,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (p == state.debridProvider) Amber else Silver,
                                )
                            }
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
                    DebridDeviceCodeSection(
                        userCode = state.debridDeviceCode!!.userCode,
                        verificationUrl = state.debridDeviceCode!!.verificationUrl,
                        isPolling = state.isPollingDebrid,
                    )
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
        }

        // Trakt & SIMKL management moved to Integrations screen

        Spacer(Modifier.height(24.dp))

        // ── Channels Section ──
        LiveTvSettingsSection(
            locked = collectionsLocked,
            onLockedClick = { onLockedFeatureClick(PremiumFeature.PERSISTENT_COLLECTIONS) },
        )

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
                                Text(stringResource(R.string.settings_max_quality), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.maxQuality.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
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
                                Text(stringResource(R.string.settings_min_quality), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.minQuality.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
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
                            color = Torve.colors.textSecondary,
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
                            color = Torve.colors.textSecondary,
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
                            color = Torve.colors.textSecondary,
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

                Spacer(Modifier.height(12.dp))
                Text(
                    "Streaming Availability Region",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "2-letter country code (e.g. US, GB, DE). Determines which streaming services are shown on detail pages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                SettingsTextField(
                    value = state.regionCode,
                    onValueChange = { input ->
                        val normalized = input.trim().uppercase()
                        if (normalized.length <= 2) {
                            viewModel.setRegionCode(normalized)
                        }
                    },
                    label = "Region",
                    placeholder = "US",
                    modifier = Modifier.fillMaxWidth(),
                )
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
                            color = Torve.colors.textSecondary,
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
                            color = Torve.colors.textSecondary,
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
                                Text(stringResource(R.string.settings_codec_preference), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.codecPreference.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
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
                                Text(stringResource(R.string.settings_hdr_mode), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.hdrMode.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
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
                    locked = customLayoutLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.SYNC_CUSTOM_LAYOUTS) { onHomeLayoutClick() }
                    },
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
                    locked = addonLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT) { onAddonCatalogClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Streaming Services",
                    subtitle = "Personalize home services",
                    locked = customSourcesLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT) { onStreamingServicesClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Stream Groups",
                    subtitle = "${state.streamGroups.size} groups",
                    locked = customSourcesLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT) { onStreamGroupsClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Regex Patterns",
                    subtitle = "${state.regexPatterns.size} patterns",
                    locked = customSourcesLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION) { onRegexPatternsClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "MDBList",
                    subtitle = if (state.mdblistApiKey.isNotBlank()) "Connected" else "Curated community lists",
                    locked = mdbListLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.MDBLIST_SETUP) { onMdbListClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Ratings",
                    subtitle = "Multi-source rating pills",
                    onClick = onRatingSettingsClick,
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = "Integrations",
                    subtitle = "Trakt, SIMKL, Jellyfin, Plex",
                    locked = traktLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.TRAKT_CONNECT) { onIntegrationsClick() }
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Kodi Remote ──
        SectionHeader(title = stringResource(R.string.settings_kodi_remote))
        Spacer(Modifier.height(8.dp))

        if (kodiLocked) {
            LockedSettingsCard(
                title = stringResource(R.string.settings_kodi_remote),
                description = "Configure Kodi hosts and remote control integration with Lifetime Access.",
                onUnlock = { onLockedFeatureClick(PremiumFeature.KODI_SETUP) },
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                if (state.kodiHosts.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_kodi_hosts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
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
                                Text(host.jsonRpcUrl, style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
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
        }

        Spacer(Modifier.height(24.dp))

        // ── AI Features ──
        SectionHeader(title = "AI Features")
        Spacer(Modifier.height(8.dp))

        if (aiProviderLocked) {
            LockedSettingsCard(
                title = "AI Features",
                description = "Connect AI providers and API keys with Lifetime Access.",
                onUnlock = { onLockedFeatureClick(PremiumFeature.AI_PROVIDER_SETUP) },
            )
        } else {
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
                                Text(stringResource(R.string.settings_ai_provider), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(state.aiProvider.label, style = MaterialTheme.typography.bodyMedium, color = Snow)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = aiProviderExpanded,
                        onDismissRequest = { aiProviderExpanded = false },
                    ) {
                        com.torve.data.ai.AiProvider.entries.forEach { provider ->
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
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.validateAiApiKey() },
                        enabled = !state.aiKeyValidating && state.activeAiApiKey.isNotBlank(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        if (state.aiKeyValidating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Obsidian, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (state.aiKeyValidating) "Testing..." else "Test Key")
                    }
                    state.aiKeyValidationResult?.let { result ->
                        when (result) {
                            "valid" -> Text(
                                "Key works! (${state.aiProvider.label})",
                                color = Emerald,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> Text(
                                result,
                                color = Ruby,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "API keys only work when the provider account has funds. If your balance is zero, AI search will fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
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
                                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
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
                                    color = Torve.colors.textSecondary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    state.appLanguage.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Snow,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Torve.colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    ExposedDropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        AppLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.displayName) },
                                onClick = {
                                    viewModel.setAppLanguage(lang)
                                    languageExpanded = false
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags(lang.code),
                                    )
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
                            color = Torve.colors.textSecondary,
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
                        text = { Text(stringResource(R.string.settings_clear_cache_confirm), color = Torve.colors.textSecondary) },
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
                                Text(stringResource(R.string.common_cancel), color = Torve.colors.textSecondary)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Backup & Sync ──
        SectionHeader(title = "Backup")
        Spacer(Modifier.height(8.dp))

        if (backupLocked) {
            LockedSettingsCard(
                title = "Backup",
                description = "Backup and restore personalized settings with Lifetime Access.",
                onUnlock = { onLockedFeatureClick(PremiumFeature.CLOUD_BACKUP_RESTORE) },
            )
        } else {
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
                        color = Torve.colors.textSecondary,
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
                    color = Torve.colors.textSecondary,
                )
            }
        }
        }

        Spacer(Modifier.height(24.dp))

        // ── Account ──
        // Local Sync & Pairing
        SectionHeader(title = "Local Sync")
        Spacer(Modifier.height(8.dp))

        if (pairingLocked || deviceLinkingLocked) {
            LockedSettingsCard(
                title = "Local Sync",
                description = "Pair devices and sync settings across screens with Lifetime Access.",
                onUnlock = { onLockedFeatureClick(PremiumFeature.DEVICE_SYNC) },
            )
        } else {
            // Local Sync
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                val syncStatusTitle = if (syncState.isAuthenticated) "Local Sync Ready" else "Local Mode"
                val syncStatusSubtext = if (syncState.isAuthenticated) {
                    "Profile ${syncState.profileName ?: syncState.userEmail ?: "Unknown"} is ready for local Wi-Fi pairing."
                } else {
                    "Create a local profile to pair TVs and hand off playback on your Wi-Fi."
                }

                Text(
                    text = syncStatusTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = syncStatusSubtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Transport: ${syncState.wsStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onPremiumAction(PremiumFeature.ACCOUNT_SETUP) { onAccountClick() }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Obsidian,
                        ),
                    ) {
                        Text(
                            if (accountSetupLocked) {
                                PremiumAccess.UNLOCK_WITH_LIFETIME_LABEL
                            } else if (syncState.isAuthenticated) {
                                "Manage Profile"
                            } else {
                                "Create Profile"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onPremiumAction(PremiumFeature.PHONE_PAIRING) { onDevicesClick() }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = syncState.isAuthenticated || pairingLocked,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    ) {
                        if (pairingLocked) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (pairingLocked) "Pair Device (Locked)" else "Pair Device")
                    }
                }
                val hasPairedTvs = syncState.isAuthenticated &&
                    syncCoordinator.targetDevices().any { it.deviceType == "tv" }
                if (hasPairedTvs) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showSyncSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Emerald,
                            contentColor = Snow,
                        ),
                    ) {
                        Text("Sync Settings to TV")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onPremiumAction(PremiumFeature.DEVICE_LINKING) { onManageDevicesClick() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Text("Manage Devices")
                }
            }
        }
        }

        if (showSyncSheet && !pairingLocked && !deviceLinkingLocked) {
            SyncToTvBottomSheet(
                syncCoordinator = syncCoordinator,
                syncRepository = syncRepository,
                onDismiss = { showSyncSheet = false },
            )
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
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = "Diagnostics",
                    locked = diagnosticsLocked,
                    onClick = {
                        onPremiumAction(PremiumFeature.DIAGNOSTICS) { onDiagnosticsClick() }
                    },
                )
                HorizontalDivider(color = Steel.copy(alpha = 0.3f))
                SettingsLinkItem(
                    title = "Report Issue",
                    onClick = {
                        val versionName = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                        } catch (_: Exception) { "unknown" }
                        val payload = buildIssuePayload(
                            appVersion = versionName,
                            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                            sdkInt = Build.VERSION.SDK_INT,
                            settingsState = state,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Torve issue report")
                            putExtra(Intent.EXTRA_TEXT, payload)
                        }
                        context.startActivity(Intent.createChooser(intent, "Report issue"))
                    },
                )
            }
        }

        // ── Reset Appearance ──
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))

        var showResetAppearanceConfirm by remember { mutableStateOf(false) }

        Text(
            text = "Danger Zone",
            style = MaterialTheme.typography.titleSmall,
            color = Ruby,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showResetAppearanceConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
            border = androidx.compose.foundation.BorderStroke(1.dp, Ruby.copy(alpha = 0.5f)),
        ) {
            Text(stringResource(R.string.settings_reset_appearance))
        }
        Text(
            text = stringResource(R.string.settings_reset_appearance_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textTertiary,
        )

        if (showResetAppearanceConfirm) {
            AlertDialog(
                onDismissRequest = { showResetAppearanceConfirm = false },
                title = { Text(stringResource(R.string.settings_reset_appearance_title)) },
                text = {
                    Text(stringResource(R.string.settings_reset_appearance_confirm))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetAppearanceSettings()
                            showResetAppearanceConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Ruby),
                    ) {
                        Text(stringResource(R.string.common_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetAppearanceConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Steel.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_version, "0.5.0"),
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textTertiary,
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
private fun LockedSettingsCard(
    title: String,
    description: String,
    onUnlock: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$title · ${PremiumAccess.LOCKED_LABEL}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onUnlock,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
                Text(PremiumAccess.UNLOCK_WITH_LIFETIME_LABEL)
            }
        }
    }
}

@Composable
private fun SettingsLinkItem(
    title: String,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (locked) Amber else Torve.colors.textPrimary,
            )
            if (locked) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = ">",
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textTertiary,
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
                    color = Torve.colors.textSecondary,
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
        Text(label, style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
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
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
                    }
                    innerTextField()
                }
            },
        )
    }
}

private fun buildIssuePayload(
    appVersion: String,
    deviceModel: String,
    sdkInt: Int,
    settingsState: com.torve.presentation.settings.SettingsUiState,
): String {
    val redactedSettings = mapOf(
        "themeMode" to settingsState.themeMode.name,
        "appLanguage" to settingsState.appLanguage.code,
        "debridProvider" to settingsState.debridProvider.name,
        "debridConnected" to settingsState.debridConnected.toString(),
        "traktConnected" to settingsState.traktConnected.toString(),
        "traktLastSyncTime" to (settingsState.traktLastSyncTime?.toString() ?: ""),
        "availabilityLastSyncTime" to (settingsState.availabilityLastSyncTime?.toString() ?: ""),
        "libraryOverlayLastSyncTime" to (settingsState.libraryOverlayLastSyncTime?.toString() ?: ""),
        "simklConnected" to settingsState.simklConnected.toString(),
        "mdblistConfigured" to settingsState.mdblistApiKey.isNotBlank().toString(),
        "jellyfinConfigured" to settingsState.jellyfinApiKey.isNotBlank().toString(),
        "jellyfinServerUrl" to settingsState.jellyfinServerUrl,
        "regionCode" to settingsState.regionCode,
        "ratingProviders" to settingsState.ratingPrefs.enabledProviders.joinToString(",") { it.name },
        "maxRatingsOnCard" to settingsState.ratingPrefs.maxRatingsOnCard.toString(),
        "allowRatingsOnLandscapeCards" to settingsState.ratingPrefs.allowRatingsOnLandscapeCards.toString(),
        "pillPosition" to settingsState.ratingPrefs.pillPosition.name,
        "torveWeights" to settingsState.ratingPrefs.torveWeights.entries.joinToString(",") { "${it.key.name}:${it.value}" },
        "globalDefaultPresetId" to (settingsState.globalDefaultPresetId ?: ""),
        "presetCount" to settingsState.cardStylePresets.size.toString(),
    )
    return buildString {
        appendLine("Torve issue report")
        appendLine("appVersion=$appVersion")
        appendLine("device=$deviceModel")
        appendLine("sdkInt=$sdkInt")
        appendLine("settings=${redactedSettings.entries.joinToString(";") { "${it.key}=${it.value}" }}")
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    locked: Boolean = false,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Snow,
                    fontWeight = FontWeight.Medium,
                )
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Amber,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = if (locked) {
                    "$subtitle · ${PremiumAccess.LIFETIME_REQUIRED_LABEL}"
                } else {
                    subtitle
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (locked) Amber.copy(alpha = 0.9f) else Torve.colors.textSecondary,
            )
        }
        Text(">", style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textTertiary)
    }
}

@Composable
private fun LiveTvSettingsSection(
    locked: Boolean = false,
    onLockedClick: () -> Unit = {},
) {
    if (locked) {
        SectionHeader(title = stringResource(R.string.settings_live_tv))
        Spacer(Modifier.height(8.dp))
        LockedSettingsCard(
            title = stringResource(R.string.settings_live_tv),
            description = "Manage channel playlists and persistent favorites with Lifetime Access.",
            onUnlock = onLockedClick,
        )
        return
    }

    val channelsViewModel: ChannelsViewModel = koinInject()
    val channelsState by channelsViewModel.state.collectAsState()

    SectionHeader(title = stringResource(R.string.settings_live_tv))
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (channelsState.playlists.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
                )
            } else {
                channelsState.playlists.forEach { playlist ->
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
                                color = Torve.colors.textTertiary,
                            )
                        }
                        IconButton(
                            onClick = { channelsViewModel.deletePlaylist(playlist.id) },
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
                onClick = { channelsViewModel.showAddPlaylistDialog() },
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

    // Add Playlist Dialog (shared with ChannelsScreen)
    if (channelsState.showAddPlaylist) {
        com.torve.android.ui.channels.AddPlaylistDialog(
            name = channelsState.newPlaylistName,
            url = channelsState.newPlaylistUrl,
            epgUrl = channelsState.newPlaylistEpgUrl,
            playlistType = channelsState.newPlaylistType,
            xtreamServer = channelsState.newXtreamServer,
            xtreamUsername = channelsState.newXtreamUsername,
            xtreamPassword = channelsState.newXtreamPassword,
            isLoading = channelsState.isAddingPlaylist,
            onNameChange = { channelsViewModel.setNewPlaylistName(it) },
            onUrlChange = { channelsViewModel.setNewPlaylistUrl(it) },
            onEpgUrlChange = { channelsViewModel.setNewPlaylistEpgUrl(it) },
            onTypeChange = { channelsViewModel.setNewPlaylistType(it) },
            onXtreamServerChange = { channelsViewModel.setNewXtreamServer(it) },
            onXtreamUsernameChange = { channelsViewModel.setNewXtreamUsername(it) },
            onXtreamPasswordChange = { channelsViewModel.setNewXtreamPassword(it) },
            onConfirm = { channelsViewModel.addPlaylist() },
            onDismiss = { channelsViewModel.dismissAddPlaylistDialog() },
        )
    }
}

/** Device-code auth section with clickable URL, copyable code, and open-in-browser button. */
@Composable
private fun DebridDeviceCodeSection(
    userCode: String,
    verificationUrl: String,
    isPolling: Boolean,
) {
    val context = LocalContext.current
    val codeCopiedMessage = stringResource(R.string.settings_code_copied)

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
            Text(stringResource(R.string.settings_your_code), style = MaterialTheme.typography.bodySmall, color = Silver)
            Text(
                text = userCode,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device Code", userCode))
                Toast.makeText(context, codeCopiedMessage, Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.diagnostics_copy),
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
        Text(stringResource(R.string.settings_open_browser))
    }

    if (isPolling) {
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber)
            Text(stringResource(R.string.settings_waiting_auth), style = MaterialTheme.typography.bodySmall, color = Silver)
        }
    }
}

private enum class SyncCategory(val label: String, val key: String) {
    ADDONS("Addons", "addons"),
    QUALITY("Quality & Streaming Preferences", "preferences"),
    INTEGRATIONS("Integrations (Trakt, OMDB, MDBList, Cloud, AI)", "integrations"),
    PLAYLISTS("Channel Playlists", "playlists"),
    FAVORITES("Channel Favorites", "favorites"),
    PROGRESS("Watch Progress", "progress"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncToTvBottomSheet(
    syncCoordinator: SyncCoordinator,
    syncRepository: SyncRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tvDevices = remember { syncCoordinator.targetDevices().filter { it.deviceType == "tv" } }
    var selectedCategories by remember {
        mutableStateOf(setOf(SyncCategory.ADDONS, SyncCategory.QUALITY, SyncCategory.INTEGRATIONS, SyncCategory.PLAYLISTS))
    }
    var selectedDeviceId by remember { mutableStateOf(tvDevices.firstOrNull()?.id) }
    var isSending by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Charcoal,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Sync Settings to TV",
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Select what to push to your paired TV.",
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
            )
            Spacer(Modifier.height(16.dp))

            SyncCategory.entries.forEach { category ->
                val checked = category in selectedCategories
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedCategories = if (checked) {
                                selectedCategories - category
                            } else {
                                selectedCategories + category
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            selectedCategories = if (isChecked) {
                                selectedCategories + category
                            } else {
                                selectedCategories - category
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Amber,
                            uncheckedColor = Silver,
                            checkmarkColor = Obsidian,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow,
                    )
                }
            }

            if (tvDevices.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Target Device",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                tvDevices.forEach { device ->
                    val isSelected = device.id == selectedDeviceId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDeviceId = device.id }
                            .background(
                                if (isSelected) Amber.copy(alpha = 0.15f) else Gunmetal,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Amber else Snow,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Amber,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            resultMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("Error")) Ruby else Emerald,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val targetId = selectedDeviceId ?: return@Button
                        val categories = selectedCategories.map { it.key }
                        if (categories.isEmpty()) return@Button
                        isSending = true
                        resultMessage = null
                        scope.launch {
                            val result = runCatching {
                                val fullPayload = syncRepository.exportSyncPayload()
                                val filtered = fullPayload.copy(
                                    addons = if ("addons" in categories) fullPayload.addons else emptyList(),
                                    preferences = if ("preferences" in categories) fullPayload.preferences else emptyList(),
                                    channelPlaylists = if ("playlists" in categories) fullPayload.channelPlaylists else emptyList(),
                                    channelFavorites = if ("favorites" in categories) fullPayload.channelFavorites else emptyList(),
                                    watchProgress = if ("progress" in categories) fullPayload.watchProgress else emptyList(),
                                    integrationSecrets = if ("integrations" in categories) fullPayload.integrationSecrets else emptyList(),
                                )
                                val syncJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                                val payloadJson = syncJson.encodeToString(
                                    com.torve.domain.sync.SyncPayload.serializer(),
                                    filtered,
                                )
                                syncCoordinator.sendSettingsPush(
                                    targetDeviceId = targetId,
                                    categories = categories,
                                    payloadJson = payloadJson,
                                ).getOrThrow()
                            }
                            isSending = false
                            resultMessage = if (result.isSuccess) {
                                "Synced successfully!"
                            } else {
                                "Error: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = selectedCategories.isNotEmpty() && selectedDeviceId != null && !isSending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = Obsidian,
                    ),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Obsidian,
                        )
                    } else {
                        Text("Sync")
                    }
                }
            }
        }
    }
}
