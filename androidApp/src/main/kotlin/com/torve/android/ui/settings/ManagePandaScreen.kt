package com.torve.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.panda.PandaManagementTokenCard
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

private const val HELP_URL_MANAGEMENT_TOKEN =
    "https://torve.app/help.html#article:panda-management-token"

@Composable
fun ManagePandaScreen(
    onBack: () -> Unit,
    onSetupClick: () -> Unit = {},
    viewModel: AddonViewModel = koinInject(),
    pandaViewModel: PandaSetupViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val pandaState by pandaViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val context = LocalContext.current

    val pandaManifestUrl = remember {
        AddonViewModel.normalizeManifestUrl(
            "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/manifest.json"
        )
    }
    val pandaConfigUrl = remember {
        "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/configure"
    }
    val pandaLogoUrl = remember {
        "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/logo.png"
    }

    val pandaAddon = remember(state.addons) {
        state.addons.find {
            AddonViewModel.normalizeManifestUrl(it.manifestUrl) == pandaManifestUrl
        } ?: state.addons.find {
            it.manifestUrl.contains("panda.torve.app") ||
                it.manifest.id == "com.torve.panda"
        }
    }
    val isInstalled = pandaAddon != null
    val isEnabled = pandaAddon?.isEnabled ?: false

    var showRecoveryDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.manage_panda_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Amber.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = pandaLogoUrl,
                    contentDescription = "Panda",
                    modifier = Modifier.size(48.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.addon_popular_panda_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )

            pandaAddon?.manifest?.version?.let { version ->
                Text(
                    stringResource(R.string.manage_panda_version, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                pandaAddon?.manifest?.description
                    ?: stringResource(R.string.addon_popular_panda_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Steel.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))

            if (pandaAddon != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Gunmetal)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.manage_panda_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Snow,
                    )
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.toggleAddon(pandaAddon.manifestUrl, enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                        ),
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

            if (!isInstalled) {
                Text(
                    stringResource(R.string.manage_panda_not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Silver,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (isInstalled && (!settingsState.debridConnected || settingsState.connectedDebridProviders.isEmpty())) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Ruby.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "Real-Debrid not connected",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Ruby,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your debrid token may have expired. Go to Settings â†’ Integrations â†’ Debrid to reconnect, then tap Reconfigure below to sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Silver,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // One-time display following a successful rotate-management. Shown
            // exactly once per rotate; "I've saved it" clears it.
            if (!pandaState.pendingManagementTokenDisplay.isNullOrBlank()) {
                PandaManagementTokenCard(
                    token = pandaState.pendingManagementTokenDisplay,
                    notice = pandaState.managementTokenNotice,
                    onAcknowledge = { pandaViewModel.acknowledgeManagementTokenDisplay() },
                )
                Spacer(Modifier.height(16.dp))
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onSetupClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        stringResource(
                            if (isInstalled) R.string.panda_setup_reconfigure
                            else R.string.manage_panda_open_config,
                        ),
                        color = Gunmetal,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pandaConfigUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        stringResource(R.string.panda_setup_web_fallback),
                        color = Silver,
                    )
                }

                if (pandaAddon != null) {
                    // Management-token section. Gated on the addon being installed
                    // so we never surface tooling for a config the user can't
                    // actually address.
                    HorizontalDivider(color = Steel.copy(alpha = 0.15f))
                    Text(
                        stringResource(R.string.manage_panda_management_token_heading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Snow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Text(
                        if (pandaState.hasManagementToken) {
                            stringResource(R.string.manage_panda_token_required_desc)
                        } else {
                            stringResource(R.string.manage_panda_token_missing_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                    )

                    if (!pandaState.hasManagementToken) {
                        OutlinedButton(
                            onClick = { showRecoveryDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.manage_panda_need_token), color = Silver)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { pandaViewModel.rotateManagementToken() },
                            enabled = !pandaState.rotateInProgress,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.manage_panda_rotate_token), color = Silver)
                        }
                        OutlinedButton(
                            onClick = { pandaViewModel.rotateManifestUrl() },
                            enabled = !pandaState.rotateInProgress,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.manage_panda_reset_manifest_url), color = Silver)
                        }
                    }

                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HELP_URL_MANAGEMENT_TOKEN))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_learn_more), color = Amber)
                    }

                    pandaState.rotateError?.let { err ->
                        Text(err, color = Ruby, style = MaterialTheme.typography.bodySmall)
                    }

                    HorizontalDivider(color = Steel.copy(alpha = 0.15f))

                    OutlinedButton(
                        onClick = {
                            // Server-side delete runs with the management token
                            // (handled by PandaSetupViewModel.deleteConfig); the
                            // local addon row is dropped independently so both
                            // paths converge on a clean slate.
                            pandaViewModel.deleteConfig()
                            viewModel.removeAddon(pandaAddon.manifestUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.manage_panda_remove),
                            color = Ruby,
                        )
                    }
                }
            }

            state.installError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ruby,
                )
            }
            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ruby,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showRecoveryDialog) {
        // Auto-dismiss on success: the VM flips hasManagementToken when the
        // server accepts the admin-issued token.
        LaunchedEffect(pandaState.hasManagementToken) {
            if (pandaState.hasManagementToken) showRecoveryDialog = false
        }
        RecoveryDialog(
            inProgress = pandaState.recoveryInProgress,
            error = pandaState.recoveryError,
            onDismiss = {
                showRecoveryDialog = false
                pandaViewModel.clearError()
            },
            onSubmit = { adminToken ->
                pandaViewModel.recoverManagementToken(adminToken)
            },
        )
    }
}

@Composable
private fun RecoveryDialog(
    inProgress: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_panda_token_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.manage_panda_token_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.manage_panda_token_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Ruby, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inProgress && input.isNotBlank(),
                onClick = { onSubmit(input) },
            ) {
                Text(
                    if (inProgress) stringResource(R.string.manage_panda_checking) else stringResource(R.string.manage_panda_validate_save),
                    color = Amber,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Silver) }
        },
    )
}
