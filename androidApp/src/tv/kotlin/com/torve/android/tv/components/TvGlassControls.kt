package com.torve.android.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Snow

/** Translucent TV control chrome; no blur/render effects on older Fire TV GPUs. */
@Composable
internal fun Modifier.tvGlassControl(
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    cornerRadius: Dp = 12.dp,
    focusBorderWidth: Dp = 2.dp,
): Modifier {
    val light by animateFloatAsState(if (focused) 1f else 0f, label = "tvGlassLight")
    val opacity = if (enabled) 1f else 0.55f
    val shape = RoundedCornerShape(cornerRadius)
    val settings = LocalTvSettingsStyle.current
    val reflection = if (settings) .30f else 1f
    return clip(shape)
        .then(if (settings) Modifier.background(androidx.compose.ui.graphics.Color(0xFF11151F).copy(alpha = .72f)) else Modifier)
        .background(Brush.verticalGradient(listOf(
            Snow.copy(alpha = (0.12f + light * 0.09f) * opacity * reflection),
            (if (selected) AmberLight else Gunmetal).copy(alpha = (if (selected) 0.16f else 0.30f) * opacity),
            Graphite.copy(alpha = (0.26f + light * 0.10f) * opacity),
        )))
        .border(if (focused) focusBorderWidth else 1.dp,
            Brush.verticalGradient(if (focused) listOf(AmberLight, AmberLight) else listOf(
                Snow.copy(alpha = 0.28f * opacity),
                (if (selected) AmberLight else Snow).copy(alpha = 0.10f * opacity),
                Snow.copy(alpha = 0.16f * opacity),
            )), shape)
}
