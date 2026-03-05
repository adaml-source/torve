package com.streamvault.android.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.tv.isRunningOnTv
import com.streamvault.domain.sync.AccountSyncManager
import com.streamvault.domain.sync.AccountSyncStatus
import com.streamvault.domain.sync.IdentityState
import com.streamvault.domain.sync.SyncState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

enum class SyncGateReason {
    SEND_TO_TV,
    PLAYBACK_HANDOFF,
    CLOUD_PROVIDER_TOKENS,
    AI_PERSONALIZATION,
    CROSS_DEVICE_SETTINGS,
}

@Composable
fun RequireSync(
    reason: SyncGateReason,
    status: AccountSyncStatus,
    onProceed: () -> Unit,
    accountSyncManager: AccountSyncManager = koinInject(),
    content: @Composable () -> Unit,
) {
    val canProceed = status.sync == SyncState.ON && status.identity == IdentityState.SIGNED_IN
    var showEnableSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    if (canProceed) {
        content()
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Enable Sync to use this",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = reasonSubtitle(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { showEnableSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Enable Sync")
                }
            }
        }
    }

    if (showEnableSheet) {
        EnableSyncSheet(
            onDismiss = { showEnableSheet = false },
            onContinue = {
                showEnableSheet = false
                scope.launch { accountSyncManager.enableSync() }
                onProceed()
            },
            isTvFlow = isRunningOnTv(context),
        )
    }
}

private fun reasonSubtitle(reason: SyncGateReason): String {
    return when (reason) {
        SyncGateReason.SEND_TO_TV -> "Send results to your TV instantly."
        SyncGateReason.PLAYBACK_HANDOFF -> "Start playback on another paired device."
        SyncGateReason.CLOUD_PROVIDER_TOKENS -> "Keep provider connections secure across devices."
        SyncGateReason.AI_PERSONALIZATION -> "Sync your AI search memory across devices."
        SyncGateReason.CROSS_DEVICE_SETTINGS -> "Keep app settings consistent on all devices."
    }
}
