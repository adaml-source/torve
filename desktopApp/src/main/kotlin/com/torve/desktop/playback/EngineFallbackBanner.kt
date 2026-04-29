package com.torve.desktop.playback

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Slim banner shown when the user picked an engine that couldn't start
 * (e.g. MPV requested but libmpv missing). The shell silently falls back
 * to VLC — this banner makes that fact visible so users don't think MPV
 * is running when it isn't.
 */
@Composable
fun EngineFallbackBanner(
    snapshot: PlayerEngineStartup.Snapshot,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
        color = colors.accentContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.padding(end = 12.dp).fillMaxWidth(0.65f)) {
                Text(
                    text = "${snapshot.requested.label} unavailable — using ${snapshot.actual.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Install the missing runtime or pick a different engine in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            TorveGhostButton(text = "Dismiss", onClick = onDismiss)
            TorvePrimaryButton(text = "Open Settings", onClick = onOpenSettings)
        }
    }
}
