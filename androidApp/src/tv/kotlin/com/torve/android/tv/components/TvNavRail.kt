package com.torve.android.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.tv.RAIL_COLLAPSED_WIDTH
import com.torve.android.tv.nav.TvTopDestination
import com.torve.android.tv.settings.rememberTvReduceMotionPreference
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberGlow
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

private val RAIL_EXPANDED_WIDTH = 160.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvNavRail(
    destinations: List<TvTopDestination>,
    selectedRoute: String,
    activeRoute: String = selectedRoute,
    isExpanded: Boolean,
    railFocusRequester: FocusRequester,
    onRailFocusChanged: (Boolean) -> Unit,
    onMoveToContent: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onNavigate: (String) -> Unit,
    navigateOnFocus: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val railWidth by animateDpAsState(
        targetValue = if (isExpanded) RAIL_EXPANDED_WIDTH else RAIL_COLLAPSED_WIDTH,
        label = "railWidth",
    )
    val itemRequesters = remember(destinations) {
        destinations.associate { it.route to FocusRequester() }
    }
    val orderedRoutes = remember(destinations) { destinations.map { it.route } }

    fun neighborRoute(route: String, delta: Int): String? {
        val size = orderedRoutes.size
        if (size <= 0) return null
        val index = orderedRoutes.indexOf(route).takeIf { it >= 0 } ?: 0
        val neighborIndex = ((index + delta) % size + size) % size
        return orderedRoutes[neighborIndex]
    }

    // Use rememberUpdatedState so focusProperties.enter always reads
    // the latest activeRoute (the committed tab) — not highlightedRoute which
    // updates too late during focus entry transitions.
    val currentActiveRoute by rememberUpdatedState(activeRoute)
    val currentSelectedRoute by rememberUpdatedState(selectedRoute)
    var railHasFocus by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            // Clip to animated width — hides overflow when collapsed
            .clip(RectangleShape)
            // Measure content at expanded width (items stay stable),
            // but report animated width (rail visually shrinks).
            .layout { measurable, constraints ->
                val expandedPx = RAIL_EXPANDED_WIDTH.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minWidth = expandedPx, maxWidth = expandedPx),
                )
                layout(railWidth.roundToPx(), placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Obsidian.copy(alpha = 0.9f),
                        Obsidian.copy(alpha = 0.9f),
                    ),
                ),
            )
            .focusRequester(railFocusRequester)
            .onFocusChanged {
                railHasFocus = it.hasFocus
                onRailFocusChanged(it.hasFocus)
            }
            .focusProperties {
                // Use activeRoute (committed selectedTopRoute) for entry focus, not
                // highlightedRoute. This prevents focus from landing on a stale rail
                // item when focus falls to the rail during content disposal.
                enter = { itemRequesters[currentActiveRoute] ?: itemRequesters[currentSelectedRoute] ?: FocusRequester.Default }
            }
            .focusGroup()
            .padding(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TorveEllipseMark(
            showWordmark = isExpanded,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 6.dp),
        )

        destinations.forEach { destination ->
            val currentRequester = itemRequesters.getValue(destination.route)
            val upRequester = neighborRoute(destination.route, -1)
                ?.let { itemRequesters[it] }
                ?: currentRequester
            val downRequester = neighborRoute(destination.route, 1)
                ?.let { itemRequesters[it] }
                ?: currentRequester
            val isSelected = selectedRoute == destination.route
            TvNavRailItem(
                destination = destination,
                selected = isSelected,
                expanded = isExpanded,
                modifier = Modifier
                    .focusRequester(currentRequester)
                    .focusProperties {
                        up = upRequester
                        down = downRequester
                        left = currentRequester
                    },
                onConfirm = {
                    onConfirm(destination.route)
                },
                // Always consume Right and use explicit focus restore.
                // Natural spatial focus traversal causes drift (e.g. Settings scrolls 4 rows).
                onMoveRight = {
                    onConfirm(destination.route)
                    onMoveToContent(destination.route)
                },
                onClick = {
                    // .clickable fires via accessibility/interaction, bypassing onPreviewKeyEvent.
                    // Must do the same as onMoveRight to move focus into content.
                    onConfirm(destination.route)
                    onMoveToContent(destination.route)
                },
                onItemFocused = {
                    if (navigateOnFocus) {
                        onNavigate(destination.route)
                    }
                },
            )
        }
    }

    // Only refocus the selected item when the rail *gains* focus (user pressed Left).
    // Do NOT refocus when selectedRoute changes — that would fight with content focus restore.
    var prevRailHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(railHasFocus) {
        if (railHasFocus && !prevRailHasFocus) {
            val preferred = itemRequesters[currentActiveRoute] ?: itemRequesters[currentSelectedRoute]
            val fallback = orderedRoutes.firstOrNull()?.let { itemRequesters[it] }
            runCatching {
                when {
                    preferred != null -> preferred.requestFocus()
                    fallback != null -> fallback.requestFocus()
                }
            }
        }
        prevRailHasFocus = railHasFocus
    }
}

