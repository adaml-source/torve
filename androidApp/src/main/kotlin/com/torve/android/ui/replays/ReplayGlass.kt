package com.torve.android.ui.replays

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight

/** Translucent native surfaces with inexpensive compositing on Fire TV. */
@Composable
internal fun ReplayGlassButton(modifier: Modifier = Modifier, enabled: Boolean = true,
    onClick: () -> Unit, content: @Composable RowScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, tween(120), label = "replayGlassFocus")
    val shape = RoundedCornerShape(24.dp)
    Surface(onClick = onClick, enabled = enabled, shape = shape, color = Color.Transparent,
        contentColor = Color.White.copy(alpha = if (enabled) 1f else .4f),
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.hasFocus }
            .background(Brush.verticalGradient(listOf(
                Color(0xFF72849D).copy(alpha = if (focused) .36f else .19f),
                Color(0xFF192334).copy(alpha = if (focused) .82f else .68f))), shape)
            .border(if (focused) 2.dp else 1.dp, Brush.linearGradient(listOf(
                if (focused) AmberLight else Color.White.copy(alpha = .27f),
                if (focused) Amber else Color.White.copy(alpha = .07f))), shape)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) { content(focused) }
    }
}
