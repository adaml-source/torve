package com.torve.desktop.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Modal blocker shown when a free user tries an action gated to
 * Premium (Play, Save, Connect, etc.). Dimmed scrim + centered card
 * with the surface-specific reason and an Upgrade CTA.
 *
 * Caller controls visibility - render only when needed:
 *
 * ```
 * if (showUpgrade) PremiumRequiredOverlay(
 *     reason = "Playback is a Premium feature.",
 *     onDismiss = { showUpgrade = false },
 * )
 * ```
 */
@Composable
fun PremiumRequiredOverlay(
    reason: String,
    onDismiss: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    // Auto-dismiss when access becomes premium - covers the
    // "user clicked Upgrade, payment succeeded, the aggressive poll
    // saw the new entitlement" path.
    val hasPremium by DesktopPremiumStateHolder.hasPremium.collectAsState()
    androidx.compose.runtime.LaunchedEffect(hasPremium) {
        if (hasPremium) onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(480.dp)
                .border(1.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .clickable(enabled = false, onClick = {}),
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Premium required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Text(
                    text = "Trailers, settings, account management, and read-only access to already-saved data stay free. Add-on installs, integration saves, downloads, and content playback are part of the Torve Premium plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "After purchase, gating clears automatically - Torve polls your account every few seconds for ~5 minutes after you click Upgrade.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TorvePrimaryButton(
                        text = "Upgrade",
                        onClick = {
                            startDesktopStripeCheckout()
                        },
                    )
                    TorveGhostButton(
                        text = "Refresh access",
                        onClick = { DesktopPremiumStateHolder.refreshNow() },
                    )
                    TorveGhostButton(text = "Not now", onClick = onDismiss)
                }
            }
        }
    }
}
