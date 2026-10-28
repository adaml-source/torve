package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import com.torve.android.tv.components.tvLiquidGlass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.torve.android.R
import com.torve.android.player.TorvePlayerView
import com.torve.android.player.clearPlayerSafely
import com.torve.android.player.setPlayerSafely
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.presentation.channels.EpgState
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
    epgState: EpgState = EpgState.NotConfigured,
) {
    val channel = focusedChannel?.channel
    val display = remember(channel) { channel?.let(::tvChannelPresentation) }
    val activeLiveSession = ActivePlaybackState.session?.takeIf { ActivePlaybackState.isLiveSession }
    val activeLivePlayer = if (isActive && activeLiveSession != null) {
        ActivePlaybackState.retainedLivePlayer()
    } else {
        null
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    if (channel == null && activeLivePlayer == null) {
        Box(modifier.height(44.dp).tvLiquidGlass(cornerRadius = 16.dp, quiet = true).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(localizedUiText("Choose a channel to see programme details"), color = Silver, fontSize = 13.sp, maxLines = 2)
        }
        return
    }

    val progress = focusedProgramme?.let { programme ->
        val total = (programme.endTime - programme.startTime).coerceAtLeast(1L)
        ((nowMs - programme.startTime).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } ?: 0f
    val nextProgramme = focusedChannel?.nextProgramme
    val hasProgramme = !focusedProgramme?.title.isNullOrBlank()
    val hasEventDetails = display?.detail != null || display?.startMs != null
    val previewHeight = when {
        activeLivePlayer != null -> 104.dp
        hasProgramme && nextProgramme != null -> 104.dp
        hasProgramme -> 88.dp
        hasEventDetails -> 80.dp
        else -> 64.dp
    }
    val programmeTitle = focusedProgramme?.title?.takeIf { it.isNotBlank() }
        ?: display?.detail
        ?: when (epgState) {
            EpgState.NotConfigured -> "Programme guide not configured"
            EpgState.Loading -> "Updating programme guide…"
            is EpgState.Error -> "Programme guide could not update · Filters to retry"
            is EpgState.Loaded -> "No schedule supplied for this channel"
        }
    val timeRange = focusedProgramme?.let {
        val state = when {
            it.startTime > nowMs -> "Upcoming"
            it.endTime <= nowMs -> "Finished"
            else -> "${((it.endTime - nowMs + 59_999L) / 60_000L)} min left"
        }
        "${previewTimeFormat.format(Date(it.startTime))}–${previewTimeFormat.format(Date(it.endTime))} · $state"
    }

    Row(
        modifier = modifier
            .height(previewHeight)
            .tvLiquidGlass(cornerRadius = 18.dp, quiet = true, baseTint = Obsidian.copy(alpha = .36f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activeLivePlayer != null) Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Amber.copy(alpha = 0.16f),
                            Graphite.copy(alpha = 0.72f),
                            Obsidian.copy(alpha = 0.96f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (activeLivePlayer != null) {
                AndroidView(
                    factory = { context ->
                        TorvePlayerView(context).apply {
                            useController = false
                            setPlayerSafely(activeLivePlayer, "tv_iptv_retained_preview_factory")
                        }
                    },
                    update = { view ->
                        view.setPlayerSafely(activeLivePlayer, "tv_iptv_retained_preview_update")
                    },
                    onRelease = { view ->
                        view.clearPlayerSafely("tv_iptv_retained_preview_release")
                    },
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                )
                Text(
                    text = localizedUiText("LIVE · ${activeLiveSession?.title.orEmpty()}"),
                    color = Snow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Obsidian.copy(alpha = 0.78f), RoundedCornerShape(topEnd = 8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else TvChannelLogo(channel, size = if (hasProgramme || hasEventDetails) 48.dp else 40.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = if (hasProgramme || hasEventDetails) Arrangement.spacedBy(3.dp) else Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = display?.title ?: activeLiveSession?.title ?: stringResource(R.string.tv_iptv_preview_idle),
                    color = Snow,
                    fontSize = if (hasProgramme) 12.sp else 16.sp,
                    lineHeight = if (hasProgramme) 15.sp else 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                display?.quality?.let { QualityBadge(it) }
            }

            Text(
                text = programmeTitle,
                color = if (hasProgramme) Amber else Silver,
                fontSize = if (hasProgramme) 16.sp else 11.sp,
                lineHeight = if (hasProgramme) 20.sp else 14.sp,
                fontWeight = if (hasProgramme) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (timeRange != null || display?.startMs != null || !display?.status?.label.isNullOrBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                timeRange?.let {
                    Text(
                        text = it,
                        color = Silver,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (focusedProgramme == null) {
                    display?.status?.label?.takeIf { it.isNotBlank() }?.let { PreviewBadge(it) }
                    display?.startMs?.let { start ->
                        Text(localizedUiText(SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(start))), color = Silver, fontSize = 10.sp, lineHeight = 12.sp)
                    }
                }
            }

            if (hasProgramme || hasEventDetails) Spacer(Modifier.weight(1f))

            if (focusedProgramme != null && focusedProgramme.startTime <= nowMs && focusedProgramme.endTime > nowMs) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Steel.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(Amber),
                    )
                }
            }

            nextProgramme?.let { next ->
                Text(
                    text = localizedUiText("Next: ${next.title.takeIf { it.isNotBlank() } ?: "Live programme"}"),
                    color = Silver,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PreviewBadge(label: String) {
    Text(
        text = localizedUiText(label),
        color = Snow,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Steel.copy(alpha = 0.20f))
            .border(1.dp, Steel.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
