package com.torve.android.ui.panda

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel

/**
 * One-time display surface for a freshly-minted Panda management token.
 *
 * Masked by default; the user can Show, Copy, then "I've saved it" to dismiss.
 * Intentionally framed as advanced so casual users can skip it without friction,
 * while power users can capture the token for cross-device restore. The token
 * is NEVER logged or displayed in analytics / crash reports — the caller hands
 * it in exactly once and receives a callback to forget it from in-memory state.
 *
 * @param token the freshly-minted management token (null collapses the card)
 * @param notice optional server-provided notice ("Save this now, shown only once")
 * @param onAcknowledge invoked when the user taps "I've saved it"
 */
@Composable
fun PandaManagementTokenCard(
    token: String?,
    notice: String?,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (token.isNullOrBlank()) return
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Gunmetal)
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.panda_management_token_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            notice
                ?: stringResource(R.string.panda_management_token_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (revealed) token else mask(token),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Amber,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Steel.copy(alpha = 0.15f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { revealed = !revealed },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (revealed) stringResource(R.string.common_hide) else stringResource(R.string.panda_show_token), color = Silver)
            }
            OutlinedButton(
                onClick = { copyToClipboard(context, token) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_copy), color = Silver)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Amber),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.panda_management_token_saved), color = Gunmetal, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun mask(token: String): String {
    if (token.length <= 8) return "•".repeat(token.length.coerceAtLeast(8))
    return "${token.take(4)}${"•".repeat(token.length - 8)}${token.takeLast(4)}"
}

private fun copyToClipboard(context: Context, token: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    // Deliberately unlabeled — some system clipboard UIs surface the label.
    cm.setPrimaryClip(ClipData.newPlainText("", token))
}
