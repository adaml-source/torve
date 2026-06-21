package com.torve.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

@Composable
fun TorveFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val container by animateColorAsState(
        if (selected) colors.accentContainerStrong.copy(alpha = 0.86f)
        else if (hovered) colors.accentContainer.copy(alpha = 0.30f)
        else colors.fieldSurface.copy(alpha = 0.82f),
        label = "filterChipContainer",
    )
    val border by animateColorAsState(
        if (selected) colors.accent
        else if (hovered) colors.accent.copy(alpha = 0.72f)
        else colors.borderSubtle.copy(alpha = 0.72f),
        label = "filterChipBorder",
    )
    val textColor by animateColorAsState(
        if (selected || hovered) colors.textPrimary else colors.textSecondary,
        label = "filterChipText",
    )
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = container,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
fun TorvePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = TorveDesktopThemeTokens.colors
    TorveActionButtonSurface(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = colors.accent,
        hoveredContainerColor = colors.accent.copy(alpha = 0.92f),
        disabledContainerColor = colors.borderSubtle,
        contentColor = Color(0xFF090A10),
        disabledContentColor = colors.textDisabled,
    )
}

@Composable
fun TorveSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = TorveDesktopThemeTokens.colors
    TorveActionButtonSurface(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = colors.accentContainer,
        hoveredContainerColor = colors.accentContainerStrong.copy(alpha = 0.96f),
        disabledContainerColor = colors.borderSubtle,
        contentColor = colors.textPrimary,
        disabledContentColor = colors.textDisabled,
        borderColor = colors.accentContainerStrong.copy(alpha = 0.72f),
        hoveredBorderColor = colors.accent,
    )
}

@Composable
fun TorveGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = TorveDesktopThemeTokens.colors
    TorveActionButtonSurface(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = colors.fieldSurface.copy(alpha = 0.76f),
        hoveredContainerColor = colors.fieldSurface.copy(alpha = 0.94f),
        disabledContainerColor = colors.borderSubtle.copy(alpha = 0.4f),
        contentColor = colors.textPrimary,
        disabledContentColor = colors.textDisabled,
        borderColor = colors.borderSubtle.copy(alpha = 0.62f),
        hoveredBorderColor = colors.borderStrong,
    )
}

@Composable
fun TorveIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val container by animateColorAsState(
        if (hovered) colors.fieldSurface.copy(alpha = 0.72f) else colors.fieldSurface.copy(alpha = 0.8f),
        label = "iconButtonContainer",
    )
    val border by animateColorAsState(
        if (hovered) colors.accent else colors.borderSubtle.copy(alpha = 0.55f),
        label = "iconButtonBorder",
    )
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        color = container,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(if (hovered) 1.5.dp else 1.dp, border),
    ) {
        Box(
            modifier = Modifier.padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
            Text(
                text = label,
                color = Color.Transparent,
                modifier = Modifier.size(0.dp),
            )
        }
    }
}

@Composable
private fun TorveActionButtonSurface(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    containerColor: Color,
    hoveredContainerColor: Color,
    disabledContainerColor: Color,
    contentColor: Color,
    disabledContentColor: Color,
    borderColor: Color = Color.Transparent,
    hoveredBorderColor: Color = borderColor,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            !enabled -> disabledContainerColor
            hovered -> hoveredContainerColor
            else -> containerColor
        },
        label = "actionButtonContainer",
    )
    val border by animateColorAsState(
        when {
            !enabled -> Color.Transparent
            hovered -> hoveredBorderColor
            else -> borderColor
        },
        label = "actionButtonBorder",
    )
    val textColor by animateColorAsState(
        if (enabled) contentColor else disabledContentColor,
        label = "actionButtonText",
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(radii.md))
            .hoverable(interactionSource = interactionSource, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        color = container,
        shape = RoundedCornerShape(radii.md),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (border == Color.Transparent) Color.Transparent else border,
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) textColor else colors.textDisabled,
        )
    }
}

@Composable
fun TorveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onSubmit: (() -> Unit)? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val keyHandlerModifier = if (onSubmit != null) {
        Modifier.onPreviewKeyEvent { ke ->
            if (ke.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                ke.key == androidx.compose.ui.input.key.Key.Enter
            ) {
                onSubmit(); true
            } else false
        }
    } else Modifier
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(keyHandlerModifier),
        enabled = enabled,
        singleLine = singleLine,
        readOnly = readOnly,
        label = { Text(label) },
        placeholder = placeholder?.let { p -> { Text(p) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(radii.md),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.fieldSurface,
            unfocusedContainerColor = colors.fieldSurface,
            disabledContainerColor = colors.sidebarSurface,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textDisabled,
            focusedIndicatorColor = colors.accent,
            unfocusedIndicatorColor = colors.borderStrong,
            disabledIndicatorColor = colors.borderSubtle,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textMuted,
        ),
    )
}

