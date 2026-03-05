package com.streamvault.android.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Obsidian

@Composable
fun EnableSyncSheet(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    isTvFlow: Boolean,
    analytics: SyncAnalytics = NoOpSyncAnalytics,
) {
    analytics.track("sync_enable_sheet_shown", mapOf("tv" to isTvFlow.toString()))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Enable Sync",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "Sync keeps your Torve experience consistent across your devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SyncBenefit("Settings across devices")
            SyncBenefit("Provider connections")
            SyncBenefit("AI search memory")
            SyncBenefit("Watchlist and continue watching")
            SyncBenefit("Send to TV and handoff playback")

            Spacer(modifier = Modifier.size(2.dp))

            Text(
                text = "Torve sync is optional and can be turned off anytime.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Local mode remains available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.size(4.dp))

            Button(
                onClick = {
                    analytics.track("sync_enable_continue", mapOf("tv" to isTvFlow.toString()))
                    onContinue()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = Obsidian,
                ),
            ) {
                Text("Continue")
            }

            OutlinedButton(
                onClick = {
                    analytics.track("sync_enable_not_now")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Not now")
            }
        }
    }
}

@Composable
private fun SyncBenefit(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .background(color = Color(0xFF4CAF50), shape = CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
