package com.torve.android.ui.sync

import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.SecureChannelState
import com.torve.android.sync.model.SyncDeviceDto
import com.torve.android.ui.transfer.QrScannerView
import com.torve.android.ui.transfer.cameraPermissionGranted
import com.torve.android.ui.transfer.deviceHasAnyCamera
import com.torve.domain.sync.SyncRepository
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.presentation.pairing.TvPairingSignInViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onDeviceLimitReached: () -> Unit = {},
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
        Text(stringResource(R.string.devices_paired_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.devices_paired_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.isAuthenticated && state.pairingCodeFlowSupported) {
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                label = { Text(stringResource(R.string.devices_pairing_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { syncCoordinator.claimPairingCode(pairingCode) },
                    enabled = true,
                ) {
                    Text(stringResource(R.string.devices_add_pairing))
                }
                OutlinedButton(onClick = { syncCoordinator.refreshDevices() }) {
                    Text(stringResource(R.string.devices_refresh))
                }
            }

            // Inline QR scanner for the "Sign in your TV with this phone"
            // flow. The TV displays a QR encoding `torve-signin:<code>`;
            // scanning it here unwraps the code and POSTs to
            // `/pairing/signin/claim` (NOT the legacy `/pairing/claim`,
            // which targets the premium-gated phone↔TV remote-control
            // flow on a different table). The TV's own poll then
            // receives auth tokens for this phone's account.
            val context = LocalContext.current
            val hasCamera = remember { deviceHasAnyCamera(context) }
            if (hasCamera) {
                var scannerOpen by remember { mutableStateOf(false) }
                var permissionGranted by remember {
                    mutableStateOf(cameraPermissionGranted(context))
                }
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    permissionGranted = granted
                    if (granted) scannerOpen = true
                }
                if (!scannerOpen) {
                    OutlinedButton(
                        onClick = {
                            if (permissionGranted) scannerOpen = true
                            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Scan TV sign-in QR")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            QrScannerView(
                                onQrDetected = { payload ->
                                    val code = TvPairingSignInViewModel
                                        .extractCodeFromQrPayload(payload)
                                    if (code != null) {
                                        scannerOpen = false
                                        syncCoordinator.claimTvSigninCode(
                                            code = code,
                                            onDeviceLimitReached = onDeviceLimitReached,
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        OutlinedButton(
                            onClick = { scannerOpen = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            }
        } else {
            OutlinedButton(onClick = { syncCoordinator.refreshDevices() }) {
                Text(stringResource(R.string.devices_refresh))
            }
        }

        if (!state.isAuthenticated) {
            Text(
                text = stringResource(R.string.devices_sign_in_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.isAuthenticated && !state.pairingCodeFlowSupported) {
            Text(
                text = stringResource(R.string.devices_pairing_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let {
            Text(
                com.torve.android.error.resolveErrorKey(LocalContext.current, it) ?: stringResource(R.string.error_unknown),
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.pairingStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (state.isAuthenticated && state.devices.isEmpty()) {
            Text(
                text = stringResource(R.string.devices_no_paired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val syncRepository: SyncRepository = koinInject()

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.devices, key = { it.pairingId ?: it.id }) { device ->
                val channelState = state.secureChannelStates[device.id] ?: SecureChannelState.PAIRED_BACKEND_ONLY
                PairedDeviceCard(
                    device = device,
                    channelState = channelState,
                    onUnpair = { syncCoordinator.revokeDevice(device.id) },
                    onEstablishSecureChannel = {
                        scope.launch {
                            val result = syncCoordinator.bootstrapSecureChannel(device.id)
                            result.onSuccess {
                                Toast.makeText(context, context.getString(R.string.devices_secure_channel_ok, device.deviceName), Toast.LENGTH_SHORT).show()
                            }.onFailure { _ ->
                                Toast.makeText(context, context.getString(R.string.devices_secure_channel_fail), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onSendSetup = {
                        scope.launch {
                            val payloadJson = syncRepository.exportForLocalTransfer()
                            val result = syncCoordinator.sendSettingsPush(
                                targetDeviceId = device.id,
                                categories = listOf("all"),
                                payloadJson = payloadJson,
                            )
                            result.onSuccess {
                                Toast.makeText(context, context.getString(R.string.devices_setup_sent, device.deviceName), Toast.LENGTH_SHORT).show()
                            }.onFailure { _ ->
                                Toast.makeText(context, context.getString(R.string.devices_setup_fail), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.common_back))
        }
    }
}

@Composable
private fun PairedDeviceCard(
    device: SyncDeviceDto,
    channelState: SecureChannelState,
    onUnpair: () -> Unit,
    onEstablishSecureChannel: () -> Unit,
    onSendSetup: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = device.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(R.string.sync_device_info, device.deviceType, device.platform), style = MaterialTheme.typography.bodySmall)
            Text(text = stringResource(R.string.devices_last_seen, device.lastSeenAt), style = MaterialTheme.typography.bodySmall)

            // Secure channel status
            val (statusText, statusColor) = when (channelState) {
                SecureChannelState.PAIRED_SECURE_CHANNEL_READY -> "Secure channel active" to MaterialTheme.colorScheme.primary
                SecureChannelState.PAIRED_LAN_REACHABLE_NO_SECRET -> "Secure channel not established" to MaterialTheme.colorScheme.tertiary
                SecureChannelState.PAIRED_LAN_UNREACHABLE -> "Device not visible on network" to MaterialTheme.colorScheme.onSurfaceVariant
                SecureChannelState.PAIRED_BACKEND_ONLY -> "Paired (device offline)" to MaterialTheme.colorScheme.onSurfaceVariant
                SecureChannelState.PAIRED_REVOKED -> stringResource(R.string.devices_state_revoked) to MaterialTheme.colorScheme.error
                SecureChannelState.NOT_PAIRED -> "Not paired" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)

            if (device.revokedAt != null) {
                Text(text = stringResource(R.string.devices_revoked_at, device.revokedAt ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (device.revokedAt == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (channelState) {
                        SecureChannelState.PAIRED_SECURE_CHANNEL_READY -> {
                            Button(onClick = onSendSetup) {
                                Text(stringResource(R.string.devices_send_setup))
                            }
                        }
                        SecureChannelState.PAIRED_LAN_REACHABLE_NO_SECRET -> {
                            Button(onClick = onEstablishSecureChannel) {
                                Text(stringResource(R.string.devices_establish_secure))
                            }
                        }
                        else -> {}
                    }
                    OutlinedButton(onClick = onUnpair) {
                        Text(stringResource(R.string.devices_unpair))
                    }
                }
            }
        }
    }
}
