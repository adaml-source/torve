package com.torve.desktop.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Convenience helpers for premium-gating UI surfaces. Two patterns:
 *
 *  - [premiumGated] wraps an action lambda - invokes it when the user
 *    has premium, otherwise calls [onUpgradeRequired] (which the page
 *    routes to its upgrade screen).
 *  - [PremiumLockedSection] replaces an entire section's content with
 *    an inline "Premium required" card when premium is missing,
 *    keeping the surrounding scaffolding visible (for read-only
 *    viewing of e.g. the existing addons list).
 *
 * Both subscribe to [DesktopPremiumStateHolder.hasPremium], so when
 * the access-state poll changes the value the UI reacts immediately.
 */

/** Run [action] when premium is true; otherwise call [onUpgradeRequired]. */
fun premiumGated(
    onUpgradeRequired: () -> Unit,
    action: () -> Unit,
): () -> Unit = {
    if (DesktopPremiumStateHolder.isPremium()) action() else onUpgradeRequired()
}

/**
 * Compose-side check. Use in `enabled = ...` props or to decide which
 * branch to render.
 */
@Composable
fun rememberHasPremium(): Boolean {
    val v by DesktopPremiumStateHolder.hasPremium.collectAsState()
    return v
}

/**
 * Inline card rendered in place of a feature section when the user
 * lacks premium. Keeps the section visually present so the user knows
 * what they'd get on upgrade rather than the surface disappearing.
 */
@Composable
fun PremiumLockedSection(
    title: String,
    description: String,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        color = colors.accentContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorvePrimaryButton(text = "Upgrade", onClick = onUpgrade)
            }
        }
    }
}
