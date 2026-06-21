package com.torve.android.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.presentation.transfer.AttemptOutcome
import com.torve.presentation.transfer.AttemptRole
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferAttemptRecord
import com.torve.presentation.transfer.TransferDiagnosticsCollector
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Read-only credential-transfer diagnostics surface.
 *
 * Renders only closed-enum / boolean / bucketed values from the shared
 * [TransferDiagnosticsCollector]. The screen never reads or surfaces
 * envelope JSON, session strings, public/private keys, secrets, or
 * access tokens — those types literally do not flow into the snapshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferDiagnosticsScreen(
    onBack: () -> Unit,
    collector: TransferDiagnosticsCollector = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<TransferDiagnosticsSnapshot?>(null) }
    var probing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshot = collector.collect(probeRelay = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            TransferStatusBanner(
                title = "Read-only diagnostics",
                body = stringResource(R.string.diag_transfer_redaction_note),
                tone = TransferBannerTone.Info,
            )

            val s = snapshot
            if (s == null) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                StatusCard(s)
                LastAttemptCard(s.lastAttempt)
            }

            OutlinedButton(
                onClick = {
                    if (!probing) {
                        probing = true
                        scope.launch {
                            snapshot = collector.collect(probeRelay = true)
                            probing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !probing,
            ) {
                Text(
                    if (probing) {
                        stringResource(R.string.diag_transfer_probing)
                    } else {
                        stringResource(R.string.diag_transfer_probe)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(s: TransferDiagnosticsSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diag_transfer_crypto), modifier = Modifier.weight(1f))
                StatusPill(if (s.cryptoEngineAvailable) "Ready" else "Missing", s.cryptoEngineAvailable)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diag_transfer_signed_in), modifier = Modifier.weight(1f))
                StatusPill(if (s.signedIn) "Signed in" else "Signed out", s.signedIn)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diag_transfer_relay), modifier = Modifier.weight(1f))
                StatusPill(
                    relayLabel(s.relayReachable),
                    ok = s.relayReachable == RelayReachability.REACHABLE,
                    neutral = s.relayReachable == RelayReachability.UNKNOWN,
                )
            }
            Text(
                text = relayHelp(s.relayReachable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastAttemptCard(record: TransferAttemptRecord?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.diag_transfer_last_attempt),
                fontWeight = FontWeight.SemiBold,
            )
            if (record == null) {
                Text(
                    text = stringResource(R.string.diag_transfer_no_attempt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Direction: ${roleLabel(record.role)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Status: ${outcomeLabel(record.outcome)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                record.errorCategory?.let {
                    Text(
                        text = "Reason: ${it.value}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "Timestamp: epoch_ms=${record.recordedAtEpochMs}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean, neutral: Boolean = false) {
    val container = when {
        neutral -> MaterialTheme.colorScheme.surface
        ok -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when {
        neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        ok -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    androidx.compose.material3.Surface(
        color = container,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            label,
            color = onContainer,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// Pill labels stay short; the full user-language explanation lives in
// `relayHelp` below. The wording centres on the three concepts the user
// hears in the Restore / Transfer flows: "automatic transfer" (the
// credential relay), "credential transfer" (the act itself), and
// "manual fallback" (paste-between-devices when the relay can't help).
private fun relayLabel(r: RelayReachability): String = when (r) {
    RelayReachability.UNKNOWN -> "Checking"
    RelayReachability.REACHABLE -> "Automatic transfer ready"
    RelayReachability.UNAVAILABLE -> "Manual fallback only"
    RelayReachability.UNAUTHORIZED -> "Sign in needed"
    RelayReachability.NETWORK_ERROR -> "Network error"
    RelayReachability.NOT_SIGNED_IN -> "Sign in needed"
    RelayReachability.NO_CRYPTO_ENGINE -> "Encryption engine missing"
}

@Composable
private fun relayHelp(r: RelayReachability): String = when (r) {
    RelayReachability.UNKNOWN -> stringResource(R.string.diag_transfer_relay_unknown)
    RelayReachability.REACHABLE -> stringResource(R.string.diag_transfer_relay_reachable)
    RelayReachability.UNAVAILABLE -> stringResource(R.string.diag_transfer_relay_unavailable)
    RelayReachability.UNAUTHORIZED -> stringResource(R.string.diag_transfer_relay_unauthorized)
    RelayReachability.NETWORK_ERROR -> stringResource(R.string.diag_transfer_relay_network)
    RelayReachability.NOT_SIGNED_IN -> stringResource(R.string.diag_transfer_relay_not_signed_in)
    RelayReachability.NO_CRYPTO_ENGINE -> stringResource(R.string.diag_transfer_relay_no_crypto)
}

private fun roleLabel(role: AttemptRole): String = when (role) {
    AttemptRole.SENDER -> "Sending credentials"
    AttemptRole.RECEIVER -> "Receiving credentials"
}

private fun outcomeLabel(outcome: AttemptOutcome): String = when (outcome) {
    AttemptOutcome.REGISTERED -> "Ready"
    AttemptOutcome.DELIVERED -> "Delivered"
    AttemptOutcome.IMPORTED -> "Imported"
    AttemptOutcome.FAILED -> "Failed — try manual fallback"
    AttemptOutcome.RELAY_UNAVAILABLE -> "Automatic transfer unavailable — use manual fallback"
}
