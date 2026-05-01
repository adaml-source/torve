package com.torve.android.ui.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.presentation.transfer.RelayDeliveryState
import com.torve.presentation.transfer.SecretsTransferSenderViewModel
import com.torve.presentation.transfer.SenderStatus
import com.torve.presentation.transfer.TransferCategorySpec
import com.torve.presentation.transfer.TransferCopy
import com.torve.presentation.transfer.TransferSecretCatalog
import kotlinx.coroutines.launch

/**
 * Cross-form-factor credential-transfer sender screen.
 *
 * Mobile: scan-first surface (camera + paste field below).
 * TV:     paste-first surface (paste at top; scan section only if a
 *         camera is detected at runtime). Driven by [preferPaste].
 *
 * Manual paste is always visible — the scanner is an accelerator, not
 * a gate. The relay POST is best-effort; any failure surfaces the
 * sealed code for manual paste on the receiving device.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SecretsTransferSendScreen(
    viewModel: SecretsTransferSenderViewModel,
    onBack: () -> Unit,
    preferPaste: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasCamera = remember { deviceHasAnyCamera(context) }

    var scannerOpen by remember { mutableStateOf(false) }
    var scannerStatus by remember { mutableStateOf<ScannerUnavailable?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send credentials") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            Text(
                text = TransferCopy.SEND_STEP1_HEADER,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = TransferCopy.SEND_STEP1_EXPLAINER,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!preferPaste && hasCamera) {
                ScanSection(
                    scannerOpen = scannerOpen,
                    scannerStatus = scannerStatus,
                    onToggleScanner = {
                        scannerOpen = !scannerOpen
                        scannerStatus = null
                    },
                    onScanned = { scanned ->
                        scannerOpen = false
                        viewModel.updateReceiverSessionString(scanned)
                    },
                    onUnavailable = {
                        scannerOpen = false
                        scannerStatus = it
                    },
                )
            }

            PasteSection(
                value = state.receiverSessionString,
                onChange = viewModel::updateReceiverSessionString,
            )

            if (preferPaste && hasCamera) {
                ScanSection(
                    scannerOpen = scannerOpen,
                    scannerStatus = scannerStatus,
                    onToggleScanner = {
                        scannerOpen = !scannerOpen
                        scannerStatus = null
                    },
                    onScanned = { scanned ->
                        scannerOpen = false
                        viewModel.updateReceiverSessionString(scanned)
                    },
                    onUnavailable = {
                        scannerOpen = false
                        scannerStatus = it
                    },
                )
            }

            CategoryPicker(
                selected = state.selectedCategories,
                onChange = viewModel::setCategoryEnabled,
            )

            Button(
                onClick = { scope.launch { viewModel.generateEnvelope() } },
                enabled = state.status !is SenderStatus.Generating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.status is SenderStatus.Generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Generating…")
                } else {
                    Text("Generate sealed code")
                }
            }

            when (val status = state.status) {
                SenderStatus.Idle -> Unit
                SenderStatus.Generating -> StatusBanner(
                    title = "Sealing credentials",
                    body = "Credentials stay local while the encrypted envelope is generated.",
                    tone = TransferBannerTone.Info,
                )
                is SenderStatus.Error -> StatusBanner(
                    title = "Could not generate code",
                    body = status.message,
                    tone = TransferBannerTone.Error,
                )
                is SenderStatus.Ready -> ReadyBlock(status = status)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScanSection(
    scannerOpen: Boolean,
    scannerStatus: ScannerUnavailable?,
    onToggleScanner: () -> Unit,
    onScanned: (String) -> Unit,
    onUnavailable: (ScannerUnavailable) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Scan QR from receiving device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (scannerStatus is ScannerUnavailable.PermissionDenied) {
                Text(
                    text = TransferCopy.SEND_CAMERA_DENIED,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (scannerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(top = 4.dp),
                ) {
                    QrScannerWithPermission(
                        onQrDetected = onScanned,
                        onUnavailable = onUnavailable,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                TextButton(onClick = onToggleScanner) { Text("Close camera") }
            } else {
                OutlinedButton(onClick = onToggleScanner) { Text("Open camera") }
            }
        }
    }
}

@Composable
private fun PasteSection(
    value: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = TransferCopy.SEND_RECEIVER_FIELD_LABEL,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(TransferCopy.SEND_RECEIVER_FIELD_PLACEHOLDER) },
            singleLine = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
        )
        if (value.isBlank()) {
            Text(
                text = TransferCopy.SEND_RECEIVER_EMPTY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryPicker(
    selected: Set<com.torve.domain.transfer.SecretCategory>,
    onChange: (com.torve.domain.transfer.SecretCategory, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = TransferCopy.SEND_STEP2_HEADER,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        TransferSecretCatalog.specs.forEach { spec ->
            CategoryRow(
                spec = spec,
                checked = spec.category in selected,
                onCheckedChange = { onChange(spec.category, it) },
            )
        }
    }
}

@Composable
private fun CategoryRow(
    spec: TransferCategorySpec,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReadyBlock(status: SenderStatus.Ready) {
    val context = LocalContext.current
    val included = status.includedCategories.joinToString { TransferSecretCatalog.titleFor(it) }
    val missing = status.categoriesWithoutSecrets
        .takeIf { it.isNotEmpty() }
        ?.joinToString { TransferSecretCatalog.titleFor(it) }
    val missingCompanion = status.categoriesMissingCompanionConfig
        .takeIf { it.isNotEmpty() }
        ?.joinToString { TransferSecretCatalog.titleFor(it) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBanner(
            title = "Sealed code ready",
            body = buildString {
                append("Encrypted ")
                append(status.secretCount)
                append(" credential record")
                if (status.secretCount != 1) append("s")
                if (status.configCount > 0) {
                    append(" + ")
                    append(status.configCount)
                    append(" config record")
                    if (status.configCount != 1) append("s")
                }
                append(" for: ")
                append(included.ifBlank { "selected categories" })
                if (missing != null) {
                    append(". No local credentials found for: ")
                    append(missing)
                }
                append(".")
            },
            tone = TransferBannerTone.Success,
        )

        if (missingCompanion != null) {
            StatusBanner(
                title = "Companion config missing",
                body = "Tokens for $missingCompanion are included, but their server URL " +
                    "is not set on this device. The receiver will need to fill it in manually.",
                tone = TransferBannerTone.Warning,
            )
        }

        RelayDeliveryBanner(status.relayDelivery)

        SealedCodeBlock(
            envelopeJson = status.envelopeJson,
            onCopy = { copyToClipboard(context, "Torve sealed code", status.envelopeJson) },
        )
    }
}

@Composable
private fun RelayDeliveryBanner(state: RelayDeliveryState) {
    when (state) {
        RelayDeliveryState.NotAttempted -> Unit
        RelayDeliveryState.Posting -> StatusBanner(
            title = "Delivering through relay…",
            body = "Posting the encrypted bundle to the Torve backend so the receiver can " +
                "pull it automatically.",
            tone = TransferBannerTone.Info,
        )
        RelayDeliveryState.Delivered -> StatusBanner(
            title = "Delivered to the receiver",
            body = "The encrypted bundle is on the relay; the receiver will import on its " +
                "next poll.",
            tone = TransferBannerTone.Success,
        )
        is RelayDeliveryState.Failed -> StatusBanner(
            title = "Relay delivery failed",
            body = state.reason,
            tone = TransferBannerTone.Warning,
        )
    }
}

@Composable
private fun SealedCodeBlock(envelopeJson: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Manual fallback — sealed code",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        ) {
            SelectionContainer {
                Text(
                    text = envelopeJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        TextButton(onClick = onCopy) { Text("Copy sealed code") }
    }
}

@Composable
private fun StatusBanner(title: String, body: String, tone: TransferBannerTone) {
    TransferStatusBanner(title = title, body = body, tone = tone)
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}
