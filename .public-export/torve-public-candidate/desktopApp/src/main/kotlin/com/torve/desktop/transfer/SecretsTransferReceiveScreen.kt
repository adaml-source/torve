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
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.transfer.SecretCategory
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
        title = ds("Receive credentials"),
        supportingText = ds("Preparing a one-time handshake..."),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TorveGhostButton(text = ds("Close"), onClick = onClose)
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
        title = ds(com.torve.presentation.transfer.TransferCopy.RECEIVE_HEADER),
        supportingText = ds(com.torve.presentation.transfer.TransferCopy.RECEIVE_PRIMARY_EXPLAINER_DESKTOP),
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
                                contentDescription = ds("Credential transfer QR code"),
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.None,
                            )
                        } else {
                            Text(
                                ds("QR rendering unavailable."),
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
                        text = ds("%1\$s (paste on the sending device):").format(
                            ds(com.torve.presentation.transfer.TransferCopy.RECEIVE_SHORT_CODE_LABEL),
                        ),
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
                title = ds("End-to-end encrypted"),
                description = ds(
                    "The QR holds this device's one-time public key - it's safe to share with the other Torve device. The private half of this handshake never leaves this device, and the Torve backend never sees credentials in the clear.",
                ),
                tone = TorveBannerTone.Info,
            )

            RelayStatusBanner(state.relayStatus)

            // When the relay registered the session, auto-import is on
            // and the paste field is no longer the primary action - hide
            // it behind an "Advanced" toggle so the surface stays focused
            // on the QR. Always reachable regardless of relay state.
            val relayRegistered = state.relayStatus is RelayStatus.Registered
            var advancedOpen by remember(relayRegistered) { mutableStateOf(!relayRegistered) }

            if (relayRegistered) {
                TorveGhostButton(
                    text = if (advancedOpen) ds("Hide manual paste") else ds("Advanced: paste sealed code manually"),
                    onClick = { advancedOpen = !advancedOpen },
                )
            }

            if (advancedOpen) {
                TorveTextField(
                    value = state.envelopeText,
                    onValueChange = onEnvelopeChanged,
                    label = ds("Sealed credential code from sender"),
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
                        text = if (state.importing) ds("Importing...") else ds("Import sealed code"),
                        enabled = !state.importing,
                        onClick = onImport,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TorveGhostButton(text = ds("Cancel and close"), onClick = onCancel)
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
                text = ds("Expires in %1\$d:%2\$02d").format(mm, ss),
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
        title = ds("Credentials imported"),
        supportingText = ds("Imported %1\$d credential record(s).").format(result.applyResult.applied),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TorveBanner(
                title = ds("Import complete"),
                description = importDescription(result),
                tone = TorveBannerTone.Success,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TorveGhostButton(text = ds("Close"), onClick = onClose)
                Spacer(Modifier.width(8.dp))
                TorvePrimaryButton(text = ds("New handshake"), onClick = onNewHandshake)
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
        title = ds("Receive credentials"),
        supportingText = ds("This handshake expired. Generate a new one or close this surface."),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Spacer(Modifier.width(0.dp))
            TorveGhostButton(text = ds("Close"), onClick = onClose)
            Spacer(Modifier.width(8.dp))
            TorvePrimaryButton(text = ds("New handshake"), onClick = onRestart)
        }
    }
}

@Composable
private fun importTitle(result: TransferImportResult): String = when (result) {
    is TransferImportResult.Success -> ds("Credentials imported")
    is TransferImportResult.MalformedEnvelope -> ds("Invalid sealed code")
    is TransferImportResult.DecryptFailure -> when (result.result) {
        TransferDecryptResult.Expired -> ds("Sealed code expired")
        TransferDecryptResult.AuthenticationFailure -> ds("Could not decrypt code")
        is TransferDecryptResult.UnsupportedVersion -> ds("Unsupported transfer version")
        TransferDecryptResult.Replayed -> ds("Code already used")
        TransferDecryptResult.EnvelopePayloadMismatch -> ds("Code failed integrity check")
        is TransferDecryptResult.Malformed -> ds("Malformed sealed code")
        is TransferDecryptResult.Success -> ds("Credentials imported")
    }
    is TransferImportResult.ApplyFailure -> when (result.result) {
        TransferApplyResult.DuplicateNonce -> ds("Code already used")
        is TransferApplyResult.NothingApplied -> ds("Nothing to import")
        is TransferApplyResult.StoreFailure -> ds("Import failed")
        is TransferApplyResult.Success -> ds("Credentials imported")
    }
    TransferImportResult.NoActiveSession -> ds("No active receive session")
    TransferImportResult.MissingPrivateKey -> ds("Receive session is no longer usable")
}

@Composable
private fun importDescription(result: TransferImportResult): String = when (result) {
    is TransferImportResult.Success -> {
        val applied = result.applyResult.applied
        val configCount = result.applyResult.configApplied
        val categoryTitles = transferCategoryTitles()
        val parts = mutableListOf<String>()
        if (configCount > 0) {
            parts += ds("Credentials and setup details imported. Some providers may take a moment to reconnect.")
        } else {
            parts += ds("Credentials imported. Some providers may take a moment to reconnect.")
        }
        parts += if (configCount > 0) {
            ds("Imported %1\$d credential record(s) + %2\$d companion config record(s).")
                .format(applied, configCount)
        } else {
            ds("Imported %1\$d credential record(s).").format(applied)
        }
        if (result.applyResult.skippedKeyNames.isNotEmpty()) {
            parts += ds("Skipped unknown keys: %1\$s.").format(result.applyResult.skippedKeyNames.joinToString())
        }
        if (result.applyResult.skippedConfigKeys.isNotEmpty()) {
            parts += ds("Skipped config keys not on the receiver allowlist: %1\$s.")
                .format(result.applyResult.skippedConfigKeys.joinToString())
        }
        if (result.applyResult.categoriesMissingCompanionConfig.isNotEmpty()) {
            val names = result.applyResult.categoriesMissingCompanionConfig.joinToString { categoryTitles.getValue(it) }
            parts += ds(
                "Imported credentials but missing companion config for: %1\$s. Fill in the matching server URL in Settings to finish setup.",
            ).format(names)
        }
        parts.joinToString(" ")
    }
    is TransferImportResult.MalformedEnvelope -> result.reason
    is TransferImportResult.DecryptFailure -> when (val decrypt = result.result) {
        TransferDecryptResult.Expired -> ds("Ask the sender to generate a fresh sealed code.")
        TransferDecryptResult.AuthenticationFailure -> ds("This code was not sealed for this receive session, or it was changed.")
        is TransferDecryptResult.UnsupportedVersion -> ds("This app cannot read transfer version %1\$d.").format(decrypt.seenVersion)
        TransferDecryptResult.Replayed -> ds("This transfer nonce has already been consumed on this device.")
        TransferDecryptResult.EnvelopePayloadMismatch -> ds("The envelope and payload expiry values do not match.")
        is TransferDecryptResult.Malformed -> decrypt.reason
        is TransferDecryptResult.Success -> ds("Imported %1\$d credential record(s).").format(decrypt.payload.secrets.size)
    }
    is TransferImportResult.ApplyFailure -> when (val apply = result.result) {
        TransferApplyResult.DuplicateNonce -> ds("This transfer nonce has already been consumed on this device.")
        is TransferApplyResult.NothingApplied -> ds("No known credential keys were found in the payload.")
        is TransferApplyResult.StoreFailure -> {
            val rollback = if (apply.rollbackAttempted) {
                if (apply.rollbackSucceeded) {
                    ds("Rollback succeeded; existing credentials were restored.")
                } else {
                    ds("Rollback failed; verify credentials manually.")
                }
            } else {
                null
            }
            listOfNotNull(apply.message, rollback).joinToString(" ")
        }
        is TransferApplyResult.Success -> ds("Imported %1\$d credential record(s).").format(apply.applied)
    }
    TransferImportResult.NoActiveSession -> ds("Generate a receive code first.")
    TransferImportResult.MissingPrivateKey -> ds("Generate a new receive code and try again.")
}

@Composable
private fun RelayStatusBanner(status: RelayStatus) {
    when (status) {
        RelayStatus.NotConfigured -> {
            // Nothing to surface - there's no relay; UI shows only the
            // local-only banner and the paste flow as primary action.
        }
        RelayStatus.Registering -> TorveBanner(
            title = ds("Setting up auto-import..."),
            description = ds("Asking the Torve backend to forward an encrypted bundle to this device."),
            tone = TorveBannerTone.Info,
        )
        is RelayStatus.Registered -> TorveBanner(
            title = ds("Auto-import is on"),
            description = ds("When the sender posts the encrypted bundle, this device imports it automatically. Manual paste stays available under Advanced."),
            tone = TorveBannerTone.Success,
        )
        is RelayStatus.Unavailable -> TorveBanner(
            title = ds("Auto-import unavailable"),
            description = ds("%1\$s Use the paste field below.").format(status.reason),
            tone = TorveBannerTone.Warning,
        )
    }
}

@Composable
private fun transferCategoryTitles(): Map<SecretCategory, String> = mapOf(
    SecretCategory.DEBRID to ds("Debrid"),
    SecretCategory.IPTV to ds("IPTV"),
    SecretCategory.PLEX_JELLYFIN to ds("Plex / Jellyfin"),
    SecretCategory.TRAKT_SIMKL to ds("Trakt / SIMKL"),
    SecretCategory.AI_KEYS to ds("AI and metadata keys"),
    SecretCategory.PANDA to ds("Panda / Usenet"),
)

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
