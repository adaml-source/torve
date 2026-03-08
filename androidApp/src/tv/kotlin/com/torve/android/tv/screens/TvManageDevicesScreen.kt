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

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.fetchDevices() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        try { backButtonRequester.requestFocus() } catch (_: Throwable) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp),
    ) {
        // Back button — always focusable for D-pad
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
                .focusRequester(backButtonRequester)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("← Back", color = if (backFocused) Amber else Color.Gray, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Manage Devices",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your Torve Pro account can be active on up to ${state.maxActiveDevices} devices at a time.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${state.activeDeviceCount} of ${state.maxActiveDevices} devices active",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Amber,
        )
        Spacer(Modifier.height(24.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.devices, key = { it.id }) { device ->
                    TvDeviceCard(
                        device = device,
                        onRemove = { viewModel.removeDevice(device.id) },
                        isRemoving = state.isRemoving,
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
            text = "Removing a device frees a slot for another device. Inactive devices may stop counting automatically after extended inactivity.",
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
            try { firstCardRequester.requestFocus() } catch (_: Throwable) {}
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.devices.filter { it.is_active }, key = { it.id }) { device ->
                    TvDeviceCard(
                        device = device,
                        onRemove = { viewModel.removeDevice(device.id) },
                        isRemoving = state.isRemoving,
                        modifier = if (device == state.devices.filter { it.is_active }.firstOrNull())
                            Modifier.focusRequester(firstCardRequester) else Modifier,
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
private fun TvDeviceCard(
    device: ManagedDeviceDto,
    onRemove: () -> Unit,
    isRemoving: Boolean,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val icon: ImageVector = when (device.device_type) {
        "tv" -> Icons.Default.Tv
        "tablet" -> Icons.Default.Tablet
        else -> Icons.Default.PhoneAndroid
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
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (device.is_active) Amber else Color.Gray, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.device_name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Snow)
                    if (device.is_current) {
                        Spacer(Modifier.width(8.dp))
                        Text("THIS DEVICE", style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text("${device.platform} · ${device.device_type}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
