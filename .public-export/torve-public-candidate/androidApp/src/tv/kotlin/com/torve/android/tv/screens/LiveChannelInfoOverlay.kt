package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Parses quality badges from a channel name (HD, FHD, 4K, UHD, HEVC, STEREO).
 */
internal fun parseQualityBadges(name: String): List<String> {
    val badges = mutableListOf<String>()
    val upper = name.uppercase()
    if ("4K" in upper || "UHD" in upper) badges += "4K"
    else if ("FHD" in upper || "1080" in upper) badges += "FHD"
    else if (Regex("""\bHD\b""").containsMatchIn(upper) || "720" in upper) badges += "HD"
    if ("HEVC" in upper || "H.265" in upper || "H265" in upper) badges += "HEVC"
    if ("STEREO" in upper) badges += "STEREO"
    return badges
}

/**
 * Computes programme progress as a fraction 0..1.
 */
internal fun programmeProgress(programme: EpgProgramme): Float {
    val now = System.currentTimeMillis()
    val start = programme.startTime
    val end = programme.endTime
    if (end <= start) return 0f
    return ((now - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun LiveChannelInfoOverlay(
    currentChannel: EnrichedChannel,
    groupName: String,
    channelNumber: Int,
    recentChannels: List<Channel>,
    favoriteChannels: List<Channel>,
    onOpenEpgGuide: () -> Unit,
    onOpenHistory: () -> Unit,
    onTuneChannel: (Channel) -> Unit,
    onClearRecent: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            ),
    ) {
        // ── Top bar: group name (left) + clock (right) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Group / playlist name + channel logo
            Row(verticalAlignment = Alignment.CenterVertically) {
                currentChannel.channel.tvgLogo?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = groupName,
                    color = Silver,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Live clock
            val clockText = produceState(initialValue = timeFormat.format(Date())) {
                while (true) {
                    value = timeFormat.format(Date())
                    delay(1000)
                }
            }.value
            Text(
                text = clockText,
                color = Silver,
                fontSize = 14.sp,
            )
        }

        // ── Centre: channel info ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 44.dp, bottom = 36.dp)
                .width(620.dp)
                .background(Charcoal.copy(alpha = 0.74f), RoundedCornerShape(22.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Channel number + name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$channelNumber",
                    color = Amber,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = currentChannel.channel.name,
                    color = Snow,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Quality badges
            val badges = remember(currentChannel.channel.name) {
                parseQualityBadges(currentChannel.channel.name)
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { badge ->
                        Text(
                            text = badge,
                            color = Amber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(AmberSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // Current programme
            currentChannel.currentProgramme?.let { prog ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = prog.title,
                    color = Snow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${timeFormat.format(Date(prog.startTime))} – ${timeFormat.format(Date(prog.endTime))}",
                    color = Silver,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { programmeProgress(prog) },
                    modifier = Modifier
                        .width(280.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Amber,
                    trackColor = Charcoal,
                )
            }

            // Next programme
            currentChannel.nextProgramme?.let { next ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.tv_live_next_label) + " " + next.title,
                    color = Silver,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${timeFormat.format(Date(next.startTime))} – ${timeFormat.format(Date(next.endTime))}",
                    color = Silver.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }

        // DPAD zapping uses this compact HUD only. The old bottom carousel was
        // removed because it duplicated OK channel browsing and clipped at edges.
    }
}
