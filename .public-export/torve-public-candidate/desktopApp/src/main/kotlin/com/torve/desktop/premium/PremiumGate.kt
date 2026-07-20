package com.torve.desktop.premium

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility helpers retained for Desktop call sites that have not yet
 * been renamed. Torve no longer blocks product features on paid state.
 */
@Deprecated("Paid feature gates were removed; this helper always runs the action.")
fun premiumGated(
    onUpgradeRequired: () -> Unit,
    action: () -> Unit,
): () -> Unit = { action() }

@Deprecated("Paid feature gates were removed; product features are free by default.")
@Composable
fun rememberHasPremium(): Boolean = true

@Deprecated("Paid locked sections were removed; this component intentionally renders nothing.")
@Composable
fun PremiumLockedSection(
    title: String,
    description: String,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No-op compatibility surface.
}
