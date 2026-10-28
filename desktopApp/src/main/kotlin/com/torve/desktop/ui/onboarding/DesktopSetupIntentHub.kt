package com.torve.desktop.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/** Outcome-first onboarding which keeps implementation-specific service names
 * behind the guided connection routes. */
@Composable
fun DesktopSetupIntentHub(
    onSetUpStreamingSources: () -> Unit,
    onConnectPersonalLibrary: () -> Unit,
    onAddLiveTv: () -> Unit,
    onSkipForNow: () -> Unit,
    onShowQrReceive: () -> Unit,
    isCompleting: Boolean = false,
    completionError: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "What do you want to set up first?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Text(
            text = "Choose one outcome now. You can add or repair every connection later under Settings → Connections.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )

        completionError?.let {
            TorveBanner(
                title = "Setup couldn't continue",
                description = it,
                tone = TorveBannerTone.Error,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Primary outcome: guided Debrid / Usenet setup.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TorvePrimaryButton(
                text = if (isCompleting) "Opening setup…" else "Set up streaming sources",
                onClick = onSetUpStreamingSources,
                enabled = !isCompleting,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Recommended for watching now — connect Debrid or Usenet with guided checks.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Secondary outcomes route directly to their settings categories.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TorveGhostButton(
                text = "Connect my personal library",
                onClick = onConnectPersonalLibrary,
                enabled = !isCompleting,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Connect Plex, Jellyfin, requests, and library automation.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TorveGhostButton(
                text = "Add live TV",
                onClick = onAddLiveTv,
                enabled = !isCompleting,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Add an M3U or Xtream playlist, then load channels and guide data.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Import and skip remain available without competing with outcomes.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveGhostButton(
                text = "Skip and explore",
                onClick = onSkipForNow,
                enabled = !isCompleting,
            )
            TorveGhostButton(
                text = "Receive from another device",
                onClick = onShowQrReceive,
                enabled = !isCompleting,
            )
        }

        if (isCompleting) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp).widthIn(max = 24.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
            }
        }
    }
}
