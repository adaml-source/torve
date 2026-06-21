package com.torve.desktop.ui.v2.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.data.device.ManagedDeviceDto
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.presentation.device.DeviceGovernanceViewModel

@Composable
fun V2DeviceLimitReachedScreen(
    viewModel: DeviceGovernanceViewModel,
    onDismiss: () -> Unit,
    onActivated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors

    LaunchedEffect(Unit) {
        viewModel.fetchDevices()
    }

    LaunchedEffect(state.premiumAccess, state.activateSuccess) {
        if (state.premiumAccess || state.activateSuccess) {
            onActivated()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TorvePageHeader(
            title = ds("Register this device"),
            subtitle = if (state.deviceLimitKnown) {
                ds("This account has reached its %1\$d-device safety limit. Remove an unused device to continue here.")
                    .format(state.maxActiveDevices)
            } else {
                ds("Checking account device registration before continuing.")
            },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveBadge(
                        text = state.deviceUsageText,
                        tone = TorveBadgeTone.Warning,
                    )
                    TorveGhostButton(text = ds("Not now"), onClick = onDismiss)
                }
            },
        )

        TorveBanner(
            title = ds("Device registration required"),
            description = if (state.deviceLimitKnown) {
                ds("This device is not registered for your account yet. Remove an unused device below to continue here.")
                    .format(state.maxActiveDevices)
            } else {
                ds("Fetching your account device limit from the backend before registration.")
            },
            tone = TorveBannerTone.Warning,
        )

        state.errorKey?.let { key ->
            TorveBanner(
                title = ds("Couldn't update devices"),
                description = key,
                tone = TorveBannerTone.Error,
            )
        }

        TorveSectionCard(
            title = ds("Active devices"),
            supportingText = ds("Swaps remaining in the last 30 days: %1\$d")
                .format(state.swapsRemaining.coerceAtLeast(0)),
        ) {
            val activeDevices = state.devices.filter { it.is_active && it.removed_at == null }
            if (activeDevices.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("Loading devices..."),
                    description = ds("Fetching your current device list from the backend."),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeDevices.forEach { device ->
                        DeviceSlotRow(
                            device = device,
                            isRemoving = state.isRemoving,
                            onRemove = { viewModel.removeDevice(device.id) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorvePrimaryButton(
                text = if (state.isActivating) ds("Registering...") else ds("Try to register now"),
                onClick = { viewModel.activateCurrentDevice() },
                enabled = !state.isActivating,
            )
            TorveGhostButton(text = ds("Cancel"), onClick = onDismiss)
        }

        Text(
            text = ds("Removing a device revokes its account access immediately. Up to 3 swaps are allowed per 30 days."),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun DeviceSlotRow(
    device: ManagedDeviceDto,
    isRemoving: Boolean,
    onRemove: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    TorveListRow(
        title = device.device_name,
        subtitle = buildString {
            append(device.platform)
            append(" • ")
            append(device.device_type)
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.is_current) {
                    TorveBadge(ds("This device"), tone = TorveBadgeTone.Accent)
                } else {
                    TorveGhostButton(
                        text = if (isRemoving) ds("Removing...") else ds("Remove & activate here"),
                        onClick = { showConfirm = true },
                        enabled = !isRemoving,
                    )
                }
            }
        },
    )

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(ds("Remove %1\$s?").format(device.device_name), fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    ds("This will revoke its access and activate this device instead. You can re-activate the other device later, but it counts as one of your 3 swaps per 30 days."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onRemove()
                }) { Text(ds("Remove & activate")) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(ds("Cancel")) }
            },
        )
    }
}
