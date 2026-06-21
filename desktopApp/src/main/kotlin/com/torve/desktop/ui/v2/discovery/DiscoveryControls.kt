package com.torve.desktop.ui.v2.discovery

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.rememberCachedBitmap

enum class DiscoveryDropdownKey {
    ContentType,
    Provider,
    Network,
    Studio,
    Year,
    Runtime,
    Status,
    Rating,
    RatingSource,
    Language,
    Sort,
}

data class DiscoveryDropdownOption(
    val label: String,
    val onClick: () -> Unit,
)

data class DiscoveryDropdownUiModel(
    val key: DiscoveryDropdownKey,
    val label: String,
    val value: String,
    val options: List<DiscoveryDropdownOption> = emptyList(),
    val onClick: (() -> Unit)? = null,
)

data class BrandFilterUiModel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
)

data class GenreFilterUiModel(
    val id: String,
    val name: String,
)

data class MoodFilterUiModel(
    val id: String,
    val label: String,
)

@Composable
fun DiscoveryControls(
    config: DiscoveryFilterConfig,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isGeminiReady: Boolean = false,
    isAiSearchEnabled: Boolean = false,
    isAiSearchActive: Boolean = false,
    aiLoading: Boolean = false,
    onSearchSubmit: (() -> Unit)? = null,
    onAiSearchClick: () -> Unit = {},
    filtersExpanded: Boolean = true,
    onFiltersClick: () -> Unit = {},
    activeFilterCount: Int = 0,
    onResetClick: () -> Unit = {},
    canReset: Boolean = false,
    dropdowns: List<DiscoveryDropdownUiModel> = emptyList(),
    providers: List<BrandFilterUiModel> = emptyList(),
    networks: List<BrandFilterUiModel> = emptyList(),
    genres: List<GenreFilterUiModel> = emptyList(),
    moods: List<MoodFilterUiModel> = emptyList(),
    selectedProviderIds: Set<String> = emptySet(),
    selectedNetworkIds: Set<String> = emptySet(),
    selectedGenreIds: Set<String> = emptySet(),
    selectedMoodIds: Set<String> = emptySet(),
    onProviderClick: (BrandFilterUiModel) -> Unit = {},
    onNetworkClick: (BrandFilterUiModel) -> Unit = {},
    onGenreClick: (GenreFilterUiModel) -> Unit = {},
    onMoodClick: (MoodFilterUiModel) -> Unit = {},
) {
    AdaptiveHeroControlBackdrop(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        val allowedDropdowns = dropdowns.filter { config.allows(it.key) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CinematicSearchBar(
                value = query,
                onValueChange = onQueryChange,
                placeholder = config.searchPlaceholder,
                showGeminiReady = config.showGeminiReady && isGeminiReady,
                onSubmit = onSearchSubmit,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (config.showAiSearch) {
                CinematicGlassPill(
                    text = if (aiLoading) "Asking" else "AI Search",
                    icon = Icons.Filled.AutoAwesome,
                    selected = isAiSearchActive,
                    enabled = true,
                    onClick = onAiSearchClick,
                    contentDescription = if (isAiSearchActive) "AI Search active" else "AI Search",
                )
            }
            CinematicGlassPill(
                text = if (!filtersExpanded && activeFilterCount > 0) "Filters $activeFilterCount" else "Filters",
                icon = Icons.Filled.Tune,
                selected = filtersExpanded,
                onClick = onFiltersClick,
                contentDescription = if (filtersExpanded) "Collapse filters" else "Expand filters",
            )
            if (!filtersExpanded) {
                allowedDropdowns
                    .filter {
                        it.key == DiscoveryDropdownKey.RatingSource ||
                            it.key == DiscoveryDropdownKey.Rating ||
                            it.key == DiscoveryDropdownKey.Sort
                    }
                    .take(3)
                    .forEach { dropdown ->
                        FilterDropdownPill(dropdown)
                    }
            }
            if (config.showReset) {
                CinematicGlassPill(
                    text = "Reset",
                    icon = Icons.Filled.Refresh,
                    selected = false,
                    enabled = canReset,
                    quiet = true,
                    onClick = onResetClick,
                    contentDescription = "Reset filters",
                )
            }
        }

        if (!filtersExpanded) return@Column

        if (allowedDropdowns.isNotEmpty()) {
            ScrollableDiscoveryRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(allowedDropdowns, key = { it.key.name }) { dropdown ->
                    FilterDropdownPill(dropdown)
                }
            }
        }

        if (config.showProviderFilter && providers.isNotEmpty()) {
            BrandChipRow(
                items = providers,
                selectedIds = selectedProviderIds,
                chipKind = "Provider",
                onClick = onProviderClick,
            )
        }
        if (config.showNetworkFilter && networks.isNotEmpty()) {
            BrandChipRow(
                items = networks,
                selectedIds = selectedNetworkIds,
                chipKind = "Network",
                onClick = onNetworkClick,
            )
        }
        if (config.showGenreChips && genres.isNotEmpty()) {
            ScrollableDiscoveryRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(genres, key = { it.id }) { genre ->
                    GenreFilterChip(
                        label = genre.name,
                        selected = genre.id in selectedGenreIds,
                        onClick = { onGenreClick(genre) },
                        contentDescription = "Genre ${genre.name}" +
                            if (genre.id in selectedGenreIds) ", selected" else "",
                    )
                }
            }
        }
        if (config.showMoodChips && moods.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "MOOD",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    fontWeight = FontWeight.SemiBold,
                )
                ScrollableDiscoveryRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(moods, key = { it.id }) { mood ->
                        MoodFilterChip(
                            mood = mood,
                            selected = mood.id in selectedMoodIds,
                            onClick = { onMoodClick(mood) },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ScrollableDiscoveryRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    val rowState = rememberLazyListState()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LazyRow(
            state = rowState,
            horizontalArrangement = horizontalArrangement,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(rowState),
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

@Composable
fun AdaptiveHeroControlBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.42f),
                        Color.Black.copy(alpha = 0.22f),
                        Color.Black.copy(alpha = 0.08f),
                    ),
                    radius = 980f,
                ),
            )
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Black.copy(alpha = 0.26f),
                    0.58f to Color.Black.copy(alpha = 0.12f),
                    1.0f to Color.Transparent,
                ),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

