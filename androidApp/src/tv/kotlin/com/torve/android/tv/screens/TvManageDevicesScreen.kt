package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.SyncDeviceDto
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Snow
import com.torve.data.device.ManagedDeviceDto
import com.torve.presentation.device.DeviceGovernanceViewModel
import org.koin.compose.koinInject

@Composable
fun TvManageDevicesScreen(
    onBack: () -> Unit,
    viewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val backButtonRequester = remember { FocusRequester() }
    val sortedDevices = remember(state.devices) {
        state.devices.sortedWith(
            compareByDescending<ManagedDeviceDto> { it.is_current }
                .thenByDescending { it.is_active }
                .thenByDescending { it.last_seen_at },
        )
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.fetchDevices() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { backButtonRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp),
    ) {
        TvBackButton(
            focusRequester = backButtonRequester,
            onClick = onBack,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Activated Devices",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Devices using Lifetime Access under this account.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${state.activeDeviceCount} of ${state.maxActiveDevices} active",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Amber,
        )
        Spacer(Modifier.height(24.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber)
            }
        } else if (sortedDevices.isEmpty()) {
            Text(
                text = "No activated devices found.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sortedDevices, key = { it.id }) { device ->
                    TvActivatedDeviceCard(
                        device = device,
                        isMutating = state.isRemoving,
                        onRevokeAccess = { viewModel.removeDevice(device.id) },
                        onRemoveDevice = { viewModel.removeDevice(device.id) },
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Revoking removes Lifetime Access from that device. Removing clears the device record.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

@Composable
fun TvPairedDevicesScreen(
    onBack: () -> Unit,
    syncCoordinator: SyncCoordinator = koinInject(),
) {
    val state by syncCoordinator.state.collectAsState()
    val backButtonRequester = remember { FocusRequester() }
    val sortedDevices = remember(state.devices, state.deviceId) {
        state.devices.sortedWith(
            compareByDescending<SyncDeviceDto> { it.id == state.deviceId }
                .thenBy { it.revokedAt != null }
                .thenByDescending { it.lastSeenAt },
        )
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { syncCoordinator.refreshDevices() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { backButtonRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp),
    ) {
        TvBackButton(
            focusRequester = backButtonRequester,
            onClick = onBack,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Paired Devices",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Devices paired for sync and control with this TV/account.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
        )
        Spacer(Modifier.height(24.dp))

        if (sortedDevices.isEmpty()) {
            Text(
                text = "No paired devices found.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sortedDevices, key = { it.id }) { device ->
                    TvPairedDeviceCard(
                        device = device,
                        currentDeviceId = state.deviceId,
                        onUnpair = { syncCoordinator.revokeDevice(device.id) },
                        onDeletePairing = { syncCoordinator.removeDevice(device.id) },
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Unpair disconnects sync/control only. Delete pairing removes stale pairing records.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

@Composable
fun TvDeviceLimitReachedScreen(
    onBack: () -> Unit,
    onActivated: () -> Unit,
    viewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val firstCardRequester = remember { FocusRequester() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.fetchDevices() }
    LaunchedEffect(state.premiumAccess, state.activateSuccess) {
        if (state.premiumAccess || state.activateSuccess) onActivated()
    }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && state.devices.isNotEmpty()) {
            kotlinx.coroutines.delay(200)
            runCatching { firstCardRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Tv, contentDescription = null, tint = Amber, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Device Limit Reached",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your Torve Pro account is already active on ${state.maxActiveDevices} devices. Remove one device to activate Torve Pro on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber)
            }
        } else {
            val activeDevices = state.devices.filter { it.is_active }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(activeDevices, key = { it.id }) { device ->
                    TvLimitDeviceCard(
                        device = device,
                        onRemove = { viewModel.removeDevice(device.id) },
                        isRemoving = state.isRemoving,
                        modifier = if (device == activeDevices.firstOrNull()) {
                            Modifier.focusRequester(firstCardRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TvActivatedDeviceCard(
    device: ManagedDeviceDto,
    isMutating: Boolean,
    onRevokeAccess: () -> Unit,
    onRemoveDevice: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val isCurrent = device.is_current
    val actionLabel = when {
        isCurrent -> null
        device.is_active -> "Revoke Access"
        else -> "Remove Device"
    }
    val confirmTitle = if (device.is_active) "Revoke Access" else "Remove Device"
    val confirmText = if (device.is_active) {
        "Revoking this device removes Lifetime Access from it and frees a device slot."
    } else {
        "Removing this device deletes it from your activated-device list."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Amber else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (device.is_active) Color(0xFF1E1E1E) else Color(0xFF151515),
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                enabled = actionLabel != null && !isMutating,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showConfirm = true },
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = iconForDeviceType(device.device_type),
                contentDescription = null,
                tint = if (device.is_active) Amber else Color.Gray,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.device_name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Snow,
                    )
                    if (device.is_current) {
                        Spacer(Modifier.width(8.dp))
                        Text("CURRENT DEVICE", style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "${device.platform} - ${device.device_type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Text(
                    text = "Last active: ${device.last_seen_at}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Text(
                    text = if (device.is_active) "Status: Active" else "Status: Not active",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.is_active) Amber else Color.Gray,
                )
            }
            if (!actionLabel.isNullOrBlank()) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused) Amber else Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showConfirm && actionLabel != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        if (device.is_active) onRevokeAccess() else onRemoveDevice()
                    },
                    enabled = !isMutating,
                ) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TvPairedDeviceCard(
    device: SyncDeviceDto,
    currentDeviceId: String?,
    onUnpair: () -> Unit,
    onDeletePairing: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val isCurrent = device.id == currentDeviceId
    val isPaired = device.revokedAt == null
    val actionLabel = when {
        isCurrent -> null
        isPaired -> "Unpair"
        else -> "Delete Pairing"
    }
    val confirmTitle = if (isPaired) "Unpair Device" else "Delete Pairing"
    val confirmText = if (isPaired) {
        "Unpairing disconnects sync/control with this device but does not revoke Lifetime Access."
    } else {
        "Deleting this stale pairing removes the device from paired-device records."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Amber else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (isPaired) Color(0xFF1E1E1E) else Color(0xFF151515),
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                enabled = actionLabel != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showConfirm = true },
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = iconForDeviceType(device.deviceType),
                contentDescription = null,
                tint = if (isPaired) Amber else Color.Gray,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Snow,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Text("THIS TV", style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "${device.platform} - ${device.deviceType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Text(
                    text = "Last seen: ${device.lastSeenAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                if (device.revokedAt != null) {
                    Text(
                        text = "Unpaired: ${device.revokedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                Text(
                    text = if (isPaired) "State: Paired" else "State: Unpaired",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPaired) Amber else Color.Gray,
                )
            }
            if (!actionLabel.isNullOrBlank()) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused) Amber else Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showConfirm && actionLabel != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        if (isPaired) onUnpair() else onDeletePairing()
                    },
                ) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TvLimitDeviceCard(
    device: ManagedDeviceDto,
    onRemove: () -> Unit,
    isRemoving: Boolean,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Amber else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (device.is_active) Color(0xFF1E1E1E) else Color(0xFF151515),
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = iconForDeviceType(device.device_type),
                contentDescription = null,
                tint = if (device.is_active) Amber else Color.Gray,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.device_name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Snow)
                    if (device.is_current) {
                        Spacer(Modifier.width(8.dp))
                        Text("THIS DEVICE", style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text("${device.platform} - ${device.device_type}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            if (device.is_active && !device.is_current) {
                Button(
                    onClick = { showConfirm = true },
                    enabled = !isRemoving,
                ) {
                    Text("Remove")
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Remove Device") },
            text = { Text("Remove \"${device.device_name}\"?") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onRemove() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TvBackButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var backFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .border(
                width = if (backFocused) 2.dp else 0.dp,
                color = if (backFocused) Amber else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .background(
                if (backFocused) Color(0xFF2A2A2A) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .onFocusChanged { backFocused = it.isFocused }
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("< Back", color = if (backFocused) Amber else Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun iconForDeviceType(deviceType: String): ImageVector {
    return when (deviceType.lowercase()) {
        "tv" -> Icons.Default.Tv
        "tablet" -> Icons.Default.Tablet
        else -> Icons.Default.PhoneAndroid
    }
}
