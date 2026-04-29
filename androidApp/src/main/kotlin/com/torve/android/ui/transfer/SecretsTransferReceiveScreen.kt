package com.torve.android.ui.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.domain.transfer.TransferApplyResult
import com.torve.domain.transfer.TransferDecryptResult
import com.torve.presentation.transfer.ReceiverState
import com.torve.presentation.transfer.RelayStatus
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import com.torve.presentation.transfer.TransferImportResult
import kotlinx.coroutines.launch

/**
 * Mobile + TV credential-transfer receive surface. Shows a QR + plain
 * session string for the sender to scan/paste, a 1 Hz countdown to
 * expiry, a relay-status banner that explains whether auto-import is
 * on, and a manual sealed-code paste field as a permanent fallback.
 *
 * `largeQr = true` (TV) renders the QR as a fixed large block so it
 * stays scannable from couch distance and the paste field tucks under
 * an "Advanced" toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsTransferReceiveScreen(
    viewModel: SecretsTransferReceiverViewModel,
    onBack: () -> Unit,
    largeQr: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive credentials") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onBack()
                    }) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            when (val s = state) {
                ReceiverState.Idle -> Text(
                    text = "Preparing a one-time handshake…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ReceiverState.Active -> ActiveBlock(
                    state = s,
                    largeQr = largeQr,
                    onEnvelopeChanged = viewModel::updateEnvelopeText,
                    onImport = { scope.launch { viewModel.acceptEnvelopeJson() } },
                )
                is ReceiverState.Imported -> ImportedBlock(s.result)
                ReceiverState.Expired -> ExpiredBlock(
                    onRestart = { scope.launch { viewModel.restart() } },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActiveBlock(
    state: ReceiverState.Active,
    largeQr: Boolean,
    onEnvelopeChanged: (String) -> Unit,
    onImport: () -> Unit,
) {
    val qrBitmap = remember(state.sessionString) {
        runCatching { AndroidTransferQrRenderer.render(state.sessionString) }.getOrNull()
    }

    Text(
        text = "Open Send credentials on the other Torve device, then scan this QR " +
            "or paste the code into its session string field.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    StatusBanner(
        title = "End-to-end encrypted",
        body = "The QR holds your device's one-time public key — it's safe to share with the " +
            "other Torve device. The sealed envelope you'll receive can only be opened on " +
            "this device. The Torve backend never sees credentials in the clear.",
        tone = TransferBannerTone.Info,
    )

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (largeQr) it.heightIn(min = 360.dp) else it.heightIn(min = 240.dp) }
            .padding(top = 4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "Credential transfer QR code",
                    modifier = Modifier.aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            } else {
                Text("QR rendering unavailable.", color = Color.Black)
            }
        }
    }

    CountdownChip(remainingSeconds = state.remainingSeconds)

    RelayStatusBanner(state.relayStatus)

    SessionStringBlock(
        sessionString = state.sessionString,
        onCopy = {
            // Caller composables can wrap this — for parity with the
            // sender screen we also offer a copy button here.
        },
    )

    AdvancedPasteSection(
        envelopeText = state.envelopeText,
        importing = state.importing,
        importResult = state.importResult,
        relayStatus = state.relayStatus,
        onChange = onEnvelopeChanged,
        onImport = onImport,
    )
}

@Composable
private fun CountdownChip(remainingSeconds: Long) {
    val mm = (remainingSeconds / 60L).coerceAtLeast(0L)
    val ss = (remainingSeconds % 60L).coerceAtLeast(0L)
    val tone = when {
        remainingSeconds <= 30L -> MaterialTheme.colorScheme.error
        remainingSeconds <= 120L -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .background(tone.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
private fun RelayStatusBanner(status: RelayStatus) {
    when (status) {
        RelayStatus.NotConfigured -> {
            // No relay was injected; surface only the manual paste flow.
        }
        RelayStatus.Registering -> StatusBanner(
            title = "Setting up auto-import…",
            body = "Asking the Torve backend to forward a sealed envelope to this device.",
            tone = TransferBannerTone.Info,
        )
        is RelayStatus.Registered -> StatusBanner(
            title = "Auto-import is on",
            body = "When the sender posts the sealed envelope, this device imports it " +
                "automatically. Manual paste stays available below.",
            tone = TransferBannerTone.Success,
        )
        is RelayStatus.Unavailable -> StatusBanner(
            title = "Auto-import unavailable",
            body = status.reason + " Use the paste field below.",
            tone = TransferBannerTone.Warning,
        )
    }
}

@Composable
private fun SessionStringBlock(
    sessionString: String,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Session string",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        ) {
            SelectionContainer {
                Text(
                    text = sessionString,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        TextButton(onClick = {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Torve receive code", sessionString))
            onCopy()
        }) { Text("Copy session string") }
    }
}

@Composable
private fun AdvancedPasteSection(
    envelopeText: String,
    importing: Boolean,
    importResult: TransferImportResult?,
    relayStatus: RelayStatus,
    onChange: (String) -> Unit,
    onImport: () -> Unit,
) {
    val relayRegistered = relayStatus is RelayStatus.Registered
    var advancedOpen by remember(relayRegistered) { mutableStateOf(!relayRegistered) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (relayRegistered) {
            TextButton(onClick = { advancedOpen = !advancedOpen }) {
                Text(if (advancedOpen) "Hide manual paste" else "Advanced: paste sealed code manually")
            }
        }
        if (advancedOpen) {
            OutlinedTextField(
                value = envelopeText,
                onValueChange = onChange,
                label = { Text("Sealed credential code from sender") },
                placeholder = { Text("{\"version\":1,...}") },
                singleLine = false,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            )
            Button(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Importing…")
                } else {
                    Text("Import sealed code")
                }
            }
            importResult?.let { ImportResultBanner(it) }
        }
    }
}

@Composable
private fun ImportedBlock(result: TransferImportResult.Success) {
    StatusBanner(
        title = "Credentials imported",
        body = importDescription(result),
        tone = TransferBannerTone.Success,
    )
}

@Composable
private fun ExpiredBlock(onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBanner(
            title = "Receive code expired",
            body = "Receive codes expire so a forgotten one can't be used later. Generate a new code to keep transferring.",
            tone = TransferBannerTone.Warning,
        )
        // Primary action — receive codes are a low-friction operation,
        // so we lead with regenerate instead of forcing the user to
        // back out and re-enter.
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text("Generate new code")
        }
    }
}

@Composable
private fun ImportResultBanner(result: TransferImportResult) {
    when (result) {
        is TransferImportResult.Success -> StatusBanner(
            title = "Credentials imported",
            body = importDescription(result),
            tone = TransferBannerTone.Success,
        )
        is TransferImportResult.MalformedEnvelope -> StatusBanner(
            title = "Invalid sealed code",
            body = result.reason,
            tone = TransferBannerTone.Error,
        )
        is TransferImportResult.DecryptFailure -> StatusBanner(
            title = decryptTitle(result.result),
            body = decryptDescription(result.result),
            tone = TransferBannerTone.Error,
        )
        is TransferImportResult.ApplyFailure -> StatusBanner(
            title = "Could not apply credentials",
            body = applyDescription(result.result),
            tone = TransferBannerTone.Error,
        )
        TransferImportResult.NoActiveSession -> StatusBanner(
            title = "No active receive session",
            body = "Generate a new receive code first.",
            tone = TransferBannerTone.Error,
        )
        TransferImportResult.MissingPrivateKey -> StatusBanner(
            title = "Receive session is no longer usable",
            body = "Generate a new receive code and try again.",
            tone = TransferBannerTone.Error,
        )
    }
}

private fun decryptTitle(result: TransferDecryptResult): String = when (result) {
    TransferDecryptResult.Expired -> "Sealed code expired"
    TransferDecryptResult.AuthenticationFailure -> "Could not decrypt code"
    is TransferDecryptResult.UnsupportedVersion -> "Unsupported transfer version"
    TransferDecryptResult.Replayed -> "Code already used"
    TransferDecryptResult.EnvelopePayloadMismatch -> "Code failed integrity check"
    is TransferDecryptResult.Malformed -> "Malformed sealed code"
    is TransferDecryptResult.Success -> "Credentials imported"
}

private fun decryptDescription(result: TransferDecryptResult): String = when (result) {
    TransferDecryptResult.Expired -> "Ask the sender to generate a fresh sealed code."
    TransferDecryptResult.AuthenticationFailure -> "This code was not sealed for this receive session, or it was changed."
    is TransferDecryptResult.UnsupportedVersion -> "This app cannot read transfer version ${result.seenVersion}."
    TransferDecryptResult.Replayed -> "This transfer nonce has already been consumed on this device."
    TransferDecryptResult.EnvelopePayloadMismatch -> "The envelope and payload expiry values do not match."
    is TransferDecryptResult.Malformed -> result.reason
    is TransferDecryptResult.Success -> "Imported ${result.payload.secrets.size} credential record(s)."
}

private fun applyDescription(result: TransferApplyResult): String = when (result) {
    TransferApplyResult.DuplicateNonce -> "This transfer nonce has already been consumed on this device."
    is TransferApplyResult.NothingApplied -> "No known credential keys were found in the payload."
    is TransferApplyResult.StoreFailure -> buildString {
        append(result.message)
        if (result.rollbackAttempted) {
            append(
                if (result.rollbackSucceeded) {
                    " Rollback succeeded; existing credentials were restored."
                } else {
                    " Rollback failed; verify credentials manually."
                },
            )
        }
    }
    is TransferApplyResult.Success -> "Imported ${result.applied} credential record(s)."
}

private fun importDescription(result: TransferImportResult.Success): String = buildString {
    val configCount = result.applyResult.configApplied
    val applied = result.applyResult.applied
    // Lead line tightened so the user understands two things at once:
    // (a) credentials are in, (b) some providers may take a second.
    if (configCount > 0) {
        append("Credentials and setup details imported. Some providers may take a moment to reconnect.")
    } else {
        append("Credentials imported. Some providers may take a moment to reconnect.")
    }
    append(" Imported ")
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

@Composable
private fun StatusBanner(title: String, body: String, tone: TransferBannerTone) {
    TransferStatusBanner(title = title, body = body, tone = tone)
}
