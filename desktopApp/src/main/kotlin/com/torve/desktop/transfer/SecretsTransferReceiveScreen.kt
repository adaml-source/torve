package com.torve.desktop.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.transfer.TransferApplyResult
import com.torve.domain.transfer.TransferDecryptResult
import com.torve.presentation.transfer.ReceiverState
import com.torve.presentation.transfer.RelayStatus
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import com.torve.presentation.transfer.TransferImportResult
import kotlinx.coroutines.launch

/**
 * Receive-only credential transfer surface.
 *
 * Generates an ephemeral receiver key pair, displays the resulting
 * handshake as a QR plus plaintext code, and counts down to expiry.
 * There is no sender flow, relay polling, or payload application path
 * in this screen.
 */
@Composable
fun SecretsTransferReceiveScreen(
    viewModel: SecretsTransferReceiverViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancel() }
    }

    when (val s = state) {
        ReceiverState.Idle -> IdlePlaceholder(onClose = onClose)
        is ReceiverState.Active -> ActiveReceiver(
            state = s,
            onEnvelopeChanged = viewModel::updateEnvelopeText,
            onImport = { scope.launch { viewModel.acceptEnvelopeJson() } },
            onCancel = {
                viewModel.cancel()
                onClose()
            },
        )
        is ReceiverState.Imported -> ImportedReceiver(
            result = s.result,
            onNewHandshake = { scope.launch { viewModel.restart() } },
            onClose = onClose,
        )
        ReceiverState.Expired -> ExpiredReceiver(
            onRestart = { scope.launch { viewModel.restart() } },
            onClose = onClose,
        )
    }
}

@Composable
private fun IdlePlaceholder(onClose: () -> Unit) {
    TorveSectionCard(
        title = "Receive credentials",
        supportingText = "Preparing a one-time handshake...",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TorveGhostButton(text = "Close", onClick = onClose)
        }
    }
}

