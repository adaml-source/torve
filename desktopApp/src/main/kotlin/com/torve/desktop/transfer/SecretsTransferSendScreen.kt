package com.torve.desktop.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.torve.presentation.transfer.RelayDeliveryState
import com.torve.presentation.transfer.SecretsTransferSenderViewModel
import com.torve.presentation.transfer.SenderStatus
import com.torve.presentation.transfer.TransferCategorySpec
import com.torve.presentation.transfer.TransferCopy
import com.torve.presentation.transfer.TransferSecretCatalog
import kotlinx.coroutines.launch

@Composable
fun SecretsTransferSendScreen(
    viewModel: SecretsTransferSenderViewModel,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val status = state.status
    val colors = TorveDesktopThemeTokens.colors

    // Disclosures default to collapsed - the spec calls for reduced
    // visual noise and a clear three-step main flow. Power users can
    // open the privacy explainer + the category list inline.
    var privacyOpen by remember { mutableStateOf(false) }
    var categoriesOpen by remember { mutableStateOf(false) }

    TorveSectionCard(
        title = "Send credentials to another device",
        supportingText = "Sending starts on the device you want to set up. " +
            "Open Receive credentials there first to get a code.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Step 1: get the receiver code ──
            StepHeader(text = TransferCopy.SEND_STEP1_HEADER)
            Text(
                text = TransferCopy.SEND_STEP1_EXPLAINER,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            TorveTextField(
                value = state.receiverSessionString,
                onValueChange = viewModel::updateReceiverSessionString,
                label = TransferCopy.SEND_RECEIVER_FIELD_LABEL,
                singleLine = false,
                placeholder = TransferCopy.SEND_RECEIVER_FIELD_PLACEHOLDER,
                modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
            )
            if (state.receiverSessionString.isBlank()) {
                Text(
                    text = TransferCopy.SEND_RECEIVER_EMPTY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }

            // ── Step 2: choose what to send (collapsed by default) ──
            StepHeader(text = TransferCopy.SEND_STEP2_HEADER)
            val selectedSummary = remember(state.selectedCategories) {
                if (state.selectedCategories.isEmpty()) {
                    "Nothing selected - tap to choose categories."
                } else {
                    state.selectedCategories.joinToString { TransferSecretCatalog.titleFor(it) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = selectedSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                TorveGhostButton(
                    text = if (categoriesOpen) "Hide categories" else "Choose categories",
                    onClick = { categoriesOpen = !categoriesOpen },
                )
            }
            if (categoriesOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferSecretCatalog.specs.forEach { spec ->
                        CategoryRow(
                            spec = spec,
                            checked = spec.category in state.selectedCategories,
                            onCheckedChange = { checked ->
                                viewModel.setCategoryEnabled(spec.category, checked)
                            },
                        )
                    }
                }
            }

            // ── Step 3: generate ──
            StepHeader(text = TransferCopy.SEND_STEP3_HEADER)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorvePrimaryButton(
                    text = if (status is SenderStatus.Generating) "Generating..." else "Generate sealed code",
                    enabled = status !is SenderStatus.Generating,
                    onClick = { scope.launch { viewModel.generateEnvelope() } },
                )
            }

            when (status) {
                SenderStatus.Idle -> Unit
                SenderStatus.Generating -> TorveBanner(
                    title = "Sealing credentials",
                    description = "Credentials stay local while the encrypted envelope is generated.",
                    tone = TorveBannerTone.Info,
                )
                is SenderStatus.Error -> TorveBanner(
                    title = "Could not generate code",
                    description = status.message,
                    tone = TorveBannerTone.Error,
                )
                is SenderStatus.Ready -> SenderEnvelopeOutput(status)
            }

            // ── Privacy disclosure (collapsed by default) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorveGhostButton(
                    text = if (privacyOpen) "Hide" else TransferCopy.SEND_PRIVACY_DISCLOSURE_HEADER,
                    onClick = { privacyOpen = !privacyOpen },
                )
            }
            if (privacyOpen) {
                Text(
                    text = TransferCopy.SEND_PRIVACY_DISCLOSURE_BODY,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun StepHeader(text: String) {
    val colors = TorveDesktopThemeTokens.colors
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.textPrimary,
    )
}

@Composable
private fun CategoryRow(
    spec: TransferCategorySpec,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        color = colors.cardSurface,
        border = BorderStroke(1.dp, colors.borderSubtle),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.title,
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = spec.description,
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SenderEnvelopeOutput(status: SenderStatus.Ready) {
    val colors = TorveDesktopThemeTokens.colors
    val included = status.includedCategories.joinToString { TransferSecretCatalog.titleFor(it) }
    val missing = status.categoriesWithoutSecrets
        .takeIf { it.isNotEmpty() }
        ?.joinToString { TransferSecretCatalog.titleFor(it) }
    val missingCompanion = status.categoriesMissingCompanionConfig
        .takeIf { it.isNotEmpty() }
        ?.joinToString { TransferSecretCatalog.titleFor(it) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TorveBanner(
            title = "Sealed code ready",
            description = buildString {
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
            tone = TorveBannerTone.Success,
        )

        if (missingCompanion != null) {
            TorveBanner(
                title = "Companion config missing",
                description = "Tokens for $missingCompanion are included, but their server URL " +
                    "is not set on this device. The receiver will need to fill it in manually.",
                tone = TorveBannerTone.Warning,
            )
        }

        RelayDeliveryBanner(status.relayDelivery)

        // Manual sealed-code paste fallback - moved into a collapsed
        // Advanced disclosure per the new flow. Still reachable.
        var advancedOpen by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveGhostButton(
                text = if (advancedOpen) "Hide sealed code" else TransferCopy.SEND_ADVANCED_HEADER,
                onClick = { advancedOpen = !advancedOpen },
            )
        }
        if (advancedOpen) {
            SelectionContainer {
                Surface(
                    color = colors.cardSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                ) {
                    Text(
                        text = status.envelopeJson,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textPrimary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayDeliveryBanner(state: RelayDeliveryState) {
    when (state) {
        RelayDeliveryState.NotAttempted -> {
            // Either the receiver code lacks a relay session id, or the
            // backend client isn't wired. The paste field below is the
            // only path; nothing extra to surface.
        }
        RelayDeliveryState.Posting -> TorveBanner(
            title = "Delivering through relay...",
            description = "Posting the encrypted bundle to the Torve backend so the receiver " +
                "can pull it automatically.",
            tone = TorveBannerTone.Info,
        )
        RelayDeliveryState.Delivered -> TorveBanner(
            title = "Delivered to the receiver",
            description = "The encrypted bundle is on the relay; the receiver will import on " +
                "its next poll. You can close this surface.",
            tone = TorveBannerTone.Success,
        )
        is RelayDeliveryState.Failed -> TorveBanner(
            title = TransferCopy.SEND_RELAY_UNAVAILABLE,
            description = state.reason,
            tone = TorveBannerTone.Warning,
        )
    }
}