@Composable
fun TorveSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search Torve",
) {
    val colors = TorveDesktopThemeTokens.colors
    TorveTextField(
        value = value,
        onValueChange = onValueChange,
        label = placeholder,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = colors.textMuted,
            )
        },
    )
}

@Composable
fun TorvePill(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = TorveDesktopThemeTokens.colors.fieldSurface,
    contentColor: Color = TorveDesktopThemeTokens.colors.textSecondary,
) {
    val radii = TorveDesktopThemeTokens.radii
    Surface(
        modifier = modifier,
        color = backgroundColor,
        shape = RoundedCornerShape(radii.sm),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
fun TorveBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: TorveBadgeTone = TorveBadgeTone.Neutral,
) {
    val colors = TorveDesktopThemeTokens.colors
    val (container, content) = when (tone) {
        TorveBadgeTone.Neutral -> colors.fieldSurface to colors.textSecondary
        TorveBadgeTone.Accent -> colors.accentContainer to colors.textPrimary
        TorveBadgeTone.Success -> colors.success.copy(alpha = 0.18f) to colors.success
        TorveBadgeTone.Warning -> colors.warning.copy(alpha = 0.18f) to colors.warning
        TorveBadgeTone.Error -> colors.error.copy(alpha = 0.18f) to colors.error
        TorveBadgeTone.Live -> colors.live.copy(alpha = 0.18f) to colors.live
    }
    TorvePill(
        text = text,
        modifier = modifier,
        backgroundColor = container,
        contentColor = content,
    )
}

enum class TorveBadgeTone {
    Neutral,
    Accent,
    Success,
    Warning,
    Error,
    Live,
}

@Composable
fun TorveCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(radii.lg),
        colors = CardDefaults.cardColors(
            containerColor = if (accent) colors.accentContainer else colors.cardSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (accent) colors.accentContainerStrong else colors.borderSubtle,
        ),
    ) {
        content()
    }
}

@Composable
fun TorveSectionCard(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val spacing = TorveDesktopThemeTokens.spacing
    TorveCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.sm.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    supportingText?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = TorveDesktopThemeTokens.colors.textSecondary,
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun TorveListRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val background = if (selected) colors.accentContainer else Color.Transparent
    val border = if (selected) colors.accent else colors.borderSubtle
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(radii.md))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .border(1.dp, border, RoundedCornerShape(radii.md)),
        color = background,
        shape = RoundedCornerShape(radii.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.size(12.dp))
                trailing()
            }
        }
    }
}

@Composable
fun TorveBanner(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    tone: TorveBannerTone = TorveBannerTone.Info,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val (container, border, titleColor) = when (tone) {
        TorveBannerTone.Info -> Triple(colors.fieldSurface, colors.borderStrong, colors.textPrimary)
        TorveBannerTone.Success -> Triple(colors.success.copy(alpha = 0.14f), colors.success.copy(alpha = 0.4f), colors.success)
        TorveBannerTone.Warning -> Triple(colors.warning.copy(alpha = 0.14f), colors.warning.copy(alpha = 0.4f), colors.warning)
        TorveBannerTone.Error -> Triple(colors.error.copy(alpha = 0.14f), colors.error.copy(alpha = 0.4f), colors.error)
    }
    Surface(
        modifier = modifier.border(1.dp, border, RoundedCornerShape(radii.lg)),
        color = container,
        shape = RoundedCornerShape(radii.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
    }
}

enum class TorveBannerTone {
    Info,
    Success,
    Warning,
    Error,
}

@Composable
fun TorvePageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun TorveSidebarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val background = if (selected) colors.accentContainer else Color.Transparent
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.md))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        color = background,
        shape = RoundedCornerShape(radii.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            badge?.let {
                TorveBadge(
                    text = it,
                    tone = if (selected) TorveBadgeTone.Accent else TorveBadgeTone.Neutral,
                )
            }
        }
    }
}

@Composable
fun TorveDropdownScaffold(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<Pair<String, () -> Unit>>,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = TorveDesktopThemeTokens.colors.drawerSurface,
    ) {
        items.forEach { (label, action) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = action,
            )
        }
    }
}

@Composable
fun TorvePlaceholderState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    if (emoji == null && action == null) {
        // Backward-compatible thin form. Existing call sites unchanged.
        TorveBanner(
            title = title,
            description = description,
            modifier = modifier,
            tone = TorveBannerTone.Info,
        )
        return
    }
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    Surface(
        modifier = modifier
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(radii.lg)),
        color = colors.cardSurface,
        shape = RoundedCornerShape(radii.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            emoji?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

@Composable
fun TorveToastHost(
    modifier: Modifier = Modifier,
    messages: List<String> = emptyList(),
) {
    if (messages.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        messages.takeLast(2).forEach { message ->
            TorveBanner(
                title = "Notice",
                description = message,
                tone = TorveBannerTone.Info,
            )
        }
    }
}
