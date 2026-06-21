package com.torve.desktop.premium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Deprecated("Desktop features are no longer payment-gated; retained for source compatibility.")
@Composable
fun PremiumRequiredOverlay(
    reason: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(reason) {
        onDismiss()
    }
}
