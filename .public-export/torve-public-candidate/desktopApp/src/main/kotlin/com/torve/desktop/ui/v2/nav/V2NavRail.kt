package com.torve.desktop.ui.v2.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * @param backdropLuminance 0.0 (black) .. 1.0 (white) estimated brightness of the
 *   hero art behind the rail. Drives adaptive scrim opacity and icon contrast.
 *   Default 0.15 = typical dark hero.
 */
@Composable
fun V2NavRail(
    currentDestination: V2Destination,
    onSelectDestination: (V2Destination) -> Unit,
    onOpenSettings: () -> Unit,
    isSettingsActive: Boolean = false,
    backdropLuminance: Float = 0.15f,
    adultModeEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val appIcon = remember { loadAppIcon() }

    // ── Adaptive brightness response ──
    // Non-linear curve: gentle on dark heroes, steeper ramp above 0.35 luminance.
    // Dark hero (0.1) → 0.42, mid (0.35) → 0.52, bright (0.6+) → 0.80-0.92
    val brightBoost = if (backdropLuminance > 0.35f) {
        // Quadratic ramp for bright heroes: kicks in harder above threshold
        val excess = (backdropLuminance - 0.35f) / 0.65f // 0..1 range for bright zone
        excess * excess * 0.50f // up to +0.50 for extreme brightness
    } else 0f

    val scrimAlpha by animateFloatAsState(
        targetValue = (0.42f + (backdropLuminance * 0.25f) + brightBoost).coerceIn(0.38f, 0.92f),
        animationSpec = tween(durationMillis = 500),
        label = "railScrim",
    )

    // Scrim edge: how far the gradient extends. On bright heroes the second stop
    // retains more opacity so the rail area has real backing, not a thin fade.
    val edgeAlpha by animateFloatAsState(
        targetValue = (scrimAlpha * 0.30f + brightBoost * 0.35f).coerceIn(0.08f, 0.45f),
        animationSpec = tween(durationMillis = 500),
        label = "railEdge",
    )

    // Unselected icon tint: brighter on bright backdrops for contrast against darker scrim.
    val iconAlpha by animateFloatAsState(
        targetValue = (0.75f + (backdropLuminance * 0.20f)).coerceIn(0.72f, 0.95f),
        animationSpec = tween(durationMillis = 500),
        label = "railIcon",
    )

    // Selected pill opacity: stronger on bright heroes so the accent reads clearly.
    val selectedPillAlpha by animateFloatAsState(
        targetValue = if (backdropLuminance > 0.35f) 1.0f else 0.85f,
        animationSpec = tween(durationMillis = 500),
        label = "railPill",
    )

    // App icon ring: subtle dark ring behind the icon on bright heroes for grounding.
    val iconRingAlpha by animateFloatAsState(
        targetValue = if (backdropLuminance > 0.30f) (0.3f + brightBoost * 0.6f).coerceAtMost(0.7f) else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "iconRing",
    )

    Box(
        modifier = modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        colors.shellBackground.copy(alpha = scrimAlpha),
                        colors.shellBackground.copy(alpha = edgeAlpha),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(start = 6.dp, top = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App icon with adaptive grounding ring on bright heroes
            Box(contentAlignment = Alignment.Center) {
                // Dark backing ring - only visible on bright heroes
                if (iconRingAlpha > 0.01f) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .background(colors.shellBackground.copy(alpha = iconRingAlpha)),
                    )
                }
                if (appIcon != null) {
                    Image(appIcon, "Torve", Modifier.size(30.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Surface(Modifier.size(30.dp), color = colors.accent, shape = CircleShape) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("T", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.Black)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            V2Destination.entries
                .filter { it != V2Destination.ADULT || adultModeEnabled }
                .forEach { dest ->
                    NavIcon(
                        icon = dest.icon,
                        selected = dest == currentDestination && !isSettingsActive,
                        unselectedAlpha = iconAlpha,
                        selectedPillAlpha = selectedPillAlpha,
                        onClick = { onSelectDestination(dest) },
                    )
                    Spacer(Modifier.height(2.dp))
                }

            Spacer(Modifier.weight(1f))

            NavIcon(
                icon = Icons.Filled.Settings,
                selected = isSettingsActive,
                unselectedAlpha = iconAlpha,
                selectedPillAlpha = selectedPillAlpha,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    selected: Boolean,
    unselectedAlpha: Float = 0.75f,
    selectedPillAlpha: Float = 0.85f,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val containerAlpha by animateFloatAsState(
        targetValue = when {
            selected -> selectedPillAlpha
            hovered -> 0.18f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 140),
        label = "navHoverAlpha",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (hovered || selected) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "navBorderAlpha",
    )
    Surface(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = if (selected) colors.accentContainer.copy(alpha = containerAlpha) else colors.fieldSurface.copy(alpha = containerAlpha),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(
            if (hovered) 1.5.dp else 1.dp,
            colors.accent.copy(alpha = borderAlpha),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon, null, Modifier.size(18.dp),
                tint = if (selected) colors.textPrimary else Color.White.copy(alpha = unselectedAlpha),
            )
        }
    }
}

private fun loadAppIcon(): ImageBitmap? = runCatching {
    Thread.currentThread().contextClassLoader.getResourceAsStream("torve_icon.png")?.readBytes()
        ?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
}.getOrNull()
