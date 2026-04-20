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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.addon.AddonViewModel
import org.koin.compose.koinInject

@Composable
fun ManagePandaScreen(
    onBack: () -> Unit,
    onSetupClick: () -> Unit = {},
    viewModel: AddonViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
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
        }
    }
    val isInstalled = pandaAddon != null
    val isEnabled = pandaAddon?.isEnabled ?: false
    val isInstalling = state.isInstalling && state.installingUrl == pandaManifestUrl

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

            // Logo
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

            // Name
            Text(
                stringResource(R.string.addon_popular_panda_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )

            // Version
            if (isInstalled && pandaAddon?.manifest?.version != null) {
                Text(
                    stringResource(R.string.manage_panda_version, pandaAddon.manifest.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Description
            Text(
                pandaAddon?.manifest?.description
                    ?: stringResource(R.string.addon_popular_panda_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Steel.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))

            if (isInstalled) {
                // Enabled toggle
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
                            pandaAddon?.let { viewModel.toggleAddon(it.manifestUrl, enabled) }
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
                // Not installed state
                Text(
                    stringResource(R.string.manage_panda_not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Silver,
                )
                Spacer(Modifier.height(16.dp))
            }

            // Actions
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Native setup — primary action
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

                if (!isInstalled) {
                    // Install Panda addon from manifest
                    OutlinedButton(
                        onClick = {
                            viewModel.setInstallUrl(pandaManifestUrl)
                            viewModel.installAddon()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isInstalling,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Amber,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.manage_panda_installing), color = Amber)
                        } else {
                            Text(stringResource(R.string.manage_panda_install), color = Amber)
                        }
                    }
                }

                // Web fallback
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

                if (isInstalled) {
                    // Remove Panda
                    OutlinedButton(
                        onClick = {
                            pandaAddon?.let { viewModel.removeAddon(it.manifestUrl) }
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

            // Error display
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
}