@Composable
fun CinematicSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    showGeminiReady: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (focused || hovered) Color(0xFF101827).copy(alpha = 0.70f)
        else Color(0xFF0B1220).copy(alpha = 0.60f),
        animationSpec = tween(160),
        label = "cinematicSearchBg",
    )
    val border by animateColorAsState(
        if (focused) colors.accent.copy(alpha = 0.58f)
        else Color.White.copy(alpha = 0.16f),
        animationSpec = tween(160),
        label = "cinematicSearchBorder",
    )
    val glowAlpha by animateFloatAsState(if (focused) 0.22f else 0f, tween(160), label = "cinematicSearchGlow")
    val keyModifier = if (onSubmit != null) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                onSubmit()
                true
            } else {
                false
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .widthIn(min = 360.dp, max = 660.dp)
            .height(52.dp)
            .background(colors.accent.copy(alpha = glowAlpha), RoundedCornerShape(26.dp))
            .padding(1.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.08f), background, background),
                ),
            )
            .border(BorderStroke(1.dp, border), RoundedCornerShape(24.dp))
            .hoverable(interactionSource)
            .semantics { contentDescription = placeholder },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(19.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontFamily = MaterialTheme.typography.bodyMedium.fontFamily,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(colors.accent),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .then(keyModifier),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                color = Color.White.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (showGeminiReady) {
                GeminiReadyCapsule()
            }
        }
    }
}

@Composable
private fun GeminiReadyCapsule() {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), CircleShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFF66E6A5)),
        )
        Text(
            text = "Gemini ready",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.70f),
            maxLines = 1,
        )
    }
}

@Composable
fun FilterDropdownPill(dropdown: DiscoveryDropdownUiModel) {
    var expanded by remember(dropdown.key) { mutableStateOf(false) }
    Box {
        CinematicGlassPill(
            text = "${dropdown.label}: ${dropdown.value}",
            icon = dropdownIcon(dropdown.key),
            trailingIcon = Icons.Filled.KeyboardArrowDown,
            selected = dropdown.isSelected(),
            height = 40.dp,
            horizontalPadding = 15.dp,
            onClick = {
                if (dropdown.options.isNotEmpty()) expanded = true
                else dropdown.onClick?.invoke()
            },
            contentDescription = "${dropdown.label} filter, ${dropdown.value}",
        )
        if (dropdown.options.isNotEmpty()) {
            TorveDropdownScaffold(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                items = dropdown.options.map { option ->
                    option.label to {
                        expanded = false
                        option.onClick()
                    }
                },
            )
        }
    }
}

private fun DiscoveryDropdownUiModel.isSelected(): Boolean = when (key) {
    DiscoveryDropdownKey.RatingSource -> value != "TMDB"
    else -> value != "Any" && value != "All" && value != "Popular"
}

