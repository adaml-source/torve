package com.streamvault.android.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.sync.SyncCoordinator
import com.streamvault.android.sync.model.SyncDeviceDto
import org.koin.compose.koinInject

@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    syncCoordinator: SyncCoordinator = koinInject(),
) {
    val state by syncCoordinator.state.collectAsState()
    var pairingCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        syncCoordinator.refreshDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pair TV", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Local Wi-Fi only. Pair TVs in your home network.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("TV Pairing Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { syncCoordinator.claimPairingCode(pairingCode) },
                enabled = state.isAuthenticated,
            ) {
                Text("Pair TV")
            }
            OutlinedButton(onClick = { syncCoordinator.refreshDevices() }) {
                Text("Refresh")
            }
        }

        if (!state.isAuthenticated) {
            Text(
                text = "Create a local profile first to pair devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.pairingStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    onRevoke = { syncCoordinator.revokeDevice(device.id) },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
private fun DeviceRow(
    device: SyncDeviceDto,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = device.deviceName, style = MaterialTheme.typography.titleMedium)
            Text(text = "${device.deviceType} - ${device.platform}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Last seen: ${device.lastSeenAt}", style = MaterialTheme.typography.bodySmall)
            if (device.revokedAt != null) {
                Text(text = "Revoked: ${device.revokedAt}", color = MaterialTheme.colorScheme.error)
            } else {
                OutlinedButton(onClick = onRevoke) {
                    Text("Revoke")
                }
            }
        }
    }
}
