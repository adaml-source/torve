package com.torve.desktop.ui.v2.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.vlc.VlcPlaybackEngine
import com.torve.desktop.vlc.VlcSessionState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Floating Picture-in-Picture overlay that renders the current VLC playback in
 * a small fixed-size frame while the main shell content remains interactive.
 *
 * Reuses the same AWT Canvas as the full-screen surface. Only one of the two
 * surfaces may mount the canvas at a time - the host composition is
 * responsible for switching between them via the `pipMode` flag.
 */
@Composable
fun V2PipOverlay(
    engine: VlcPlaybackEngine,
    title: String,
    subtitle: String?,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val sessionStateFlow = engine.sessionState
        ?: remember { MutableStateFlow(VlcSessionState()) }
    val sessionState by sessionStateFlow.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastFrameCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(engine) {
        while (isActive) {
            val renderer = engine.frameRenderer
            if (renderer != null && renderer.frameCount != lastFrameCount) {
                currentFrame = renderer.latestFrame()
                lastFrameCount = renderer.frameCount
            }
            delay(33)
        }
    }

    Box(
        modifier = modifier
            .size(width = 420.dp, height = 236.dp)
            .shadow(16.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp))
            .hoverable(interactionSource),
    ) {
        currentFrame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PipChrome(
                title = title,
                subtitle = subtitle,
                isPlaying = sessionState.isPlaying,
                onPlayPause = onPlayPause,
                onExpand = onExpand,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun PipChrome(
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            PipIconButton(
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
            )
            PipIconButton(
                icon = Icons.Filled.Fullscreen,
                contentDescription = "Expand",
                onClick = onExpand,
            )
            PipIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close",
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun PipIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = if (hovered) colors.fieldSurface.copy(alpha = 0.82f) else Color.Transparent,
        border = BorderStroke(if (hovered) 1.5.dp else 1.dp, colors.accent.copy(alpha = if (hovered) 1f else 0f)),
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
