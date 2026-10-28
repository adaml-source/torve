package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.tv.TV_NAV_RAIL_WIDTH
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.TvTopDestination
import com.torve.android.tv.settings.rememberTvReduceMotionPreference
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Snow
import kotlinx.coroutines.delay

private val RAIL_ITEM_WIDTH = 38.dp
private val RAIL_EXPANDED_ITEM_WIDTH = TV_NAV_RAIL_WIDTH
private val RAIL_ICON_CENTER = 22.dp
private val RAIL_ITEM_HEIGHT = 36.dp
private val RAIL_FOCUSED_EXTRA_HEIGHT = 20.dp
private val RAIL_VERSION_SPACE = 24.dp
private val RAIL_LABEL_HORIZONTAL_PADDING = 5.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvNavRail(
    destinations: List<TvTopDestination>,
    enabledRoutes: Set<String> = destinations.mapTo(linkedSetOf()) { it.route },
    selectedRoute: String,
    activeRoute: String = selectedRoute,
    isExpanded: Boolean,
    railFocusRequester: FocusRequester,
    preferredEntryRoute: String? = null,
    preferredEntryRequestNonce: Int = 0,
    onRailFocusChanged: (Boolean) -> Unit,
    onPreferredEntryRouteConsumed: () -> Unit = {},
    onMoveToContent: (String) -> Unit,
    onConfirm: (String) -> Unit = {},
    onHighlight: (String) -> Unit = {},
    onNavigate: (String) -> Unit,
    navigateOnFocus: Boolean = false,
    settingsNeedAttention: Boolean = false,
    isVisible: Boolean = true,
    isFocusEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Keep requesters stable by route when optional entries are removed or restored.
    val itemRequesters = remember { mutableMapOf<String, FocusRequester>() }
    destinations.forEach { dest -> itemRequesters.getOrPut(dest.route) { FocusRequester() } }
    val visibleDestinations = destinations.filter { it.route in enabledRoutes }
    val availableRoutes = visibleDestinations.map { it.route }

    fun neighborRoute(route: String, delta: Int): String? {
        val size = availableRoutes.size
        if (size <= 0) return null
        val index = availableRoutes.indexOf(route).takeIf { it >= 0 } ?: 0
        val neighborIndex = ((index + delta) % size + size) % size
        return availableRoutes[neighborIndex]
    }

    val currentActiveRoute by rememberUpdatedState(activeRoute)
    val currentSelectedRoute by rememberUpdatedState(selectedRoute)
    val currentPreferredEntryRoute by rememberUpdatedState(preferredEntryRoute)
    val currentEnabledRoutes by rememberUpdatedState(enabledRoutes)
    var railHasFocus by remember { mutableStateOf(false) }
    // Reserve label space only at the last focused destination, including during
    // content handoff, so the compact group does not jump when focus leaves it.
    var lastFocusedRoute by remember { mutableStateOf(preferredEntryRoute ?: activeRoute) }
    val labelSlotRoute = (if (railHasFocus) lastFocusedRoute else selectedRoute).takeIf { it in enabledRoutes }
        ?: selectedRoute.takeIf { it in enabledRoutes }
        ?: availableRoutes.firstOrNull()
    val reduceMotion = rememberTvReduceMotionPreference()
    val menuAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(if (reduceMotion || !isFocusEnabled) 0 else 160),
        label = "mainMenuAlpha",
    )
    val menuOffset by animateDpAsState(
        targetValue = if (isVisible) 0.dp else -TV_NAV_RAIL_WIDTH,
        animationSpec = tween(if (reduceMotion || !isFocusEnabled) 0 else 160),
        label = "mainMenuOffset",
    )

    BoxWithConstraints(
        modifier = modifier
            .offset(x = menuOffset)
            .alpha(menuAlpha)
            // Every bubble fits inside the existing inset, including while focused.
            .width(RAIL_EXPANDED_ITEM_WIDTH)
            .fillMaxHeight()
            .padding(top = 14.dp, bottom = 14.dp)
            .zIndex(4f),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Explicit bounds let the menu wrap and center when fewer destinations are enabled.
        // Weighted children make a Column fill the screen even with fill=false.
        val rowHeight = if (visibleDestinations.isEmpty()) RAIL_ITEM_HEIGHT else
            ((maxHeight - RAIL_VERSION_SPACE - 16.dp - RAIL_FOCUSED_EXTRA_HEIGHT -
                4.dp * (visibleDestinations.size - 1)) / visibleDestinations.size)
                .coerceIn(1.dp, RAIL_ITEM_HEIGHT)
        Column(
            modifier = Modifier
                .padding(bottom = RAIL_VERSION_SPACE)
                .width(RAIL_EXPANDED_ITEM_WIDTH)
                .testTag("tv_nav_rail_panel")
                .focusRequester(railFocusRequester)
                .onFocusChanged {
                    railHasFocus = it.hasFocus
                    onRailFocusChanged(it.hasFocus)
                }
                .focusProperties {
                    enter = enter@{ direction ->
                        // Left or explicit Back restoration enters the icons.
                        // A spatial Up/Down search must stay inside page content.
                        if (!isFocusEnabled || (!railHasFocus &&
                                direction != FocusDirection.Left && direction != FocusDirection.Enter)) {
                            return@enter FocusRequester.Cancel
                        }
                        currentPreferredEntryRoute
                            ?.takeIf { it in currentEnabledRoutes }
                            ?.let(itemRequesters::get)
                            ?: currentActiveRoute.takeIf { it in currentEnabledRoutes }?.let(itemRequesters::get)
                            ?: currentSelectedRoute.takeIf { it in currentEnabledRoutes }?.let(itemRequesters::get)
                            ?: FocusRequester.Default
                    }
                }
                .focusGroup()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleDestinations.forEach { destination ->
                key(destination.route) {
                    val enabled = destination.route in enabledRoutes
                    val currentRequester = itemRequesters.getValue(destination.route)
                    val upRequester = neighborRoute(destination.route, -1)
                        ?.let { itemRequesters[it] }
                        ?: currentRequester
                    val downRequester = neighborRoute(destination.route, 1)
                        ?.let { itemRequesters[it] }
                        ?: currentRequester
                    val isCurrentRoute = selectedRoute == destination.route
                    val itemHeight by animateDpAsState(
                        targetValue = rowHeight + if (destination.route == labelSlotRoute) RAIL_FOCUSED_EXTRA_HEIGHT else 0.dp,
                        animationSpec = tween(if (reduceMotion) 0 else 180),
                        label = "railItemHeight",
                    )
                    TvNavRailItem(
                        destination = destination,
                        selected = isCurrentRoute,
                        enabled = enabled,
                        needsAttention = settingsNeedAttention && destination.route == TvRoutes.SETTINGS,
                        modifier = Modifier
                            .height(itemHeight)
                            .focusRequester(currentRequester)
                            .alpha(if (enabled) 1f else 0f)
                            .focusProperties {
                                canFocus = enabled && isFocusEnabled
                                up = upRequester
                                down = downRequester
                                left = currentRequester
                            },
                        onMoveRight = { onMoveToContent(destination.route) },
                        onClick = { onMoveToContent(destination.route) },
                        onItemFocused = {
                            if (!enabled) return@TvNavRailItem
                            lastFocusedRoute = destination.route
                            val preferredRoute = currentPreferredEntryRoute
                            if (preferredRoute != null) {
                                if (destination.route == preferredRoute) {
                                    onPreferredEntryRouteConsumed()
                                } else if (railHasFocus) {
                                    return@TvNavRailItem
                                }
                            }
                            onHighlight(destination.route)
                            if (navigateOnFocus) {
                                onNavigate(destination.route)
                            }
                        },
                    )
                }
            }
        }
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            color = Snow.copy(alpha = .5f),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(RAIL_EXPANDED_ITEM_WIDTH)
                .padding(start = 12.dp)
                .testTag("tv_nav_version"),
        )
    }

    var prevRailHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(railHasFocus) {
        if (railHasFocus && !prevRailHasFocus) {
            val preferred = currentPreferredEntryRoute
                ?.takeIf { it in currentEnabledRoutes }
                ?.let(itemRequesters::get)
                ?: currentActiveRoute.takeIf { it in currentEnabledRoutes }?.let(itemRequesters::get)
                ?: currentSelectedRoute.takeIf { it in currentEnabledRoutes }?.let(itemRequesters::get)
            val fallback = availableRoutes.firstOrNull()?.let { itemRequesters[it] }
            runCatching {
                when {
                    preferred != null -> preferred.requestFocus()
                    fallback != null -> fallback.requestFocus()
                }
            }
        }
        prevRailHasFocus = railHasFocus
    }

    LaunchedEffect(preferredEntryRoute, preferredEntryRequestNonce, isFocusEnabled) {
        if (!isFocusEnabled) return@LaunchedEffect
        val route = preferredEntryRoute ?: return@LaunchedEffect
        if (route !in enabledRoutes) return@LaunchedEffect
        val requester = itemRequesters[route] ?: return@LaunchedEffect
        runCatching { requester.requestFocus() }
        delay(16)
        runCatching { requester.requestFocus() }
        delay(48)
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun TvNavRailItem(
    destination: TvTopDestination,
    selected: Boolean,
    enabled: Boolean,
    needsAttention: Boolean = false,
    onMoveRight: () -> Unit,
    onClick: () -> Unit,
    onItemFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    var keyDownReceivedHere by remember { mutableStateOf(false) }
    val expanded = focused || selected
    val tint by animateColorAsState(
        targetValue = when {
            focused -> Snow
            selected -> Amber.copy(alpha = 0.92f)
            else -> Snow.copy(alpha = 0.74f)
        },
        animationSpec = tween(if (reduceMotion) 0 else 90),
        label = "railTint",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            focused -> Snow
            selected -> Snow.copy(alpha = 0.86f)
            else -> Snow.copy(alpha = 0.66f)
        },
        animationSpec = tween(if (reduceMotion) 0 else 90),
        label = "railLabelTint",
    )
    val label = shortNavLabel(destination)
    val accessibleLabel = if (destination.route == TvRoutes.HOME) "Home" else stringResource(destination.labelResId)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium,
    )
    val textMeasurer = rememberTextMeasurer()
    val labelWidthPx = with(LocalDensity.current) {
        (RAIL_EXPANDED_ITEM_WIDTH - RAIL_LABEL_HORIZONTAL_PADDING * 2).roundToPx()
    }
    val labelFontSize = remember(label, labelStyle, textMeasurer, labelWidthPx) {
        listOf(10.sp, 9.5.sp, 9.sp, 8.5.sp, 8.sp).firstOrNull { size ->
            textMeasurer.measure(label, labelStyle.copy(fontSize = size), softWrap = false, maxLines = 1)
                .size.width <= labelWidthPx
        } ?: 8.sp
    }
    val itemWidth by animateDpAsState(
        targetValue = if (expanded) RAIL_EXPANDED_ITEM_WIDTH else RAIL_ITEM_WIDTH,
        animationSpec = tween(if (reduceMotion) 0 else 180),
        label = "railItemWidth",
    )
    val labelReveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else if (focused) 180 else 100),
        label = "railLabelReveal",
    )
    val bubbleHeight by animateDpAsState(
        targetValue = if (expanded) 52.dp else 32.dp,
        animationSpec = tween(if (reduceMotion) 0 else 180),
        label = "railBubbleHeight",
    )
    val iconSize by animateDpAsState(
        targetValue = if (expanded) 24.dp else 14.dp,
        animationSpec = tween(if (reduceMotion) 0 else 180),
        label = "railIconSize",
    )

    Box(
        modifier = modifier
            .width(RAIL_EXPANDED_ITEM_WIDTH)
            .zIndex(if (focused) 2f else 0f)
            .testTag("tv_nav_item_${destination.route}")
            .semantics { contentDescription = accessibleLabel }
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (it.isFocused && !wasFocused) keyDownReceivedHere = false
                if (it.isFocused && !wasFocused) onItemFocused()
            }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown -> {
                        onMoveRight()
                        true
                    }
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyUp -> true
                    (event.key == Key.Enter || event.key == Key.DirectionCenter) &&
                        event.type == KeyEventType.KeyDown -> {
                        keyDownReceivedHere = true
                        true
                    }
                    (event.key == Key.Enter || event.key == Key.DirectionCenter) &&
                        event.type == KeyEventType.KeyUp -> {
                        if (keyDownReceivedHere) {
                            keyDownReceivedHere = false
                            onMoveRight()
                        }
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.width(itemWidth).height(bubbleHeight)
                .clipToBounds().testTag("tv_nav_bubble_${destination.route}"),
        ) {
            // Crop the left curve at the screen edge; the exposed end stays round.
            val radius = 16.dp
            Box(Modifier.offset(x = -radius).wrapContentWidth(Alignment.Start, unbounded = true)
                .width(itemWidth + radius).fillMaxHeight()
                .tvLiquidGlass(focused, selected, enabled, cornerRadius = radius, focusScale = 1f, quiet = true))
            if (expanded || labelReveal > 0f) Box(
                Modifier.align(Alignment.BottomCenter).offset(y = (-6).dp)
                    .width((itemWidth - RAIL_LABEL_HORIZONTAL_PADDING * 2).coerceAtLeast(0.dp)).height(14.dp)
                    .clipToBounds().alpha(labelReveal),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = localizedUiText(label),
                    style = labelStyle,
                    fontSize = labelFontSize,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("tv_nav_label_${destination.route}"),
                )
            }
            // Grow in place horizontally; the label uses the space underneath.
            Icon(destination.icon, contentDescription = null, tint = tint,
                modifier = Modifier.offset(x = RAIL_ICON_CENTER - iconSize / 2,
                    y = (bubbleHeight - iconSize) / 2 - 8.dp * labelReveal)
                    .size(iconSize).testTag("tv_nav_icon_${destination.route}"))
        }
        if (needsAttention) {
            TvAttentionDot(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 9.dp))
        }
    }
}

@Composable
private fun shortNavLabel(destination: TvTopDestination): String = when (destination.route) {
    TvRoutes.HOME -> "Home"
    TvRoutes.SHOWS -> stringResource(R.string.tv_nav_shows_compact)
    TvRoutes.IPTV -> stringResource(R.string.tv_nav_channels_compact)
    TvRoutes.LIBRARY -> stringResource(R.string.tv_nav_library_compact)
    TvRoutes.SETTINGS -> stringResource(R.string.tv_nav_settings_compact)
    else -> stringResource(destination.labelResId)
}
