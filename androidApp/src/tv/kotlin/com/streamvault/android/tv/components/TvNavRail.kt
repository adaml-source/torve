package com.streamvault.android.tv.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.streamvault.android.tv.nav.TvTopDestination

@Composable
fun TvNavRail(
    destinations: List<TvTopDestination>,
    selectedRoute: String,
    isExpanded: Boolean,
    railFocusRequester: FocusRequester,
    onRailFocusChanged: (Boolean) -> Unit,
    onMoveToContent: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val railWidth by animateDpAsState(targetValue = if (isExpanded) 228.dp else 94.dp, label = "railWidth")
    val itemRequesters = remember(destinations) {
        destinations.associate { it.route to FocusRequester() }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xE60A1020),
                        Color(0xE60B1324),
                    ),
                ),
            )
            .focusRequester(railFocusRequester)
            .onFocusChanged { onRailFocusChanged(it.hasFocus) }
            .focusable()
            .focusGroup()
            .padding(horizontal = 14.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "T",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            AnimatedVisibility(visible = isExpanded) {
                Text(
                    text = "orve",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        destinations.forEach { destination ->
            TvNavRailItem(
                destination = destination,
                selected = selectedRoute == destination.route,
                expanded = isExpanded,
                modifier = Modifier.focusRequester(
                    itemRequesters.getValue(destination.route),
                ),
                onMoveToContent = onMoveToContent,
                onClick = { onNavigate(destination.route) },
            )
        }
    }

    LaunchedEffect(isExpanded, selectedRoute) {
        if (isExpanded) {
            itemRequesters[selectedRoute]?.requestFocus()
        }
    }
}

@Composable
private fun TvNavRailItem(
    destination: TvTopDestination,
    selected: Boolean,
    expanded: Boolean,
    onMoveToContent: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        label = "railItemScale",
    )
    val background = when {
        focused -> Color(0x33D6A45B)
        selected -> Color(0x22D6A45B)
        else -> Color.Transparent
    }
    val borderColor = when {
        focused -> Color(0xFFDFB068)
        selected -> Color(0x66DFB068)
        else -> Color.Transparent
    }
    val tint = if (focused || selected) Color.White else Color(0xFFB3BDD0)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onMoveToContent()
                    true
                } else {
                    false
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(width = 2.dp, height = 22.dp)
                .background(borderColor, RoundedCornerShape(2.dp)),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Icon(
            imageVector = destination.icon,
            contentDescription = stringResource(destination.labelResId),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )

        AnimatedVisibility(visible = expanded) {
            Text(
                text = stringResource(destination.labelResId),
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
