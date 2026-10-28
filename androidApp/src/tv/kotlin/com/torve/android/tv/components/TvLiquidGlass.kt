package com.torve.android.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.android.tv.settings.rememberTvReduceMotionPreference

/** Layered glass painted locally: works on Fire OS without backdrop blur or render effects. */
@Composable
internal fun Modifier.tvLiquidGlass(
    focused: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    cornerRadius: Dp = 20.dp,
    focusScale: Float = 1.025f,
    quiet: Boolean = false,
    baseTint: Color = Color.Transparent,
): Modifier {
    val reduceMotion = rememberTvReduceMotionPreference()
    val settings = LocalTvSettingsStyle.current
    val focusLight by animateFloatAsState(
        if (focused) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 160),
        label = "liquidGlassFocus",
    )
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(cornerRadius)
    val opacity = if (enabled) 1f else .45f
    val presence = when {
        !quiet || focused -> 1f
        selected -> .65f
        else -> .32f
    }
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            val lift = if (reduceMotion || settings) 0f else focusLight
            scaleX = 1f + (focusScale - 1f) * lift
            scaleY = scaleX
            shadowElevation = (if (quiet) 8f * focusLight else 3f + 9f * focusLight) * density
            this.shape = shape
            clip = false
            ambientShadowColor = Color.Black.copy(alpha = .20f)
            spotShadowColor = Color.Black.copy(alpha = .38f)
        }
        .clip(shape)
        .drawWithCache {
            val radius = CornerRadius(cornerRadius.toPx())
            val light = focusLight
            val strength = opacity * presence * if (settings) .30f else 1f
            val body = Brush.verticalGradient(
                0f to Color.White.copy(alpha = (.19f + light * .15f) * strength),
                .42f to (if (dark) Color(0xFFAEBED8) else Color.White).copy(alpha = (.065f + light * .08f) * strength),
                1f to (if (dark) Color(0xFF263046) else Color(0xFF64748B)).copy(alpha = (.24f + light * .10f) * strength),
            )
            val reflection = Brush.radialGradient(
                listOf(Color.White.copy(alpha = (.13f + light * .12f) * strength), Color.Transparent),
                center = Offset(size.width * .16f, -size.height * .65f),
                radius = size.width.coerceAtLeast(size.height) * .85f,
            )
            val lowerLight = Brush.radialGradient(
                listOf(Color(0xFFB9D6FF).copy(alpha = (.075f + light * .06f) * strength), Color.Transparent),
                center = Offset(size.width * .95f, size.height * 1.8f),
                radius = size.width.coerceAtLeast(size.height) * .7f,
            )
            val rim = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = (.48f + light * .40f) * strength),
                    Color.White.copy(alpha = .12f * strength),
                    (if (selected || focused) accent else Color.White).copy(alpha = (if (selected || focused) .70f else .23f) * strength),
                ),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
            val stroke = (1f + light * .6f).dp.toPx()
            val inset = stroke / 2f
            onDrawWithContent {
                drawRoundRect(if (settings) Color(0xFF11151F).copy(alpha = .72f) else baseTint, cornerRadius = radius)
                drawRoundRect(body, cornerRadius = radius)
                if (selected) drawRoundRect(accent.copy(alpha = .06f * strength), cornerRadius = radius)
                drawRoundRect(reflection, cornerRadius = radius)
                drawRoundRect(lowerLight, cornerRadius = radius)
                drawContent()
                drawRoundRect(rim, topLeft = Offset(inset, inset),
                    size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((radius.x - inset).coerceAtLeast(0f)), style = Stroke(stroke))
                // The second, inset rim makes the surface read as curved glass rather than an outline.
                val inner = 2.dp.toPx()
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f * strength), Color.Transparent)),
                    topLeft = Offset(inner, inner), size = Size((size.width - inner * 2).coerceAtLeast(0f), (size.height - inner * 2).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((radius.x - inner).coerceAtLeast(0f)), style = Stroke(.5.dp.toPx()))
                if (settings && focused) drawRoundRect(accent, topLeft = Offset(inset, inset),
                    size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((radius.x - inset).coerceAtLeast(0f)), style = Stroke(2.dp.toPx()))
            }
        }
}
