package com.torve.android.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.torve.android.R
import com.torve.android.tv.TV_NAV_RAIL_WIDTH
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.TvTopDestination
import com.torve.android.tv.settings.rememberTvReduceMotionPreference
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import kotlinx.coroutines.delay

private val RAIL_GLASS_WIDTH = 70.dp
private val RAIL_ITEM_WIDTH = 56.dp
private val RAIL_ITEM_HEIGHT = 50.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvNavRail(
    destinations: List<TvTopDestination>,
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
    modifier: Modifier = Modifier,
) {
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

    val currentActiveRoute by rememberUpdatedState(activeRoute)
    val currentSelectedRoute by rememberUpdatedState(selectedRoute)
    val currentPreferredEntryRoute by rememberUpdatedState(preferredEntryRoute)
    var railHasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .width(TV_NAV_RAIL_WIDTH)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)
            .zIndex(4f),
    ) {
        Column(
            modifier = Modifier
                .width(RAIL_GLASS_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.68f),
                            Obsidian.copy(alpha = 0.60f),
                            Obsidian.copy(alpha = 0.66f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    color = Snow.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(26.dp),
                )
                .focusRequester(railFocusRequester)
                .onFocusChanged {
                    railHasFocus = it.hasFocus
                    onRailFocusChanged(it.hasFocus)
                }
                .focusProperties {
                    enter = {
                        currentPreferredEntryRoute?.let(itemRequesters::get)
                            ?: itemRequesters[currentActiveRoute]
                            ?: itemRequesters[currentSelectedRoute]
                            ?: FocusRequester.Default
                    }
                }
                .focusGroup()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TorveEllipseMark(modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))

            destinations.forEach { destination ->
                val currentRequester = itemRequesters.getValue(destination.route)
                val upRequester = neighborRoute(destination.route, -1)
                    ?.let { itemRequesters[it] }
                    ?: currentRequester
                val downRequester = neighborRoute(destination.route, 1)
                    ?.let { itemRequesters[it] }
                    ?: currentRequester
                val isCurrentRoute = selectedRoute == destination.route
                TvNavRailItem(
                    destination = destination,
                    selected = isCurrentRoute,
                    modifier = Modifier
                        .focusRequester(currentRequester)
                        .focusProperties {
                            up = upRequester
                            down = downRequester
                            left = currentRequester
                        },
                    onMoveRight = { onMoveToContent(destination.route) },
                    onClick = { onMoveToContent(destination.route) },
                    onItemFocused = {
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

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    var prevRailHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(railHasFocus) {
        if (railHasFocus && !prevRailHasFocus) {
            val preferred = currentPreferredEntryRoute?.let(itemRequesters::get)
                ?: itemRequesters[currentActiveRoute]
                ?: itemRequesters[currentSelectedRoute]
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

    LaunchedEffect(preferredEntryRoute, preferredEntryRequestNonce) {
        val route = preferredEntryRoute ?: return@LaunchedEffect
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
    onMoveRight: () -> Unit,
    onClick: () -> Unit,
    onItemFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberTvReduceMotionPreference()
    var focused by remember { mutableStateOf(false) }
    var keyDownReceivedHere by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (focused) 1.04f else 1f,
        label = "railItemScale",
    )
    val tint by animateColorAsState(
        targetValue = when {
            focused -> Snow
            selected -> Amber.copy(alpha = 0.92f)
            else -> Snow.copy(alpha = 0.74f)
        },
        label = "railTint",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            focused -> Snow
            selected -> Snow.copy(alpha = 0.86f)
            else -> Snow.copy(alpha = 0.66f)
        },
        label = "railLabelTint",
    )
    val tileBorder by animateColorAsState(
        targetValue = if (focused) Amber.copy(alpha = 0.78f) else Color.Transparent,
        label = "railTileBorder",
    )
    val tileFill = when {
        focused -> Brush.verticalGradient(
            colors = listOf(
                Amber.copy(alpha = 0.14f),
                Snow.copy(alpha = 0.045f),
                Amber.copy(alpha = 0.10f),
            ),
        )
        selected -> Brush.verticalGradient(
            colors = listOf(
                Amber.copy(alpha = 0.07f),
                Color.Transparent,
            ),
        )
        else -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val label = shortNavLabel(destination)

    Box(
        modifier = modifier
            .width(RAIL_ITEM_WIDTH)
            .height(RAIL_ITEM_HEIGHT)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(tileFill)
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = tileBorder,
                shape = RoundedCornerShape(16.dp),
            )
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (it.isFocused && !wasFocused) keyDownReceivedHere = false
                if (it.isFocused && !wasFocused) onItemFocused()
            }
            .onPreviewKeyEvent { event ->
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
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && !focused) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(20.dp)
                    .background(Amber.copy(alpha = 0.72f), RoundedCornerShape(3.dp)),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(if (focused) 23.dp else 21.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun shortNavLabel(destination: TvTopDestination): String = when (destination.route) {
    TvRoutes.HOME -> "Home"
    TvRoutes.MOVIES -> "Movies"
    TvRoutes.SHOWS -> "Shows"
    TvRoutes.SEARCH -> "Search"
    TvRoutes.IPTV -> "Channels"
    TvRoutes.SPORTS -> "Sports"
    TvRoutes.LIBRARY -> "Library"
    TvRoutes.SETTINGS -> "Settings"
    else -> stringResource(destination.labelResId)
}

@Composable
private fun TorveEllipseMark(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.torve_eye),
        contentDescription = "Torve",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(28.dp),
    )
}
