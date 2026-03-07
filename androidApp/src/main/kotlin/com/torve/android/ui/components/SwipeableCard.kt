package com.torve.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import com.torve.android.ui.theme.Amber
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipeableCard(
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Bookmark,
    revealColor: Color = Amber.copy(alpha = 0.3f),
    threshold: Float = 100f,
    content: @Composable () -> Unit,
) {
    val offsetX = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    val bgColor by animateColorAsState(
        targetValue = if (offsetX.floatValue > threshold / 2) revealColor else Color.Transparent,
        label = "swipe_reveal",
    )

    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    ) {
        // Reveal layer behind
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(bgColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (offsetX.floatValue > 20f) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(28.dp),
                )
            }
        }

        // Swipeable content
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.floatValue.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val newOffset = (offsetX.floatValue + delta).coerceIn(0f, threshold * 1.5f)
                        offsetX.floatValue = newOffset
                    },
                    onDragStopped = {
                        if (offsetX.floatValue > threshold) {
                            onSwipeRight()
                        }
                        scope.launch {
                            offsetX.floatValue = 0f
                        }
                    },
                ),
        ) {
            content()
        }
    }
}
