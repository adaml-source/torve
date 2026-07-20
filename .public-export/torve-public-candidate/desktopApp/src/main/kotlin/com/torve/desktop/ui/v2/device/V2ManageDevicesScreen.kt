package com.torve.desktop.ui.v2.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.presentation.device.DeviceGovernanceViewModel

@Composable
fun V2ManageDevicesScreen(
    viewModel: DeviceGovernanceViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors

    LaunchedEffect(Unit) {
        viewModel.fetchDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TorvePageHeader(
            title = ds("Manage Devices"),
            subtitle = ds("Active devices registered to your account. Remove a device to free up a safety slot."),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveBadge(
                        text = state.deviceUsageText,
                        tone = if (state.effectiveCapReached) TorveBadgeTone.Warning else TorveBadgeTone.Accent,
                    )
                    V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
                }
            },
        )

        if (state.isLoading && state.devices.isEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        state.errorKey?.let { key ->
            TorveBanner(
                title = ds("Device error"),
                description = key,
                tone = TorveBannerTone.Error,
            )
        }

        if (state.effectiveCapReached && state.deviceLimitKnown) {
            TorveBanner(
                title = ds("Device limit reached"),
                description = ds("You have reached your %1\$d-device limit. Remove one below to activate another device.")
                    .format(state.maxActiveDevices),
                tone = TorveBannerTone.Warning,
            )
        } else if (!state.deviceLimitKnown) {
            TorveBanner(
                title = ds("Checking device limit"),
                description = ds("Fetching your account device limit from the backend."),
                tone = TorveBannerTone.Info,
            )
        }

        TorveSectionCard(
            title = ds("Active devices"),
            supportingText = ds("Swaps remaining in the last 30 days: %1\$d")
                .format(state.swapsRemaining.coerceAtLeast(0)),
        ) {
            val activeDevices = state.devices.filter { it.removed_at == null }
            if (activeDevices.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No active devices"),
                    description = ds("Sign in on another device to see it listed here."),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeDevices.forEach { device ->
                        DeviceRow(
                            device = device,
                            isRemoving = state.isRemoving,
                            onRemove = { viewModel.removeDevice(device.id) },
                        )
                    }
                }
            }
        }

        val removedDevices = state.devices.filter { it.removed_at != null }
        if (removedDevices.isNotEmpty()) {
            TorveSectionCard(
                title = ds("Recently removed"),
                supportingText = ds("These slots are freed up and available for new devices."),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    removedDevices.forEach { device ->
                        TorveListRow(
                            title = device.device_name,
                            subtitle = "${device.platform} • ${device.device_type} • ${ds("removed")}",
                            trailing = {
                                TorveBadge(ds("Removed"), tone = TorveBadgeTone.Neutral)
                            },
                        )
                    }
                }
            }
        }

        Text(
            text = ds("Devices become inactive after 45 days without use and free up automatically."),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun DeviceRow(
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
            if (device.app_version != null) {
                append(" • ")
                append(device.app_version)
            }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.is_current) {
                    TorveBadge(ds("This device"), tone = TorveBadgeTone.Accent)
                } else if (device.is_active) {
                    TorveBadge(ds("Active"), tone = TorveBadgeTone.Success)
                }
                if (!device.is_current) {
                    TorveGhostButton(
                        text = if (isRemoving) ds("Removing...") else ds("Remove"),
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
            title = { Text(ds("Remove device?"), fontWeight = FontWeight.SemiBold) },
            text = {
                Text(ds("Remove %1\$s? It will need to be re-registered before it can access this account again.").format(device.device_name))
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onRemove()
                }) { Text(ds("Remove")) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(ds("Cancel")) }
            },
        )
    }
}
