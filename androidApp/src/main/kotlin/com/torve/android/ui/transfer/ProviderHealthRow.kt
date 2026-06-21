package com.torve.android.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.ProviderRepairAction
import com.torve.presentation.providerhealth.ProviderRepairMapper

/**
 * Re-usable Android provider-health row. Mobile and TV settings both
 * render this Composable; the routing of the four `ProviderRepairAction`
 * variants is hoisted to the caller because the destination differs by
 * surface (mobile NavController vs TV NavHost).
 *
 * The row never shows raw backend bodies — `entry.message` is set by
 * checkers using neutral copy. Action labels come from a tiny inline
 * helper so the closed-enum action set translates 1:1 to UI buttons.
 */
@Composable
fun ProviderHealthRow(
    entry: ProviderHealthEntry,
    onTransferReceive: () -> Unit,
    onOpenSettings: (ProviderHealthEntry) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = remember(entry) { ProviderRepairMapper.actionsFor(entry) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusPill(entry.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.message?.takeIf { it.isNotBlank() }?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { action ->
                        when (action) {
                            ProviderRepairAction.TransferFromAnotherDevice ->
                                OutlinedButton(onClick = onTransferReceive) {
                                    Text("Transfer from another device")
                                }
                            ProviderRepairAction.ReenterCredentials ->
                                TextButton(onClick = { onOpenSettings(entry) }) {
                                    Text("Re-enter")
                                }
                            ProviderRepairAction.OpenDiagnostics ->
                                TextButton(onClick = onOpenDiagnostics) {
                                    Text("Diagnostics")
                                }
                            ProviderRepairAction.OpenProviderSettings ->
                                TextButton(onClick = { onOpenSettings(entry) }) {
                                    Text("Open settings")
                                }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: ProviderHealthStatus) {
    val (label, ok) = when (status) {
        ProviderHealthStatus.GREEN -> "Ready" to true
        ProviderHealthStatus.YELLOW -> "Warn" to false
        ProviderHealthStatus.RED -> "Error" to false
        ProviderHealthStatus.UNCONFIGURED -> "Setup" to false
        ProviderHealthStatus.UNKNOWN -> "…" to true
    }
    val container = if (ok) MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (ok) MaterialTheme.colorScheme.onTertiaryContainer
    else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = container, shape = RoundedCornerShape(999.dp)) {
        Text(
            label,
            color = onContainer,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Maps a [ProviderHealthCategory] to the existing Android mobile
 * settings route most relevant for that category. Used by both the
 * mobile and TV surfaces (with their respective NavController-vs-TV-nav
 * adaptation done at the call site).
 */
fun providerSettingsRouteFor(category: ProviderHealthCategory): String? = when (category) {
    ProviderHealthCategory.DEBRID -> "panda_setup"
    ProviderHealthCategory.PLEX_JELLYFIN -> "integrations"
    ProviderHealthCategory.TRAKT, ProviderHealthCategory.SIMKL -> "integrations"
    ProviderHealthCategory.USENET_INDEXER,
    ProviderHealthCategory.USENET_PROVIDER,
    ProviderHealthCategory.DOWNLOAD_CLIENT -> "manage_panda"
    ProviderHealthCategory.IPTV -> "settings"
    ProviderHealthCategory.ADDON -> "addon_catalog"
    ProviderHealthCategory.EPG -> "settings"
    ProviderHealthCategory.PLAYBACK -> null
}
