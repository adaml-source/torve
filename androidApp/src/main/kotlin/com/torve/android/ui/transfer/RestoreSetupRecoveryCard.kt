package com.torve.android.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import com.torve.presentation.providerhealth.ProviderHealthRecoverySnapshot
import com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider
import com.torve.presentation.transfer.TransferImportCompletionNotifier
import org.koin.compose.koinInject

/**
 * "Restore setup from another device" non-blocking recovery card.
 *
 * Shown only when the shared [ProviderHealthRecoveryStateProvider]
 * reports that 2+ transferable provider categories have no local
 * credentials. The user can dismiss the card for the current Settings
 * session ("Set up manually") or navigate straight to the receive
 * screen ("Receive credentials").
 *
 * The card is a no-op on devices that already have credentials — the
 * snapshot's `shouldShowRecoveryCard` short-circuits to false and the
 * Composable renders nothing.
 */
@Composable
fun RestoreSetupRecoveryCard(
    onReceive: () -> Unit,
    provider: ProviderHealthRecoveryStateProvider = koinInject(),
    coordinator: ProviderHealthCoordinator = koinInject(),
    completionNotifier: TransferImportCompletionNotifier = koinInject(),
) {
    var snapshot by remember { mutableStateOf<ProviderHealthRecoverySnapshot?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    // Observing the notifier means a successful import recomputes the
    // snapshot in the same recomposition that surfaces the success
    // banner — the card vanishes without the user reopening Settings.
    val lastImport by completionNotifier.lastImportEpochMs.collectAsState()
    val healthEntries by coordinator.entries.collectAsState()
    LaunchedEffect(lastImport, healthEntries) {
        snapshot = provider.snapshot(healthEntries = healthEntries)
    }

    val show = !dismissed && (snapshot?.shouldShowRecoveryCard == true)
    if (!show) return

    val snap = snapshot ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.recovery_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.recovery_card_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.recovery_card_summary_format,
                    snap.missingTransferableCategoryCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { dismissed = true }) {
                    Text(stringResource(R.string.recovery_card_action_manual))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onReceive) {
                    Text(stringResource(R.string.recovery_card_action_receive))
                }
            }
        }
    }
}
