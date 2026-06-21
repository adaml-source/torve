package com.torve.desktop.ui.v2.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.download.DesktopDownloadManagerState
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import kotlinx.coroutines.delay

@Composable
fun DownloadStatusOverlay(
    state: DesktopDownloadManagerState,
) {
    val colors = TorveDesktopThemeTokens.colors

    // Toast - shows whenever lastEvent changes, auto-dismisses after 4s.
    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.lastEvent, state.lastEventNonce) {
        val event = state.lastEvent?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        toastMessage = event
        delay(4_000)
        if (toastMessage == event) toastMessage = null
    }

    // Thin progress bar - visible while a download is active.
    val activeTitle = state.activeDownloadTitle?.takeIf { it.isNotBlank() }
    val showProgress = activeTitle != null

    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Surface(
                modifier = Modifier.widthIn(min = 280.dp, max = 520.dp),
                color = colors.dockSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f)),
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Download,
                        null,
                        tint = colors.accent,
                    )
                    Text(
                        text = toastMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showProgress,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Surface(
                modifier = Modifier.widthIn(min = 320.dp, max = 560.dp),
                color = colors.dockSurface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
                shadowElevation = 4.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.Download,
                            null,
                            tint = colors.accent,
                            modifier = Modifier.width(16.dp).height(16.dp),
                        )
                        Text(
                            text = activeTitle ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 380.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${(state.activeProgress * 100f).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.activeProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = colors.accent,
                        trackColor = colors.borderSubtle,
                    )
                }
            }
        }
    }
}
