package com.torve.desktop.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Panda-primary onboarding screen. Replaces the legacy four-card
 * "pick a source category" hub with a single recommended action
 * ("Set up with Panda") plus a skip path. Per the simplification
 * plan in `docs/onboarding-simplification-plan.md`:
 *
 *   - Panda configures debrid + Newznab + Usenet provider in one
 *     flow, so it does the work of all four old cards in one
 *     screen post-onboarding.
 *   - Users who don't want any source can skip — addons + Plex /
 *     Jellyfin auto-discovery still work, and Settings →
 *     Integrations is one click away from the Home empty-state.
 *   - Power users who want to configure debrid, IPTV, or Plex
 *     individually go through Settings → Integrations rather
 *     than having those choices on the first-run screen.
 *
 * The legacy hub helpers (per-intent cards, "Ready to watch"
 * banner, status badge mapping) are gone from this file. If
 * Settings → Integrations later reuses them they can come back
 * in a dedicated component file.
 */
@Composable
fun DesktopSetupIntentHub(
    onSetUpWithPanda: () -> Unit,
    onSkipForNow: () -> Unit,
    onConfigureSourcesIndividually: () -> Unit,
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
            text = "One last step — connect your sources",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Text(
            text = "Panda configures your debrid, Newznab indexers, and Usenet provider in one flow. " +
                "Skip if you want to explore Torve first or set things up manually later.",
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

        // Primary CTA — large, full-width primary button. Routes
        // through the same complete-onboarding-and-deep-link path
        // the legacy Usenet card used, so post-admission Panda
        // setup picks up where this leaves off.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TorvePrimaryButton(
                text = if (isCompleting) "Starting Panda…" else "Set up with Panda",
                onClick = onSetUpWithPanda,
                enabled = !isCompleting,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Recommended — handles debrid, Newznab, and your Usenet provider in one go.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Secondary CTA — skip and enter Torve with no source
        // configured. Built-in addons + Plex / Jellyfin auto-
        // discovery still work; the Home empty-state will offer
        // a "Set up sources" CTA for users who change their mind.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TorveGhostButton(
                text = "Skip for now",
                onClick = onSkipForNow,
                enabled = !isCompleting,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "You can browse Torve and use addons / Plex immediately. Add a streaming source later in Settings → Integrations.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Tertiary, small — for users who know exactly what they
        // want (e.g. "I just want Plex"). Routes to Settings →
        // Integrations post-admission. Visually de-emphasised
        // because most users should pick Panda or Skip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveGhostButton(
                text = "Configure individual sources",
                onClick = onConfigureSourcesIndividually,
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
