package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val previewTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
internal fun TvEpgPreviewPanel(
    focusedChannel: EnrichedChannel?,
    focusedProgramme: EpgProgramme?,
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val channel = focusedChannel?.channel
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(channel?.url, isActive) {
        val url = channel?.url
        if (url == null) {
            player.stop()
            return@LaunchedEffect
        }
        if (!isActive) {
            player.playWhenReady = false
            player.pause()
            return@LaunchedEffect
        }
        // Stop current playback immediately so rapid scrolling doesn't keep
        // stale streams running while we wait for the debounce.
        val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUrl != url) {
            player.stop()
        }
        // Wait 500ms before starting new stream — skip loading when user
        // scrolls through channels quickly.
        delay(500)
        if (!isActive || channel?.url != url) return@LaunchedEffect
        if (player.currentMediaItem?.localConfiguration?.uri?.toString() != url) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
        player.playWhenReady = true
        player.play()
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val progress = focusedProgramme?.let { programme ->
        val total = (programme.endTime - programme.startTime).coerceAtLeast(1L)
        ((nowMs - programme.startTime).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Graphite.copy(alpha = 0.55f))
            .border(1.dp, Steel.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Obsidian.copy(alpha = 0.65f)),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                update = { view ->
                    view.player = player
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                },
                modifier = Modifier.fillMaxSize(),
            )

            Text(
                text = channel?.name ?: stringResource(R.string.tv_iptv_preview_idle),
                color = Snow,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Obsidian.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = channel?.name ?: stringResource(R.string.tv_iptv_preview_idle),
                color = Snow,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = focusedProgramme?.title ?: stringResource(R.string.tv_live_no_programme_data),
                color = Silver,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val subtitle = focusedProgramme?.subTitle
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = Silver.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val description = focusedProgramme?.description
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = Silver,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            if (focusedProgramme != null) {
                Text(
                    text = "${previewTimeFormat.format(Date(focusedProgramme.startTime))} - ${previewTimeFormat.format(Date(focusedProgramme.endTime))}",
                    color = Silver,
                    fontSize = 11.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Steel.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(Amber),
                    )
                }
            }
        }
    }
}
