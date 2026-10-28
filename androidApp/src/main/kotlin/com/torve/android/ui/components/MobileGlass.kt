package com.torve.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Subdued layered glass, without a blur pass or an opaque platform button fill. */
@Composable
fun Modifier.mobileGlassSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    selected: Boolean = false,
    enabled: Boolean = true,
    pressed: Boolean = false,
    quiet: Boolean = false,
    tint: Color = Color.Transparent,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val strength = if (enabled) 1f else .45f
    val presence = if (quiet) .55f else 1f
    val scale by animateFloatAsState(if (pressed) .985f else 1f, tween(100), label = "phoneGlassPress")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(if (quiet) 1.dp else 3.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color.Black.copy(alpha = .16f))
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val body = scheme.surface.copy(alpha = (if (dark) .48f else .82f) * strength)
            val sheen = Brush.verticalGradient(listOf(
                Color.White.copy(alpha = (if (dark) .13f else .50f) * presence * strength),
                Color.White.copy(alpha = .025f * presence * strength),
                scheme.onSurface.copy(alpha = .025f * presence * strength)))
            val rim = Brush.linearGradient(listOf(
                (if (dark) Color.White else scheme.onSurface).copy(alpha = (if (dark) .28f else .13f) * presence * strength),
                scheme.onSurface.copy(alpha = .045f * strength),
                (if (selected) scheme.primary else scheme.onSurface).copy(alpha = (if (selected) .36f else .10f) * strength)),
                start = Offset.Zero, end = Offset(size.width, size.height))
            onDrawWithContent {
                drawOutline(outline, body)
                if (tint != Color.Transparent) drawOutline(outline, tint.copy(alpha = tint.alpha.coerceAtMost(.16f) * strength))
                if (selected) drawOutline(outline, scheme.primary.copy(alpha = .08f * strength))
                drawOutline(outline, sheen)
                drawContent()
                drawOutline(outline, rim, style = Stroke(1.dp.toPx()))
            }
        }
}

@Composable
fun MobileGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    accented: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Solid-button callers may still request onPrimary (black). Glass needs onSurface contrast.
    val surface = MaterialTheme.colorScheme.surface
    val foreground = readableGlassContent(colors.contentColor, surface, MaterialTheme.colorScheme.onSurface)
    val disabledForeground = MaterialTheme.colorScheme.onSurface.copy(alpha = .50f)
    Button(onClick, modifier.heightIn(min = 48.dp).mobileGlassSurface(shape, accented, enabled, pressed,
        tint = if (accented) MaterialTheme.colorScheme.primary else Color.Transparent),
        enabled, shape, ButtonDefaults.buttonColors(containerColor = Color.Transparent,
            contentColor = foreground, disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledForeground),
        elevation, border, contentPadding, source, content)
}

@Composable
fun MobileGlassOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MobileGlassButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content = content)

@Composable
fun MobileGlassIconButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .mobileGlassSurface(CircleShape, enabled = enabled, pressed = pressed), enabled,
        IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = colors.contentColor,
            disabledContainerColor = Color.Transparent, disabledContentColor = colors.disabledContentColor),
        source, content)
}

@Composable
fun MobileGlassCard(
    modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(20.dp),
    colors: CardColors = CardDefaults.cardColors(), elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.mobileGlassSurface(shape, quiet = true, tint = colors.containerColor), shape,
        CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = colors.contentColor),
        elevation, border, content)
}

/** Keep explicit accent colors when readable, repairing old solid-button foregrounds. */
internal fun readableGlassContent(requested: Color, surface: Color, fallback: Color): Color {
    val rendered = requested.compositeOver(surface).luminance()
    val background = surface.luminance()
    val contrast = (maxOf(rendered, background) + .05f) / (minOf(rendered, background) + .05f)
    return if (contrast >= 4.5f) requested else fallback
}
