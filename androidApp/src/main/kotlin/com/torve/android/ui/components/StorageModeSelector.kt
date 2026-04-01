package com.torve.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Torve
import com.torve.domain.integrations.IntegrationRuntimeState
import com.torve.domain.integrations.IntegrationStorageMode

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Storage Mode — reusable components for integration screens
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Global Explainer ────────────────────────────────────────

@Composable
fun StorageModeExplainer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Gunmetal.copy(alpha = 0.6f))
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.storage_how_title),
            style = MaterialTheme.typography.titleSmall,
            color = Torve.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.storage_how_body),
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.storage_how_options),
            style = MaterialTheme.typography.labelSmall,
            color = Torve.colors.textHint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.storage_how_note),
            style = MaterialTheme.typography.labelSmall,
            color = Torve.colors.textHint,
        )
    }
}

// ── Per-Integration Selector ────────────────────────────────

@Composable
fun StorageModeSelector(
    selected: IntegrationStorageMode,
    onModeSelected: (IntegrationStorageMode) -> Unit,
    modifier: Modifier = Modifier,
    isSignedIn: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.storage_store_label),
            style = MaterialTheme.typography.labelMedium,
            color = Torve.colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))

        StorageModeOption(
            label = if (isSignedIn) stringResource(R.string.storage_account_recommended) else stringResource(R.string.storage_account_sign_in_required),
            description = stringResource(R.string.storage_account_desc),
            selected = selected == IntegrationStorageMode.ACCOUNT,
            enabled = isSignedIn,
            onClick = { if (isSignedIn) onModeSelected(IntegrationStorageMode.ACCOUNT) },
        )

        Spacer(Modifier.height(4.dp))

        StorageModeOption(
            label = stringResource(R.string.storage_device_only_label),
            description = stringResource(R.string.storage_device_only_desc),
            selected = selected == IntegrationStorageMode.DEVICE_ONLY,
            enabled = true,
            onClick = { onModeSelected(IntegrationStorageMode.DEVICE_ONLY) },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.storage_keys_note),
            style = MaterialTheme.typography.labelSmall,
            color = Torve.colors.textHint,
        )
    }
}

@Composable
private fun StorageModeOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Gunmetal else Gunmetal.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = if (enabled) onClick else null,
            modifier = Modifier.size(20.dp),
            colors = RadioButtonDefaults.colors(
                selectedColor = Amber,
                unselectedColor = if (enabled) Silver else Silver.copy(alpha = 0.3f),
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Torve.colors.textPrimary else Torve.colors.textHint,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = Torve.colors.textHint,
            )
        }
    }
}

// ── Status Badges ───────────────────────────────────────────
// Storage mode and runtime state are separate concepts.
// StorageModeBadge shows WHERE credentials are stored.
// RuntimeStateBadge shows WHETHER credentials are usable now.

@Composable
fun StorageModeBadge(
    mode: IntegrationStorageMode,
    modifier: Modifier = Modifier,
) {
    val (text, bg, fg) = when (mode) {
        IntegrationStorageMode.ACCOUNT -> Triple(stringResource(R.string.storage_badge_account), Amber.copy(alpha = 0.15f), Amber)
        IntegrationStorageMode.DEVICE_ONLY -> Triple(stringResource(R.string.storage_badge_device_only), Gunmetal, Torve.colors.textHint)
    }
    StatusBadgeText(text, bg, fg, modifier)
}

@Composable
fun RuntimeStateBadge(
    state: IntegrationRuntimeState,
    modifier: Modifier = Modifier,
) {
    val (text, bg, fg) = when (state) {
        IntegrationRuntimeState.CONNECTED -> Triple(stringResource(R.string.storage_state_connected), Emerald.copy(alpha = 0.15f), Emerald)
        IntegrationRuntimeState.NEEDS_REAUTH -> Triple(stringResource(R.string.storage_state_needs_reauth), Ruby.copy(alpha = 0.15f), Ruby)
        IntegrationRuntimeState.NEEDS_CREDENTIALS -> Triple(stringResource(R.string.storage_state_needs_credentials), Ruby.copy(alpha = 0.15f), Ruby)
        IntegrationRuntimeState.NOT_CONFIGURED -> Triple(stringResource(R.string.storage_state_not_configured), Gunmetal, Torve.colors.textHint)
    }
    StatusBadgeText(text, bg, fg, modifier)
}

@Composable
private fun StatusBadgeText(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Compact storage mode label (backward-compatible alias). */
@Composable
fun StorageModeLabel(
    mode: IntegrationStorageMode,
    modifier: Modifier = Modifier,
) {
    StorageModeBadge(mode = mode, modifier = modifier)
}

// ── Missing Credentials Card ────────────────────────────────

@Composable
fun MissingCredentialsCard(
    runtimeState: IntegrationRuntimeState,
    onEnterCredentials: () -> Unit,
    onChangeStorageMethod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, body, actionLabel) = when (runtimeState) {
        IntegrationRuntimeState.NEEDS_CREDENTIALS -> Triple(
            stringResource(R.string.storage_creds_needed_title),
            stringResource(R.string.storage_creds_needed_body),
            stringResource(R.string.storage_creds_enter),
        )
        IntegrationRuntimeState.NEEDS_REAUTH -> Triple(
            stringResource(R.string.storage_reauth_title),
            stringResource(R.string.storage_reauth_body),
            stringResource(R.string.storage_reauth_action),
        )
        else -> return // Only render for actionable states
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ruby.copy(alpha = 0.08f))
            .padding(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Ruby,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onEnterCredentials,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = onChangeStorageMethod) {
                Text(stringResource(R.string.storage_change_method), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────

@Composable
fun RemoveIntegrationDialog(
    storageMode: IntegrationStorageMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val body = when (storageMode) {
        IntegrationStorageMode.ACCOUNT ->
            stringResource(R.string.storage_remove_account_body)
        IntegrationStorageMode.DEVICE_ONLY ->
            stringResource(R.string.storage_remove_device_body)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_remove_title)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_remove), color = Ruby)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun ChangeStorageModeDialog(
    currentMode: IntegrationStorageMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body, confirmLabel) = when (currentMode) {
        IntegrationStorageMode.ACCOUNT -> Triple(
            stringResource(R.string.storage_switch_device_title),
            stringResource(R.string.storage_switch_device_body),
            stringResource(R.string.storage_switch_device_confirm),
        )
        IntegrationStorageMode.DEVICE_ONLY -> Triple(
            stringResource(R.string.storage_switch_account_title),
            stringResource(R.string.storage_switch_account_body),
            stringResource(R.string.storage_switch_account_confirm),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = Amber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
