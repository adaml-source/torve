package com.torve.android.tv.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Only provided by the Fire TV settings shell; phone styling is unaffected. */
val LocalTvSettingsStyle = staticCompositionLocalOf { false }
val TvSettingsDialogSurface = Color(0xFF171A22)

@Composable
fun TvSettingsStyle(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val colors = MaterialTheme.colorScheme.copy(
        surface = Color(0xFF181B24), surfaceVariant = Color(0xFF252934),
        surfaceContainer = Color(0xFF1D2029), surfaceContainerLow = Color(0xFF181B24),
        surfaceContainerHigh = TvSettingsDialogSurface,
        onSurface = Color(0xFFF4F4F7), onSurfaceVariant = Color(0xFFBEC1CD),
        outline = Color(0xFF626776), outlineVariant = Color(0xFF373C49),
    )
    CompositionLocalProvider(LocalTvSettingsStyle provides true) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
