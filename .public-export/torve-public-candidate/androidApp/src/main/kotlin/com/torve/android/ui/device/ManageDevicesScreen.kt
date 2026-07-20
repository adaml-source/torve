package com.torve.android.ui.device

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
fun ManageDevicesScreen(
    onBack: () -> Unit,
    viewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.fetchDevices()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.manage_devices_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_cd))
                }
            },
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header
                item {
                    Text(
                        text = if (state.deviceLimitKnown) {
                            stringResource(R.string.manage_devices_limit_info, state.maxActiveDevices)
                        } else {
                            stringResource(R.string.manage_devices_limit_loading)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.activeDeviceCountKnown && state.deviceLimitKnown) {
                            stringResource(R.string.manage_devices_count, state.activeDeviceCount, state.maxActiveDevices)
                        } else {
                            stringResource(R.string.manage_devices_count_loading)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Amber,
                    )
                    if (state.effectiveCapReached && state.deviceLimitKnown) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.manage_devices_limit_reached_warning, state.maxActiveDevices),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Active devices
                val activeDevices = state.devices.filter { it.removed_at == null }
                items(activeDevices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        onRemove = { viewModel.removeDevice(device.id) },
                        isRemoving = state.isRemoving,
                    )
                }

                // Footer
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.manage_devices_remove_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.manage_devices_inactive_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // Error
                state.errorKey?.let { key ->
                    resolveErrorKey(context, key)?.let { message ->
                        item {
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: ManagedDeviceDto,
    onRemove: () -> Unit,
    isRemoving: Boolean,
) {
    var showConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.is_active)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = deviceIcon(device.device_type),
                contentDescription = null,
                tint = if (device.is_active) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
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
                        Text(
                            text = stringResource(R.string.manage_devices_this_device),
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = "${device.platform} · ${device.device_type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (device.is_active) {
                    Text(
                        text = stringResource(R.string.manage_devices_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF22C55E),
                    )
                } else if (device.removed_at != null) {
                    Text(
                        text = stringResource(R.string.manage_devices_removed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!device.is_current) {
                IconButton(
                    onClick = { showConfirm = true },
                    enabled = !isRemoving,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.manage_devices_remove_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.manage_devices_remove_title)) },
            text = { Text(stringResource(R.string.manage_devices_remove_confirm, device.device_name)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onRemove()
                }) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun deviceIcon(type: String): ImageVector = when (type) {
    "tv" -> Icons.Default.Tv
    "tablet" -> Icons.Default.Tablet
    else -> Icons.Default.PhoneAndroid
}
