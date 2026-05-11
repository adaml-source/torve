package com.torve.desktop.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.presentation.transfer.AttemptOutcome
import com.torve.presentation.transfer.AttemptRole
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferAttemptRecord
import com.torve.presentation.transfer.TransferDiagnosticsCollector
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import kotlinx.coroutines.launch

/**
 * Read-only credential-transfer diagnostics card for the desktop
 * settings page.
 *
 * Pulls the shared [TransferDiagnosticsCollector] from Koin and renders
 * the same closed-shape snapshot the Android and iOS surfaces show.
 * Backend bodies, raw error strings, session strings, envelopes, and
 * access tokens are structurally unable to reach this view.
 *
 * The "Probe relay now" button fires a single non-destructive
 * `getSession` against a deliberately bogus session id - it confirms
 * the route family is mounted without consuming the user's quota.
 */
@Composable
fun TransferDiagnosticsCard(
    collector: TransferDiagnosticsCollector = remember {
        org.koin.java.KoinJavaComponent.get(TransferDiagnosticsCollector::class.java)
    },
) {
    var snapshot by remember { mutableStateOf<TransferDiagnosticsSnapshot?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(collector) {
        snapshot = collector.collect(probeRelay = false)
    }

    TorveSectionCard(
        title = ds("Transfer diagnostics"),
        supportingText = ds(
            "Read-only health check for credential transfer. Crypto engine, sign-in, relay reachability, and the latest attempt this device made.",
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TorveBanner(
                title = ds("No secrets in this view"),
                description = ds(
                    "Diagnostics never include credentials, envelope JSON, QR payloads, access tokens, or private keys. Every value below is a closed enum or a bucketed count.",
                ),
                tone = TorveBannerTone.Info,
            )

            val current = snapshot
            if (current == null) {
                Text(ds("Loading..."), color = TorveDesktopThemeTokens.colors.textSecondary)
            } else {
                StatusRow(
                    ds("Crypto engine"),
                    ds(if (current.cryptoEngineAvailable) "available" else "unavailable"),
                    current.cryptoEngineAvailable,
                )
                StatusRow(ds("Signed in"), ds(if (current.signedIn) "yes" else "no"), current.signedIn)
                StatusRow(
                    ds("Backend relay"),
                    ds(relayLabel(current.relayReachable)),
                    current.relayReachable == RelayReachability.REACHABLE,
                )
                LastAttemptBlock(current.lastAttempt)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorveGhostButton(
                    text = ds("Refresh"),
                    onClick = {
                        scope.launch {
                            snapshot = collector.collect(probeRelay = false)
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                TorvePrimaryButton(
                    text = if (probing) ds("Probing relay...") else ds("Probe relay now"),
                    enabled = !probing,
                    onClick = {
                        scope.launch {
                            probing = true
                            try {
                                snapshot = collector.collect(probeRelay = true)
                            } finally {
                                probing = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    val colors = TorveDesktopThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusPill(value = value, ok = ok)
    }
}

@Composable
private fun StatusPill(value: String, ok: Boolean) {
    val colors = TorveDesktopThemeTokens.colors
    val tone = if (ok) colors.accent else colors.warning
    Surface(
        color = tone.copy(alpha = 0.18f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
    ) {
        Text(
            text = value,
            color = tone,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LastAttemptBlock(record: TransferAttemptRecord?) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        color = colors.cardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = ds("Last transfer attempt"),
                color = colors.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (record == null) {
                Text(
                    text = ds("No attempt recorded yet on this device."),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    ds("Role: %1\$s").format(ds(roleLabel(record.role))),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    ds("Outcome: %1\$s").format(ds(outcomeLabel(record.outcome))),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                record.errorCategory?.let {
                    Text(
                        ds("Reason: %1\$s").format(it.value),
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    ds("Timestamp: epoch_ms=%1\$d").format(record.recordedAtEpochMs),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun relayLabel(r: RelayReachability): String = when (r) {
    RelayReachability.UNKNOWN -> "unknown"
    RelayReachability.REACHABLE -> "reachable"
    RelayReachability.UNAVAILABLE -> "unavailable"
    RelayReachability.UNAUTHORIZED -> "unauthorized"
    RelayReachability.NETWORK_ERROR -> "network error"
    RelayReachability.NOT_SIGNED_IN -> "not signed in"
    RelayReachability.NO_CRYPTO_ENGINE -> "no crypto engine"
}

internal fun roleLabel(r: AttemptRole): String = when (r) {
    AttemptRole.SENDER -> "sender"
    AttemptRole.RECEIVER -> "receiver"
}

internal fun outcomeLabel(o: AttemptOutcome): String = when (o) {
    AttemptOutcome.REGISTERED -> "registered"
    AttemptOutcome.DELIVERED -> "delivered"
    AttemptOutcome.IMPORTED -> "imported"
    AttemptOutcome.FAILED -> "failed"
    AttemptOutcome.RELAY_UNAVAILABLE -> "relay unavailable"
}
