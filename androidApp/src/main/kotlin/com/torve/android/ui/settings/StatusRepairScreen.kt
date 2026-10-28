package com.torve.android.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.transfer.providerSettingsRouteFor
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import org.koin.compose.koinInject

@Composable
fun StatusRepairScreen(
    onBack: () -> Unit,
    onNavigate: (route: String) -> Unit,
    onDiagnose: () -> Unit,
    coordinator: ProviderHealthCoordinator = koinInject(),
) {
    com.torve.android.ui.product.MobileConnectionCenterScreen(onBack = onBack,
        onProfiles = { onNavigate("profiles") }, onAccount = { onNavigate("sync_account") },
        onRepair = { service -> onNavigate(when (service) {
            "addons" -> "addon_catalog"
            "streaming_setup" -> "source_setup"
            else -> "integrations?service=${android.net.Uri.encode(service)}"
        }) }, onProviderNavigate = onNavigate, onDiagnostics = onDiagnose, health = coordinator)
}

@Composable
internal fun StatusRepairNavigationHeader(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.torve.android.ui.components.BackButton(onClick = onBack)
        Text(
            text = stringResource(R.string.repair_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.repair_open_settings), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
