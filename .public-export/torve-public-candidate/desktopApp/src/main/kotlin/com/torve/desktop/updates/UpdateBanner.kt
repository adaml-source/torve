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
 *
 * The [handoffPhase] is surfaced inline so users get feedback on the
 * download / verify / hand-off stages. Without it, clicking "Download
 * & install" was a black box: SHA mismatch, network failure, and the
 * happy path all looked identical (the click "did nothing"). Caught by
 * B4 smoke 2026-05-03 when an ngrok abuse 403 silently failed the
 * handoff.
 */
@Composable
fun UpdateBanner(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    onView: () -> Unit,
    onInstall: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    handoffPhase: UpdateInstallerHandoff.Phase = UpdateInstallerHandoff.Phase.Idle,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val canInstall = onInstall != null && info.installerUrl != null
    val handoffActive = handoffPhase !is UpdateInstallerHandoff.Phase.Idle
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp)),
        color = colors.cardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Accent disc with update icon.
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
                    val installLabel = when (handoffPhase) {
                        is UpdateInstallerHandoff.Phase.Idle -> "Download & install"
                        is UpdateInstallerHandoff.Phase.Downloading -> "Downloading…"
                        is UpdateInstallerHandoff.Phase.Verifying -> "Verifying…"
                        is UpdateInstallerHandoff.Phase.HandedOff -> "Launching installer…"
                        is UpdateInstallerHandoff.Phase.Failed -> "Try again"
                    }
                    TorveGhostButton(
                        text = "Release notes",
                        onClick = onView,
                        enabled = !handoffActive ||
                            handoffPhase is UpdateInstallerHandoff.Phase.Failed,
                    )
                    TorvePrimaryButton(
                        text = installLabel,
                        onClick = onInstall!!,
                        enabled = handoffPhase is UpdateInstallerHandoff.Phase.Idle ||
                            handoffPhase is UpdateInstallerHandoff.Phase.Failed,
                    )
                } else {
                    TorvePrimaryButton(text = "View release", onClick = onView)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                    enabled = !handoffActive ||
                        handoffPhase is UpdateInstallerHandoff.Phase.Failed ||
                        handoffPhase is UpdateInstallerHandoff.Phase.HandedOff,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // Inline status row appears below the action row whenever the
            // handoff is in flight, succeeded, or failed. Without it, a
            // SHA mismatch or download failure was indistinguishable from
            // success — the click looked like it "did nothing".
            val statusText: String? = when (val p = handoffPhase) {
                is UpdateInstallerHandoff.Phase.Idle -> null
                is UpdateInstallerHandoff.Phase.Downloading ->
                    if (p.totalBytes != null && p.totalBytes > 0) {
                        val pct = (p.bytesRead * 100 / p.totalBytes).toInt().coerceIn(0, 100)
                        "Downloading installer… $pct%"
                    } else {
                        "Downloading installer… ${p.bytesRead / (1024 * 1024)} MB"
                    }
                is UpdateInstallerHandoff.Phase.Verifying ->
                    "Verifying installer signature…"
                is UpdateInstallerHandoff.Phase.HandedOff ->
                    "Installer launched. Torve will close when the upgrade starts."
                is UpdateInstallerHandoff.Phase.Failed ->
                    "Couldn't install: ${p.reason}"
            }
            if (statusText != null) {
                val tone = if (handoffPhase is UpdateInstallerHandoff.Phase.Failed) {
                    colors.error
                } else {
                    colors.textSecondary
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                    modifier = Modifier.padding(start = 58.dp, end = 14.dp, bottom = 10.dp),
                )
            }
        }
    }
}
