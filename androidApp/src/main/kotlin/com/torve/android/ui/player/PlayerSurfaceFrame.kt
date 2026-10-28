package com.torve.android.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keep player presentation in its own composition method as controls grow. */
@Composable
internal fun PlayerSurfaceFrame(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier, content = content)
}
