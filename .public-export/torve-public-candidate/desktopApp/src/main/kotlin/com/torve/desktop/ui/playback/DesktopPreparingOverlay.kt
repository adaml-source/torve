package com.torve.desktop.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.desktop.playback.DesktopPreparingStreamState
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import kotlinx.coroutines.delay

/**
 * Full-surface overlay shown while a Panda/addon stream is still warming up
 * upstream. Mirrors the Android [com.torve.android.ui.detail.StreamPreparingOverlay]:
 * spinner, elapsed-time ticker, attempt counter, cancel button. Stays mounted
 * over the (intentionally empty) player surface so the user isn't bounced
 * back to the detail page mid-poll.
 */
@Composable
fun DesktopPreparingOverlay(
    state: DesktopPreparingStreamState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    // Eat clicks on the scrim so anything behind it doesn't get accidentally
    // tapped while the overlay is up.
    val noRipple = remember { MutableInteractionSource() }
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.84f))
                .clickable(interactionSource = noRipple, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = colors.elevatedSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.6f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(18.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = colors.accent,
                        strokeWidth = 3.dp,
                    )
                    Text(
                        text = "Preparing your stream",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    Text(
                        text = "${state.serviceName} is downloading the file. Playback starts automatically as soon as it's ready.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )

                    // Live elapsed-time ticker.
                    var nowMs by remember(state.startedAtEpochMs) {
                        mutableLongStateOf(System.currentTimeMillis())
                    }
                    LaunchedEffect(state.startedAtEpochMs) {
                        while (true) {
                            nowMs = System.currentTimeMillis()
                            delay(1_000L)
                        }
                    }
                    val elapsedSec = ((nowMs - state.startedAtEpochMs) / 1_000L).coerceAtLeast(0L)

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Elapsed ${formatMmSs(elapsedSec)}  •  attempt ${state.attempt} / ${state.maxAttempts}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accent,
                        fontWeight = FontWeight.Medium,
                    )

                    // Attempt-progress bar - gives a visual sense of how long
                    // we've been polling vs the max budget. It is intentionally
                    // an attempt indicator (not download %) because Panda's
                    // server doesn't report download progress for /u/<token>/nzb.
                    LinearProgressIndicator(
                        progress = {
                            (state.attempt.toFloat() / state.maxAttempts.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = colors.accent,
                        trackColor = colors.borderSubtle,
                    )

                    if (state.canCancel) {
                        Spacer(Modifier.height(4.dp))
                        TorveGhostButton(
                            text = "Cancel",
                            onClick = onCancel,
                        )
                    }
                }
            }
        }
    }
}

private fun formatMmSs(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    val sStr = if (s < 10) "0$s" else s.toString()
    return "$m:$sStr"
}