@Composable
private fun TvNavRailItem(
    destination: TvTopDestination,
    selected: Boolean,
    expanded: Boolean,
    onConfirm: () -> Unit,
    onMoveRight: () -> Unit,
    onClick: () -> Unit,
    onItemFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    // Track whether OK/Enter KeyDown was received on THIS item, so we only
    // act on KeyUp if the press started here (prevents KeyUp bounce from
    // content cards that were disposed between KeyDown and KeyUp).
    var keyDownReceivedHere by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (focused) 1.04f else 1f,
        label = "railItemScale",
    )
    val background = when {
        focused -> AmberSubtle
        selected && expanded -> AmberGlow
        else -> Color.Transparent
    }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber
            else -> Color.Transparent
        },
        label = "railBorder",
    )
    val tint by animateColorAsState(
        targetValue = when {
            focused -> Snow
            selected -> Amber
            else -> Silver
        },
        label = "railTint",
    )
    val itemHorizontalPadding = if (expanded) 12.dp else 6.dp
    val indicatorSpacing = if (expanded) 10.dp else 6.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                // Reset KeyDown tracking when focus changes — prevents stale state
                // from blocking OK presses after focus arrives via route switch.
                if (it.isFocused && !wasFocused) keyDownReceivedHere = false
                if (it.isFocused && !wasFocused) onItemFocused()
            }
            .onPreviewKeyEvent { event ->
                when {
                    // Right arrow: move to content on KeyDown (safe — clickable ignores Right)
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown -> {
                        onMoveRight()
                        true
                    }
                    event.key == Key.DirectionRight && event.type == KeyEventType.KeyUp -> true

                    // Enter/Center: mark KeyDown received here, act on KeyUp only if
                    // KeyDown was also on this item. This prevents KeyUp bounce from
                    // content cards that were disposed between KeyDown and KeyUp.
                    (event.key == Key.Enter || event.key == Key.DirectionCenter) && event.type == KeyEventType.KeyDown -> {
                        keyDownReceivedHere = true
                        true
                    }
                    (event.key == Key.Enter || event.key == Key.DirectionCenter) && event.type == KeyEventType.KeyUp -> {
                        if (keyDownReceivedHere) {
                            keyDownReceivedHere = false
                            onMoveRight()
                        }
                        true // always consume to prevent clickable from firing
                    }

                    else -> false
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = itemHorizontalPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(width = 2.dp, height = 22.dp)
                .background(
                    when {
                        focused -> Amber
                        selected -> Amber.copy(alpha = 0.5f)
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(2.dp),
                ),
        )

        Spacer(modifier = Modifier.width(indicatorSpacing))

        Icon(
            imageVector = destination.icon,
            contentDescription = stringResource(destination.labelResId),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )

        // Always rendered (stable layout); alpha hides when collapsed
        Text(
            text = stringResource(destination.labelResId),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            modifier = Modifier.padding(start = 10.dp).alpha(if (expanded) 1f else 0f),
        )
    }
}

/**
 * Collapsed: torve_eye.png (just the eye ellipses).
 * Expanded: torve_logo.png (T + eye + RVE).
 */
@Composable
private fun TorveEllipseMark(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = false,
) {
    if (showWordmark) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.torve_logo),
            contentDescription = "Torve",
            contentScale = ContentScale.Fit,
            modifier = modifier.height(28.dp),
        )
    } else {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.torve_eye),
            contentDescription = "Torve",
            contentScale = ContentScale.Fit,
            modifier = modifier.size(32.dp),
        )
    }
}
