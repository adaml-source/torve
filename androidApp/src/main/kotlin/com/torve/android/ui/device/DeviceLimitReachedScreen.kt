package com.torve.android.ui.device

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.error.resolveErrorKey
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Snow
import com.torve.data.device.ManagedDeviceDto
import com.torve.presentation.device.DeviceGovernanceViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLimitReachedScreen(
    onBack: () -> Unit,
    onActivated: () -> Unit,
    viewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.fetchDevices()
    }

    // Auto-navigate when current device becomes active
    LaunchedEffect(state.premiumAccess, state.activateSuccess) {
        if (state.premiumAccess || state.activateSuccess) {
            onActivated()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.device_limit_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_cd))
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Icon(
                    Icons.Default.DevicesOther,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(48.dp).fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (state.deviceLimitKnown) {
                        stringResource(R.string.device_limit_message, state.maxActiveDevices)
                    } else {
                        stringResource(R.string.device_limit_message_loading)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.activeDeviceCountKnown && state.deviceLimitKnown) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.manage_devices_count, state.activeDeviceCount, state.maxActiveDevices),
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.device_limit_active_devices),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
            }

            items(
                state.devices.filter { it.is_active },
                key = { it.id },
            ) { device ->
                DeviceLimitRow(
                    device = device,
                    onRemove = { viewModel.removeDevice(device.id) },
                    isRemoving = state.isRemoving,
                )
            }

            state.errorKey?.let { key ->
                resolveErrorKey(context, key)?.let { message ->
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.device_limit_activate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DeviceLimitRow(
    device: ManagedDeviceDto,
    onRemove: () -> Unit,
    isRemoving: Boolean,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val icon: ImageVector = when (device.device_type) {
        "tv" -> Icons.Default.Tv
        "tablet" -> Icons.Default.Tablet
        else -> Icons.Default.PhoneAndroid
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Amber, modifier = Modifier.size(28.dp))
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
                        Text(stringResource(R.string.manage_devices_this_device), style = MaterialTheme.typography.labelSmall, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "${device.platform} · ${device.device_type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!device.is_current) {
                IconButton(
                    onClick = { showConfirm = true },
                    enabled = !isRemoving,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_remove), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.manage_devices_remove_title)) },
            text = { Text(stringResource(R.string.device_limit_remove_confirm, device.device_name)) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onRemove() }) {
                    Text(stringResource(R.string.device_limit_remove_activate), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
