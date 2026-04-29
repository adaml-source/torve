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
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import org.koin.compose.koinInject

/**
 * Provider-health rows section for Android mobile Settings.
 *
 * Renders nothing when [ProviderHealthCoordinator.entries] is empty —
 * Android currently registers no checkers (desktop has its own
 * `DesktopProviderHealthInit`; an `AndroidProviderHealthInit` is the
 * one piece of follow-up work for parity). Until that exists, the
 * recovery card and Transfer entry points carry the UX. As soon as
 * any checker is registered (e.g. when an Android init equivalent
 * lands), this section starts rendering rows automatically.
 *
 * No fake green/red is displayed when the coordinator has no data.
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
    if (entries.isEmpty()) return

    val sorted = entries.sortedWith(
        compareBy({ it.category.ordinal }, { it.label }),
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Provider health",
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