@Composable
fun CinematicGlassPill(
    text: String,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
    height: Dp = 40.dp,
    horizontalPadding: Dp = 15.dp,
    contentDescription: String = text,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val activeFocus = (hovered || focused) && enabled
    val scale by animateFloatAsState(
        targetValue = if (activeFocus) 1.035f else 1f,
        animationSpec = tween(150),
        label = "glassPillScale",
    )
    val background by animateColorAsState(
        when {
            !enabled -> Color(0xFF0B1220).copy(alpha = 0.20f)
            selected -> colors.accent.copy(alpha = if (quiet) 0.08f else 0.12f)
            activeFocus -> Color(0xFF101827).copy(alpha = 0.66f)
            else -> Color(0xFF0B1220).copy(alpha = if (quiet) 0.42f else 0.54f)
        },
        tween(150),
        label = "glassPillBg",
    )
    val border by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.04f)
            selected -> colors.accent.copy(alpha = 0.58f)
            activeFocus -> colors.accent.copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = if (quiet) 0.12f else 0.16f)
        },
        tween(150),
        label = "glassPillBorder",
    )
    val textColor by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.35f)
            selected -> Color(0xFFFFE3A5)
            else -> Color.White.copy(alpha = if (quiet) 0.82f else 0.94f)
        },
        tween(150),
        label = "glassPillText",
    )
    val iconColor by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.30f)
            selected -> colors.accent.copy(alpha = 0.95f)
            else -> Color.White.copy(alpha = 0.76f)
        },
        tween(150),
        label = "glassPillIcon",
    )
    val glowAlpha by animateFloatAsState(if (activeFocus) 0.18f else 0f, tween(150), label = "glassPillGlow")

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = glowAlpha), CircleShape)
            .hoverable(interactionSource, enabled)
            .focusable(enabled, interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        color = background,
        shape = CircleShape,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .defaultMinSize(minWidth = if (quiet) 0.dp else 40.dp)
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Icon(trailingIcon, null, tint = iconColor.copy(alpha = 0.78f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BrandChipRow(
    items: List<BrandFilterUiModel>,
    selectedIds: Set<String>,
    chipKind: String,
    onClick: (BrandFilterUiModel) -> Unit,
) {
    ScrollableDiscoveryRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items, key = { it.id }) { item ->
            BrandFilterChip(
                item = item,
                selected = item.id in selectedIds,
                chipKind = chipKind,
                onClick = { onClick(item) },
            )
        }
    }
}

@Composable
fun BrandFilterChip(
    item: BrandFilterUiModel,
    selected: Boolean,
    chipKind: String,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val activeFocus = hovered || focused
    val scale by animateFloatAsState(if (activeFocus) 1.035f else 1f, tween(150), label = "brandScale")
    val background by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.11f)
            activeFocus -> Color(0xFF101827).copy(alpha = 0.62f)
            else -> Color(0xFF0B1220).copy(alpha = 0.48f)
        },
        tween(150),
        label = "brandBg",
    )
    val border by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.52f)
            activeFocus -> colors.accent.copy(alpha = 0.48f)
            else -> Color.White.copy(alpha = 0.14f)
        },
        tween(150),
        label = "brandBorder",
    )

    Surface(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .hoverable(interactionSource)
            .focusable(true, interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics {
                contentDescription = "$chipKind ${item.name}" + if (selected) ", selected" else ""
            },
        color = background,
        shape = CircleShape,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrandLogo(item.name, item.logoUrl, Modifier.size(24.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color(0xFFFFE3A5) else Color.White.copy(alpha = 0.94f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BrandLogo(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
) {
    val resolvedLogoUrl = remember(logoUrl) {
        resolveBrandLogoUrl(
            absoluteLogoUrl = logoUrl,
            tmdbLogoPath = logoUrl,
            size = "w300",
        )
    }
    val hasLogoSource = !resolvedLogoUrl.isNullOrBlank()
    val bitmap = rememberCachedBitmap(resolvedLogoUrl)

    if (hasLogoSource) {
        Box(
            modifier = modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) return@Box
            Image(
                bitmap = bitmap,
                contentDescription = "$name logo",
                modifier = Modifier.sizeIn(maxWidth = 22.dp, maxHeight = 22.dp),
                contentScale = ContentScale.Fit,
            )
        }
        return
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.74f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GenreFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String = label,
) {
    CinematicGlassPill(
        text = label,
        selected = selected,
        quiet = true,
        height = 38.dp,
        horizontalPadding = 13.dp,
        onClick = onClick,
        contentDescription = contentDescription,
    )
}

@Composable
fun MoodFilterChip(
    mood: MoodFilterUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val activeFocus = hovered || focused
    val scale by animateFloatAsState(
        when {
            activeFocus && selected -> 1.045f
            activeFocus -> 1.04f
            else -> 1f
        },
        tween(150),
        label = "moodScale",
    )
    val background by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.13f)
            activeFocus -> Color(0xFF101827).copy(alpha = 0.64f)
            else -> Color(0xFF0B1220).copy(alpha = 0.48f)
        },
        tween(150),
        label = "moodBg",
    )
    val border by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.64f)
            activeFocus -> colors.accent.copy(alpha = 0.58f)
            else -> Color.White.copy(alpha = 0.14f)
        },
        tween(150),
        label = "moodBorder",
    )
    val glowAlpha by animateFloatAsState(if (activeFocus || selected) 0.16f else 0f, tween(150), label = "moodGlow")
    val iconColor = if (selected) colors.accent.copy(alpha = 0.95f) else Color(0xFFFFF4D8).copy(alpha = 0.78f)
    val textColor = if (selected) Color(0xFFFFE9B9) else Color(0xFFFFF8EA).copy(alpha = 0.94f)

    Surface(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = glowAlpha), CircleShape)
            .hoverable(interactionSource)
            .focusable(true, interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics {
                contentDescription = "Mood ${mood.label}" + if (selected) ", selected" else ""
            },
        color = background,
        shape = CircleShape,
        border = BorderStroke(1.dp, border),
    ) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = if (selected || activeFocus) 0.16f else 0.08f)),
            )
            Row(
                modifier = Modifier.height(44.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(moodIcon(mood.id), null, tint = iconColor, modifier = Modifier.size(18.dp))
                Text(
                    mood.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun DiscoveryFilterConfig.allows(key: DiscoveryDropdownKey): Boolean = when (key) {
    DiscoveryDropdownKey.ContentType -> contentType == ContentDiscoveryType.Mixed
    DiscoveryDropdownKey.Provider -> showProviderFilter
    DiscoveryDropdownKey.Network -> showNetworkFilter
    DiscoveryDropdownKey.Studio -> showStudioFilter
    DiscoveryDropdownKey.Year -> showYearFilter
    DiscoveryDropdownKey.Runtime -> showRuntimeFilter
    DiscoveryDropdownKey.Status -> showStatusFilter
    DiscoveryDropdownKey.Rating -> showRatingFilter
    DiscoveryDropdownKey.RatingSource -> showRatingFilter
    DiscoveryDropdownKey.Language -> showLanguageFilter
    DiscoveryDropdownKey.Sort -> showSortFilter
}

private fun dropdownIcon(key: DiscoveryDropdownKey): ImageVector = when (key) {
    DiscoveryDropdownKey.ContentType -> Icons.Filled.FilterList
    DiscoveryDropdownKey.Provider -> Icons.Filled.Movie
    DiscoveryDropdownKey.Network -> Icons.Filled.Tv
    DiscoveryDropdownKey.Studio -> Icons.Filled.LocalMovies
    DiscoveryDropdownKey.Year -> Icons.Filled.CalendarToday
    DiscoveryDropdownKey.Runtime -> Icons.Filled.AccessTime
    DiscoveryDropdownKey.Status -> Icons.Filled.PlayCircle
    DiscoveryDropdownKey.Rating -> Icons.Filled.Star
    DiscoveryDropdownKey.RatingSource -> Icons.Filled.Star
    DiscoveryDropdownKey.Language -> Icons.Filled.Language
    DiscoveryDropdownKey.Sort -> Icons.Filled.Sort
}

private fun moodIcon(id: String): ImageVector = when (id) {
    "all" -> Icons.Filled.AutoAwesome
    "dark" -> Icons.Filled.NightsStay
    "funny" -> Icons.Filled.EmojiEmotions
    "cinematic" -> Icons.Filled.LocalMovies
    "fast" -> Icons.Filled.Bolt
    "comfort", "easy" -> Icons.Filled.PlayCircle
    "acclaimed" -> Icons.Filled.Star
    "hidden" -> Icons.Filled.Diamond
    "blockbuster" -> Icons.Filled.Movie
    "date" -> Icons.Filled.Favorite
    "mind" -> Icons.Filled.AutoAwesome
    "family" -> Icons.Filled.Home
    else -> Icons.Filled.AutoAwesome
}
