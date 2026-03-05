package com.streamvault.android.ui.sync

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.tv.isRunningOnTv
import com.streamvault.domain.sync.AccountSyncManager
import com.streamvault.domain.sync.IdentityState
import com.streamvault.domain.sync.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SyncCenterScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTvPairing: () -> Unit,
    onOpenPairingClaim: () -> Unit,
    accountSyncManager: AccountSyncManager = koinInject(),
    analytics: SyncAnalytics = NoOpSyncAnalytics,
) {
    val scope = rememberCoroutineScope()
    val status by accountSyncManager.status.collectAsState()
    val context = LocalContext.current
    val isTv = isRunningOnTv(context)
    var showEnableSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        accountSyncManager.refreshStatus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackButton(onClick = onBack)

        Text(
            text = "Sync Center",
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Torve sync is opt in. Local mode keeps data only on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = statusTitle(status.sync, status.identity),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusDescription(status.sync, status.identity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "Paired devices: ${status.pairedDevicesCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Pending actions: ${status.pendingActionsCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.lastSyncTimeMs?.let { timestamp ->
                    Text(
                        text = "Last sync: ${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when {
                    status.sync == SyncState.OFF -> {
                        Button(
                            onClick = { showEnableSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enable Sync")
                        }
                    }

                    status.sync == SyncState.ON && status.identity == IdentityState.LOCAL -> {
                        Button(
                            onClick = onOpenLogin,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign in to activate sync")
                        }
                    }

                    status.sync == SyncState.ON && status.identity == IdentityState.SIGNED_IN -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (isTv) {
                                        onOpenTvPairing()
                                    } else {
                                        onOpenPairingClaim()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Manage Devices")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        accountSyncManager.recordSyncActionQueued("manual_sync")
                                        delay(250)
                                        accountSyncManager.recordSyncActionDelivered()
                                        analytics.track("sync_now")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Sync Now")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    accountSyncManager.disableSync()
                                    analytics.track("sync_disabled")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Disable Sync")
                        }
                    }

                    status.sync == SyncState.PAUSED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        accountSyncManager.resumeSync()
                                        analytics.track("sync_resumed")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Resume Sync")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        accountSyncManager.markLocalOnly()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Use Local Mode")
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                )
                Text(
                    text = "Sync is optional. You can switch back to Local Mode at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
            }
        }
    }

    if (showEnableSheet) {
        EnableSyncSheet(
            onDismiss = { showEnableSheet = false },
            onContinue = {
                showEnableSheet = false
                scope.launch { accountSyncManager.enableSync() }
                if (isTv) {
                    onOpenTvPairing()
                } else {
                    onOpenLogin()
                }
            },
            isTvFlow = isTv,
            analytics = analytics,
        )
    }
}

private fun statusTitle(syncState: SyncState, identity: IdentityState): String {
    return when {
        syncState == SyncState.ON && identity == IdentityState.SIGNED_IN -> "Sync Enabled"
        syncState == SyncState.ON && identity == IdentityState.LOCAL -> "Sync Needs Sign In"
        syncState == SyncState.PAUSED -> "Sync Paused"
        else -> "Local Mode"
    }
}

private fun statusDescription(syncState: SyncState, identity: IdentityState): String {
    return when {
        syncState == SyncState.ON && identity == IdentityState.SIGNED_IN -> "Settings and activity can sync across your devices."
        syncState == SyncState.ON && identity == IdentityState.LOCAL -> "You enabled sync intent. Sign in to activate cloud sync."
        syncState == SyncState.PAUSED -> "Sync is temporarily paused."
        else -> "Your data stays on this device only."
    }
}
