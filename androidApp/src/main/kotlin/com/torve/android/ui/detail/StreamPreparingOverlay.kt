package com.torve.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.presentation.detail.PreparingStreamState
import kotlinx.coroutines.delay

/**
 * Full-screen overlay shown while a Panda (or equivalent addon) stream is
 * still being prepared upstream. The parent surface is intentionally NOT
 * navigated to the player while this is visible — the probe hasn't said
 * Ready yet, so ExoPlayer must not see the URL. Cancel routes back through
 * the VM's [com.torve.presentation.detail.DetailViewModel.cancelPreparing]
 * which stops the retry loop cleanly.
 */
@Composable
fun StreamPreparingOverlay(
    state: PreparingStreamState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Swallow touches on the scrim so the detail content underneath isn't
    // accidentally tappable while we're in this blocking UX.
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(interactionSource = noRipple, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Gunmetal)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Amber,
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.stream_preparing_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.stream_preparing_body, state.serviceName),
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            // Elapsed-time ticker — recomputed each second from [startedAt]
            // so the copy isn't dependent on the VM updating its attempt
            // counter (probes fire every 15 s, the clock should feel live).
            var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.startedAt) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    delay(1_000L)
                }
            }
            val elapsedSec = ((nowMs - state.startedAt) / 1000L).coerceAtLeast(0L)
            Text(
                stringResource(R.string.stream_preparing_elapsed, formatMmSs(elapsedSec)),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.stream_preparing_subnote),
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
                textAlign = TextAlign.Center,
            )
            if (state.canCancel) {
                Spacer(Modifier.height(22.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        stringResource(R.string.stream_preparing_cancel),
                        color = Silver,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun formatMmSs(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    // Two-digit seconds; minutes unpadded so "0:15", "1:02", "10:30" all read cleanly.
    val sStr = if (s < 10) "0$s" else s.toString()
    return "$m:$sStr"
}
