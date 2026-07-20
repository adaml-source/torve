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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
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
    backButtonRequester: FocusRequester = remember { FocusRequester() },
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
    viewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsState()
    val firstDeviceRequester = remember { FocusRequester() }
    var closeArmed by remember { mutableStateOf(false) }
    val sortedDevices = remember(state.devices) {
        state.devices.sortedWith(
            compareByDescending<ManagedDeviceDto> { it.is_current }
                .thenByDescending { it.is_active }
                .thenByDescending { it.last_seen_at },
        )
    }

    val entryRequester = if (sortedDevices.isEmpty()) backButtonRequester else firstDeviceRequester
    onFirstContentRequester(entryRequester)

    BackHandler(enabled = closeArmed, onBack = onBack)
    LaunchedEffect(Unit) { viewModel.fetchDevices() }
    LaunchedEffect(Unit) {
        closeArmed = false
        kotlinx.coroutines.delay(300)
        closeArmed = true
    }
    LaunchedEffect(sortedDevices.size) {
        val delays = listOf(20L, 60L, 120L, 220L, 360L)
        for (waitMs in delays) {
            kotlinx.coroutines.delay(waitMs)
            val focused = runCatching {
                entryRequester.requestFocus()
                true
            }.getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp),
    ) {
        TvBackButton(
            focusRequester = backButtonRequester,
            onFocused = { onContentFocused(backButtonRequester) },
            onClick = {
                if (closeArmed) onBack()
            },
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.tv_manage_activated_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tv_manage_activated_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.activeDeviceCountKnown && state.deviceLimitKnown) {
                stringResource(R.string.tv_manage_active_count, state.activeDeviceCount, state.maxActiveDevices)
            } else {
                stringResource(R.string.tv_manage_active_count_loading)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Amber,
        )
        if (state.effectiveCapReached && state.deviceLimitKnown) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tv_manage_limit_reached_warning, state.maxActiveDevices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber)
            }
        } else if (sortedDevices.isEmpty()) {
            Text(
                text = stringResource(R.string.tv_manage_no_activated),
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
                        modifier = if (device == sortedDevices.firstOrNull()) {
                            Modifier.focusRequester(firstDeviceRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        state.errorKey?.let { key ->
            com.torve.android.error.resolveErrorKey(context, key)?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.tv_manage_revoke_info),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

@Composable
fun TvPairedDevicesScreen(
    onBack: () -> Unit,
    backButtonRequester: FocusRequester = remember { FocusRequester() },
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
    syncCoordinator: SyncCoordinator = koinInject(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by syncCoordinator.state.collectAsState()
    val firstDeviceRequester = remember { FocusRequester() }
    var closeArmed by remember { mutableStateOf(false) }
    val sortedDevices = remember(state.devices, state.deviceId) {
        state.devices.sortedWith(
            compareByDescending<SyncDeviceDto> { it.id == state.deviceId }
                .thenBy { it.revokedAt != null }
                .thenByDescending { it.lastSeenAt },
        )
    }

    val entryRequester = if (sortedDevices.isEmpty()) backButtonRequester else firstDeviceRequester
    onFirstContentRequester(entryRequester)

    BackHandler(enabled = closeArmed, onBack = onBack)
    LaunchedEffect(Unit) { syncCoordinator.refreshDevices() }
    LaunchedEffect(Unit) {
        closeArmed = false
        kotlinx.coroutines.delay(300)
        closeArmed = true
    }
    LaunchedEffect(sortedDevices.size, state.deviceId) {
        val delays = listOf(20L, 60L, 120L, 220L, 360L)
        for (waitMs in delays) {
            kotlinx.coroutines.delay(waitMs)
            val focused = runCatching {
                entryRequester.requestFocus()
                true
            }.getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp),
    ) {
        TvBackButton(
            focusRequester = backButtonRequester,
            onFocused = { onContentFocused(backButtonRequester) },
            onClick = {
                if (closeArmed) onBack()
            },
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.tv_manage_paired_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tv_manage_paired_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
        )
        Spacer(Modifier.height(24.dp))

        if (sortedDevices.isEmpty()) {
            Text(
                text = stringResource(R.string.tv_manage_no_paired),
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
                        modifier = if (device == sortedDevices.firstOrNull()) {
                            Modifier.focusRequester(firstDeviceRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = com.torve.android.error.resolveErrorKey(context, it) ?: stringResource(R.string.error_unknown),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.tv_manage_unpair_info),
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
            text = stringResource(R.string.tv_manage_device_limit_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (state.deviceLimitKnown) {
                stringResource(R.string.tv_manage_device_limit_desc, state.maxActiveDevices)
            } else {
                stringResource(R.string.tv_manage_device_limit_desc_loading)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
        if (state.activeDeviceCountKnown && state.deviceLimitKnown) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tv_manage_active_count, state.activeDeviceCount, state.maxActiveDevices),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Amber,
            )
        }
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

        state.errorKey?.let { key ->
            com.torve.android.error.resolveErrorKey(context, key)?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TvActivatedDeviceCard(
    device: ManagedDeviceDto,
    isMutating: Boolean,
    onRevokeAccess: () -> Unit,
    onRemoveDevice: () -> Unit,
    modifier: Modifier = Modifier,
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
        "Revoking this device removes its account access and frees a device registration."
    } else {
        "Removing this device deletes it from your activated-device list."
    }

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
                        Text(stringResource(R.string.tv_manage_current_device), style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "${device.platform} - ${device.device_type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Text(
                    text = stringResource(R.string.tv_manage_last_active, device.last_seen_at),
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
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun TvPairedDeviceCard(
    device: SyncDeviceDto,
    currentDeviceId: String?,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val isCurrent = device.id == currentDeviceId
    val isPaired = device.revokedAt == null
    val actionLabel = when {
        isCurrent -> null
        isPaired -> "Unpair"
        else -> null
    }
    val confirmTitle = "Unpair Device"
    val confirmText = "Unpairing disconnects control and playback handoff with this device but does not revoke Premium."

    Box(
        modifier = modifier
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
                        Text(stringResource(R.string.tv_manage_this_tv), style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "${device.platform} - ${device.deviceType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Text(
                    text = stringResource(R.string.tv_manage_last_seen, device.lastSeenAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                if (device.revokedAt != null) {
                    Text(
                        text = stringResource(R.string.tv_manage_unpaired, device.revokedAt ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                Text(
                    text = "State: ${if (isPaired) device.pairingState else "revoked"}",
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
                        onUnpair()
                    },
                ) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
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
                        Text(stringResource(R.string.tv_manage_this_device), style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text("${device.platform} - ${device.device_type}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            if (device.is_active && !device.is_current) {
                Button(
                    onClick = { showConfirm = true },
                    enabled = !isRemoving,
                ) {
                    Text(stringResource(R.string.common_remove))
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.tv_manage_remove_title)) },
            text = { Text(stringResource(R.string.tv_manage_remove_confirm, device.device_name)) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onRemove() }) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun TvBackButton(
    focusRequester: FocusRequester,
    onFocused: () -> Unit = {},
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
            .onFocusChanged {
                backFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter) -> {
                        onClick()
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter) -> {
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.tv_manage_back), color = if (backFocused) Amber else Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun iconForDeviceType(deviceType: String): ImageVector {
    return when (deviceType.lowercase()) {
        "tv" -> Icons.Default.Tv
        "tablet" -> Icons.Default.Tablet
        else -> Icons.Default.PhoneAndroid
    }
}
