package com.torve.desktop.ui.v2.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.v2.ScrollbarAdapter
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.domain.model.ChannelLogoRef
import com.torve.domain.model.LiveTvChannelLogoResolver

private val LiveGlassShape = RoundedCornerShape(24.dp)
private val LiveRowShape = RoundedCornerShape(16.dp)
private val Amber = Color(0xFFFFB23F)

@Composable
internal fun LiveTvGlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(LiveGlassShape)
            .background(Amber.copy(alpha = 0.06f), LiveGlassShape),
        color = Color(0xFF07101D).copy(alpha = 0.66f),
        shape = LiveGlassShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
        shadowElevation = 10.dp,
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.055f),
                        0.20f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.08f),
                    ),
                )
                .padding(18.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun LiveTvSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = focused || hovered
    val bg by animateColorAsState(
        if (active) Color(0xFF0B1424).copy(alpha = 0.74f) else Color(0xFF070E1A).copy(alpha = 0.64f),
        tween(150),
        label = "liveSearchBg",
    )
    val border by animateColorAsState(
        if (focused) colors.accent.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.15f),
        tween(150),
        label = "liveSearchBorder",
    )
    val glow by animateFloatAsState(if (focused) 0.22f else 0f, tween(150), label = "liveSearchGlow")

    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.accent.copy(alpha = glow), RoundedCornerShape(22.dp))
            .padding(1.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(21.dp))
            .hoverable(interactionSource)
            .semantics { contentDescription = "Search channels" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(20.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interactionSource,
                cursorBrush = SolidColor(colors.accent),
                textStyle = TextStyle(
                    color = Color.White.copy(alpha = 0.96f),
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontFamily = MaterialTheme.typography.bodyMedium.fontFamily,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                "Search channels",
                                color = Color.White.copy(alpha = 0.74f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
internal fun LiveTvTabPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
) {
    LiveTvGlassButton(
        text = text,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        height = 42.dp,
        horizontalPadding = 15.dp,
        contentDescription = contentDescription,
    )
}

@Composable
internal fun LiveTvGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    primary: Boolean = false,
    height: Dp = 40.dp,
    horizontalPadding: Dp = 14.dp,
    contentDescription: String = text,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = (focused || hovered) && enabled
    val scale by animateFloatAsState(if (active) 1.028f else 1f, tween(140), label = "liveButtonScale")
    val background by animateColorAsState(
        when {
            !enabled -> Color(0xFF07101D).copy(alpha = 0.22f)
            primary -> colors.accent.copy(alpha = if (active) 0.25f else 0.18f)
            selected -> colors.accent.copy(alpha = 0.14f)
            active -> Color(0xFF101A2D).copy(alpha = 0.68f)
            else -> Color(0xFF07101D).copy(alpha = 0.48f)
        },
        tween(140),
        label = "liveButtonBg",
    )
    val border by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.04f)
            primary -> colors.accent.copy(alpha = if (active) 0.78f else 0.58f)
            selected -> colors.accent.copy(alpha = 0.58f)
            active -> colors.accent.copy(alpha = 0.62f)
            else -> Color.White.copy(alpha = 0.12f)
        },
        tween(140),
        label = "liveButtonBorder",
    )
    val contentColor = when {
        !enabled -> Color.White.copy(alpha = 0.42f)
        primary || selected -> Color(0xFFFFE4A8)
        else -> Color.White.copy(alpha = 0.91f)
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(CircleShape)
            .hoverable(interactionSource, enabled)
            .focusable(enabled, interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        color = background,
        shape = CircleShape,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .defaultMinSize(minWidth = 40.dp)
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (primary || selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LiveTvStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF07101D).copy(alpha = 0.58f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF70E1A0)),
                )
            }
            Text(
                text,
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun LiveTvChannelLogo(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
) {
    val logo = remember(name, logoUrl) {
        LiveTvChannelLogoResolver.resolveLogo(
            channelName = name,
            playlistLogoUrl = logoUrl,
        )
    }
    LiveTvChannelLogo(
        logo = logo,
        channelName = name,
        modifier = modifier,
    )
}

@Composable
internal fun LiveTvChannelLogo(
    logo: ChannelLogoRef,
    channelName: String,
    modifier: Modifier = Modifier,
    maxLogoWidth: Dp = 40.dp,
    maxLogoHeight: Dp = 32.dp,
) {
    val bitmap = rememberCachedBitmap(logo.url)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$channelName logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.sizeIn(maxWidth = maxLogoWidth, maxHeight = maxLogoHeight),
            )
        } else {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 34.dp, minHeight = 34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    logo.fallbackText,
                    color = Color.White.copy(alpha = 0.74f),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun LiveTvQualityBadge(label: String) {
    Surface(
        color = Color.White.copy(alpha = 0.07f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = Color.White.copy(alpha = 0.74f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun LiveTvPanelTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.97f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun liveTvScrollbarStyle(thickness: Dp = 5.dp): ScrollbarStyle {
    val colors = TorveDesktopThemeTokens.colors
    return LocalScrollbarStyle.current.copy(
        minimalHeight = 34.dp,
        thickness = thickness,
        shape = CircleShape,
        hoverDurationMillis = 180,
        unhoverColor = Color.White.copy(alpha = 0.18f),
        hoverColor = colors.accent.copy(alpha = 0.58f),
    )
}

@Composable
internal fun PremiumVerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        adapter = adapter,
        style = liveTvScrollbarStyle(),
        modifier = modifier.width(5.dp).fillMaxHeight(),
    )
}

@Composable
internal fun PremiumHorizontalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
) {
    HorizontalScrollbar(
        adapter = adapter,
        style = liveTvScrollbarStyle(),
        modifier = modifier.height(5.dp),
    )
}

internal fun qualityBadgesFor(channelName: String): List<String> {
    val upper = channelName.uppercase()
    return buildList {
        if ("4K" in upper) add("4K")
        if ("UHD" in upper && "4K" !in this) add("UHD")
        if ("FHD" in upper || "1080P" in upper || "1080" in upper) add("FHD")
        if ("720P" in upper || "720" in upper) add("720P")
        if ("HD" in upper && none { it == "FHD" || it == "4K" || it == "UHD" }) add("HD")
        if ("LOW BIT" in upper || "LOWBIT" in upper) add("LOW BIT")
        if ("RAW" in upper) add("RAW")
    }.take(3)
}

@Composable
internal fun LiveTvPlayButton(
    text: String = "Play",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
) {
    LiveTvGlassButton(
        text = text,
        icon = Icons.Filled.PlayArrow,
        primary = true,
        onClick = onClick,
        modifier = modifier,
        contentDescription = contentDescription,
    )
}

@Composable
internal fun LiveTvFavoriteMark(isFavorite: Boolean, modifier: Modifier = Modifier) {
    if (!isFavorite) return
    Icon(
        Icons.Filled.Star,
        contentDescription = "Favorite channel",
        tint = Amber.copy(alpha = 0.92f),
        modifier = modifier.size(18.dp),
    )
}

@Composable
internal fun LiveTvFallbackIcon(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Tv,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.62f),
        modifier = modifier.size(18.dp),
    )
}
