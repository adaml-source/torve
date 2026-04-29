package com.torve.desktop.updates

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Slim "an update is available" banner. Positioned by the caller (V2App
 * docks it at top-center). Stays out of the way: a single line of copy +
 * two buttons (View release / Dismiss). Doesn't auto-update — the user
 * downloads the new build manually for now.
 */
@Composable
fun UpdateBanner(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    onView: () -> Unit,
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
            Column(modifier = Modifier.padding(end = 12.dp).fillMaxWidth(0.6f)) {
                Text(
                    text = "Torve update available",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "${info.name} — you're on $currentVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            TorveGhostButton(text = "Dismiss", onClick = onDismiss)
            TorvePrimaryButton(text = "View release", onClick = onView)
        }
    }
}
