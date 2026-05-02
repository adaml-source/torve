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
 * docks it at top-center). Stays out of the way: one line of copy plus
 * the smallest action set that fits the available metadata:
 *
 *   * When the appcast feed includes a direct `<enclosure url=...>`
 *     installer URL, render **Download & install** as the primary
 *     action - wired by the caller to [UpdateInstallerHandoff.start].
 *   * Otherwise fall back to **View release** (open the release page
 *     in the OS browser).
 *
 * Either way, "Dismiss" hides the banner for the session.
 */
@Composable
fun UpdateBanner(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    onView: () -> Unit,
    onInstall: (() -> Unit)? = null,
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
                    text = "${info.name} - you're on $currentVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            TorveGhostButton(text = "Dismiss", onClick = onDismiss)
            // Show "View release" as a secondary link when an installer
            // handoff is available, so the user can still read the
            // changelog before triggering the install.
            if (onInstall != null && info.installerUrl != null) {
                TorveGhostButton(text = "View release", onClick = onView)
                TorvePrimaryButton(text = "Download & install", onClick = onInstall)
            } else {
                TorvePrimaryButton(text = "View release", onClick = onView)
            }
        }
    }
}
