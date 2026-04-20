package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

@Composable
fun LiveMenuBarOverlay(
    videoResolution: String,
    audioCodec: String,
    onSearch: () -> Unit,
    onChannelList: () -> Unit,
    onGuide: () -> Unit,
    onPlaybackOptions: () -> Unit,
    onPip: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { firstFocus.requestFocus() } catch (_: IllegalStateException) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            // Row 1: Stream info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (videoResolution.isNotBlank()) {
                    InfoChip(label = videoResolution)
                }
                if (audioCodec.isNotBlank()) {
                    InfoChip(label = audioCodec)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Row 2: Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuBarButton(
                    icon = Icons.Filled.Search,
                    label = stringResource(R.string.tv_live_search),
                    focusRequester = firstFocus,
                    onClick = onSearch,
                )
                MenuBarButton(
                    icon = Icons.Filled.List,
                    label = stringResource(R.string.tv_live_channels_list),
                    onClick = onChannelList,
                )
                MenuBarButton(
                    icon = Icons.Filled.CalendarMonth,
                    label = stringResource(R.string.tv_live_tv_guide),
                    onClick = onGuide,
                )
                MenuBarButton(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.tv_live_settings),
                    onClick = onPlaybackOptions,
                )
                MenuBarButton(
                    icon = Icons.Filled.PictureInPicture,
                    label = stringResource(R.string.tv_live_pip),
                    onClick = onPip,
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String) {
    Text(
        text = label,
        color = Silver,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Graphite.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun MenuBarButton(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val modifier = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(56.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Graphite.copy(alpha = 0.85f),
            focusedContainerColor = Amber.copy(alpha = 0.25f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Amber),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, tint = Snow, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = Snow, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
