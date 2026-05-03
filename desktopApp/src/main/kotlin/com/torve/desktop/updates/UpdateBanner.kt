package com.torve.desktop.updates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * "Update available" banner. Positioned by the caller (V2App docks it
 * top-center). Layout: small accent circle with an update icon, a tight
 * two-line text block (heading + version delta), then actions on the
 * right. Dismiss is a small unobtrusive X — the primary action is the
 * **Download & install** button when the appcast carries an installer
 * URL, falling back to **View release** otherwise.
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
    val canInstall = onInstall != null && info.installerUrl != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp)),
        color = colors.cardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Accent disc with update icon — gives the banner an obvious
            // "this is an update" affordance without needing a colored
            // background that fights with the rest of the shell chrome.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.accentContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdateAlt,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    text = "Update available",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "${info.tag} · you're on $currentVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (canInstall) {
                TorveGhostButton(text = "Release notes", onClick = onView)
                TorvePrimaryButton(text = "Download & install", onClick = onInstall!!)
            } else {
                TorvePrimaryButton(text = "View release", onClick = onView)
            }
            // Dismiss is the smallest control on the row — an unfilled X
            // that doesn't compete with the primary action for attention.
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
