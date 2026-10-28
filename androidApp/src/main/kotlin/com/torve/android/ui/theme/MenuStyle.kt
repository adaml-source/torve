package com.torve.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Translucent menus share one treatment; focus changes paint, never geometry. */
@Composable
fun menuPanelColor(focused: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    if (com.torve.android.tv.components.LocalTvSettingsStyle.current) return colors.surface.copy(alpha = .96f)
    val dark = colors.surface.luminance() < 0.5f
    return colors.surface.copy(alpha = if (dark) { if (focused) 0.76f else 0.52f } else 0.90f)
}

@Composable
fun menuOutlineColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