@Composable
private fun ActiveReceiver(
    state: ReceiverState.Active,
    onEnvelopeChanged: (String) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val qrBitmap = remember(state.sessionString) {
        runCatching { TransferQrBitmap.render(state.sessionString) }.getOrNull()
    }

    TorveSectionCard(
        title = "Receive credentials",
        supportingText = "Show this one-time code to the sending device. It can scan the QR " +
            "or paste the session string below.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .size(280.dp)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "Credential transfer QR code",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.None,
                            )
                        } else {
                            Text(
                                "QR rendering unavailable.",
                                color = Color.Black,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CountdownRow(remainingSeconds = state.remainingSeconds)
                    Text(
                        text = "Or paste this code on the sending device:",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    SelectionContainer {
                        Surface(
                            color = colors.cardSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.borderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp),
                        ) {
                            Text(
                                text = state.sessionString,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = colors.textPrimary,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            TorveBanner(
                title = "End-to-end encrypted",
                description = "The QR holds this device's one-time public key — it's safe to share " +
                    "with the other Torve device. The private half of this handshake never leaves " +
                    "this device, and the Torve backend never sees credentials in the clear.",
                tone = TorveBannerTone.Info,
            )

            RelayStatusBanner(state.relayStatus)

            // When the relay registered the session, auto-import is on
            // and the paste field is no longer the primary action — hide
            // it behind an "Advanced" toggle so the surface stays focused
            // on the QR. Always reachable regardless of relay state.
            val relayRegistered = state.relayStatus is RelayStatus.Registered
            var advancedOpen by remember(relayRegistered) { mutableStateOf(!relayRegistered) }

            if (relayRegistered) {
                TorveGhostButton(
                    text = if (advancedOpen) "Hide manual paste" else "Advanced: paste sealed code manually",
                    onClick = { advancedOpen = !advancedOpen },
                )
            }

            if (advancedOpen) {
                TorveTextField(
                    value = state.envelopeText,
                    onValueChange = onEnvelopeChanged,
                    label = "Sealed credential code from sender",
                    singleLine = false,
                    placeholder = "{\"version\":1,...}",
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                )
            }

            state.importResult?.let { result ->
                TorveBanner(
                    title = importTitle(result),
                    description = importDescription(result),
                    tone = importTone(result),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (advancedOpen) {
                    TorvePrimaryButton(
                        text = if (state.importing) "Importing..." else "Import sealed code",
                        enabled = !state.importing,
                        onClick = onImport,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TorveGhostButton(text = "Cancel and close", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun CountdownRow(remainingSeconds: Long) {
    val colors = TorveDesktopThemeTokens.colors
    val mm = (remainingSeconds / 60L).coerceAtLeast(0L)
    val ss = (remainingSeconds % 60L).coerceAtLeast(0L)
    val tone = when {
        remainingSeconds <= 30L -> colors.error
        remainingSeconds <= 120L -> colors.warning
        else -> colors.accent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .background(tone.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "Expires in %d:%02d".format(mm, ss),
                style = MaterialTheme.typography.labelMedium,
                color = tone,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ImportedReceiver(
    result: TransferImportResult.Success,
    onNewHandshake: () -> Unit,
    onClose: () -> Unit,
) {
    TorveSectionCard(
        title = "Credentials imported",
        supportingText = "Imported ${result.applyResult.applied} credential record(s).",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TorveBanner(
                title = "Import complete",
                description = importDescription(result),
                tone = TorveBannerTone.Success,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TorveGhostButton(text = "Close", onClick = onClose)
                Spacer(Modifier.width(8.dp))
                TorvePrimaryButton(text = "New handshake", onClick = onNewHandshake)
            }
        }
    }
}

@Composable
private fun ExpiredReceiver(
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    TorveSectionCard(
        title = "Receive credentials",
        supportingText = "This handshake expired. Generate a new one or close this surface.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Spacer(Modifier.width(0.dp))
            TorveGhostButton(text = "Close", onClick = onClose)
            Spacer(Modifier.width(8.dp))
            TorvePrimaryButton(text = "New handshake", onClick = onRestart)
        }
    }
}

private fun importTitle(result: TransferImportResult): String = when (result) {
    is TransferImportResult.Success -> "Credentials imported"
    is TransferImportResult.MalformedEnvelope -> "Invalid sealed code"
    is TransferImportResult.DecryptFailure -> when (result.result) {
        TransferDecryptResult.Expired -> "Sealed code expired"
        TransferDecryptResult.AuthenticationFailure -> "Could not decrypt code"
        is TransferDecryptResult.UnsupportedVersion -> "Unsupported transfer version"
        TransferDecryptResult.Replayed -> "Code already used"
        TransferDecryptResult.EnvelopePayloadMismatch -> "Code failed integrity check"
        is TransferDecryptResult.Malformed -> "Malformed sealed code"
        is TransferDecryptResult.Success -> "Credentials imported"
    }
    is TransferImportResult.ApplyFailure -> when (result.result) {
        TransferApplyResult.DuplicateNonce -> "Code already used"
        is TransferApplyResult.NothingApplied -> "Nothing to import"
        is TransferApplyResult.StoreFailure -> "Import failed"
        is TransferApplyResult.Success -> "Credentials imported"
    }
    TransferImportResult.NoActiveSession -> "No active receive session"
    TransferImportResult.MissingPrivateKey -> "Receive session is no longer usable"
}

private fun importDescription(result: TransferImportResult): String = when (result) {
    is TransferImportResult.Success -> buildString {
        val applied = result.applyResult.applied
        val configCount = result.applyResult.configApplied
        if (configCount > 0) {
            append("Credentials and setup details imported. Some providers may take a moment to reconnect. ")
        } else {
            append("Credentials imported. Some providers may take a moment to reconnect. ")
        }
        append("Imported ")
        append(applied)
        append(" credential record")
        if (applied != 1) append("s")
        if (configCount > 0) {
            append(" + ")
            append(configCount)
            append(" companion config record")
            if (configCount != 1) append("s")
        }
        append(".")
        if (result.applyResult.skippedKeyNames.isNotEmpty()) {
            append(" Skipped unknown keys: ")
            append(result.applyResult.skippedKeyNames.joinToString())
            append(".")
        }
        if (result.applyResult.skippedConfigKeys.isNotEmpty()) {
            append(" Skipped config keys not on the receiver allowlist: ")
            append(result.applyResult.skippedConfigKeys.joinToString())
            append(".")
        }
        if (result.applyResult.categoriesMissingCompanionConfig.isNotEmpty()) {
            append(" Imported credentials but missing companion config for: ")
            append(result.applyResult.categoriesMissingCompanionConfig.joinToString { it.name })
            append(". Fill in the matching server URL in Settings to finish setup.")
        }
    }
    is TransferImportResult.MalformedEnvelope -> result.reason
    is TransferImportResult.DecryptFailure -> when (val decrypt = result.result) {
        TransferDecryptResult.Expired -> "Ask the sender to generate a fresh sealed code."
        TransferDecryptResult.AuthenticationFailure -> "This code was not sealed for this receive session, or it was changed."
        is TransferDecryptResult.UnsupportedVersion -> "This app cannot read transfer version ${decrypt.seenVersion}."
        TransferDecryptResult.Replayed -> "This transfer nonce has already been consumed on this device."
        TransferDecryptResult.EnvelopePayloadMismatch -> "The envelope and payload expiry values do not match."
        is TransferDecryptResult.Malformed -> decrypt.reason
        is TransferDecryptResult.Success -> "Imported ${decrypt.payload.secrets.size} credential record(s)."
    }
    is TransferImportResult.ApplyFailure -> when (val apply = result.result) {
        TransferApplyResult.DuplicateNonce -> "This transfer nonce has already been consumed on this device."
        is TransferApplyResult.NothingApplied -> "No known credential keys were found in the payload."
        is TransferApplyResult.StoreFailure -> buildString {
            append(apply.message)
            if (apply.rollbackAttempted) {
                append(
                    if (apply.rollbackSucceeded) {
                        " Rollback succeeded; existing credentials were restored."
                    } else {
                        " Rollback failed; verify credentials manually."
                    },
                )
            }
        }
        is TransferApplyResult.Success -> "Imported ${apply.applied} credential record(s)."
    }
    TransferImportResult.NoActiveSession -> "Generate a receive code first."
    TransferImportResult.MissingPrivateKey -> "Generate a new receive code and try again."
}

@Composable
private fun RelayStatusBanner(status: RelayStatus) {
    when (status) {
        RelayStatus.NotConfigured -> {
            // Nothing to surface — there's no relay; UI shows only the
            // local-only banner and the paste flow as primary action.
        }
        RelayStatus.Registering -> TorveBanner(
            title = "Setting up auto-import…",
            description = "Asking the Torve backend to forward a sealed envelope to this device.",
            tone = TorveBannerTone.Info,
        )
        is RelayStatus.Registered -> TorveBanner(
            title = "Auto-import is on",
            description = "When the sender posts the sealed envelope, this device imports it " +
                "automatically. Manual paste stays available under Advanced.",
            tone = TorveBannerTone.Success,
        )
        is RelayStatus.Unavailable -> TorveBanner(
            title = "Auto-import unavailable",
            description = status.reason + " Use the paste field below.",
            tone = TorveBannerTone.Warning,
        )
    }
}

private fun importTone(result: TransferImportResult): TorveBannerTone = when (result) {
    is TransferImportResult.Success ->
        if (result.applyResult.categoriesMissingCompanionConfig.isNotEmpty())
            TorveBannerTone.Warning
        else TorveBannerTone.Success
    is TransferImportResult.ApplyFailure -> when (val apply = result.result) {
        is TransferApplyResult.NothingApplied -> TorveBannerTone.Warning
        is TransferApplyResult.StoreFailure -> if (apply.rollbackSucceeded) {
            TorveBannerTone.Warning
        } else {
            TorveBannerTone.Error
        }
        else -> TorveBannerTone.Error
    }
    else -> TorveBannerTone.Error
}
