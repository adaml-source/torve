package com.torve.android.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import org.koin.compose.koinInject

/**
 * Needs-attention rows for Android mobile Settings.
 *
 * This is intentionally not a second configuration catalog. Settings
 * already has durable places to configure Debrid, IPTV, Plex, Trakt,
 * SIMKL, addons, and Panda. The top health area only surfaces
 * configured functionality that currently needs action.
 */
@Composable
fun ProviderHealthSection(
    onTransferReceive: () -> Unit,
    onOpenSettings: (entry: ProviderHealthEntry) -> Unit,
    onOpenDiagnostics: () -> Unit,
    coordinator: ProviderHealthCoordinator = koinInject(),
    modifier: Modifier = Modifier,
) {
    val entries by coordinator.entries.collectAsState()
    val attentionEntries = entries.filter { entry ->
        entry.status == ProviderHealthStatus.RED ||
            entry.status == ProviderHealthStatus.YELLOW
    }
    if (attentionEntries.isEmpty()) return

    val sorted = attentionEntries.sortedWith(
        compareBy({ it.category.ordinal }, { it.label }),
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Needs attention",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        sorted.forEach { entry ->
            ProviderHealthRow(
                entry = entry,
                onTransferReceive = onTransferReceive,
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
            )
        }
    }
}
