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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val currentChannelGuideTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun LiveCurrentChannelGuideOverlay(
    channel: EnrichedChannel,
    programmes: List<EpgProgramme>,
    activeReplayProgramme: EpgProgramme?,
    canReplayProgramme: (Channel, EpgProgramme) -> Boolean,
    onReplayProgramme: (Channel, EpgProgramme) -> Unit,
    onOpenFullGuide: () -> Unit,
    onWatchLive: (() -> Unit)?,
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Obsidian.copy(alpha = 0.9f),
                    1f to Color.Black.copy(alpha = 0.96f),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AsyncImage(
                        model = channel.channel.tvgLogo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Graphite.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .padding(6.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = channel.channel.name,
                            color = Snow,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = channel.currentProgramme?.title ?: "Channel schedule",
                            color = Silver,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryActionButton(
                        label = "Full guide",
                        icon = Icons.Filled.CalendarMonth,
                        focusRequester = firstFocus,
                        onClick = onOpenFullGuide,
                    )
                    if (onWatchLive != null) {
                        SecondaryActionButton(
                            label = "Watch live",
                            icon = Icons.Filled.PlayArrow,
                            onClick = onWatchLive,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (activeReplayProgramme != null) {
                    "Replay is active. Select another finished programme or return to the live channel."
                } else {
                    "Current channel schedule. Finished programmes can be replayed when archive support is available."
                },
                color = Silver,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(18.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (programmes.isEmpty()) {
                    item("empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Charcoal.copy(alpha = 0.88f), RoundedCornerShape(18.dp))
                                .padding(24.dp),
                        ) {
                            Text(
                                text = "No programme data is available for this channel yet.",
                                color = Silver,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }

                items(
                    items = programmes,
                    key = { programme -> "${programme.startTime}_${programme.endTime}_${programme.title}" },
                ) { programme ->
                    val canReplay = canReplayProgramme(channel.channel, programme)
                    val isCurrent = programme.startTime <= System.currentTimeMillis() &&
                        programme.endTime > System.currentTimeMillis()
                    val isReplayActive = activeReplayProgramme?.startTime == programme.startTime &&
                        activeReplayProgramme.endTime == programme.endTime

                    CurrentChannelProgrammeCard(
                        programme = programme,
                        isCurrent = isCurrent,
                        canReplay = canReplay,
                        isReplayActive = isReplayActive,
                        onClick = if (canReplay) {
                            { onReplayProgramme(channel.channel, programme) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        modifier = modifier.height(54.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Graphite.copy(alpha = 0.88f),
            focusedContainerColor = Amber.copy(alpha = 0.3f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Amber),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = label, tint = Snow, modifier = Modifier.size(20.dp))
            Text(text = label, color = Snow, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CurrentChannelProgrammeCard(
    programme: EpgProgramme,
    isCurrent: Boolean,
    canReplay: Boolean,
    isReplayActive: Boolean,
    onClick: (() -> Unit)?,
) {
    val statusLabel = when {
        isReplayActive -> "Playing"
        isCurrent -> "Now"
        canReplay -> "Replay"
        programme.endTime <= System.currentTimeMillis() -> "Ended"
        else -> "Upcoming"
    }

    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isReplayActive) Amber.copy(alpha = 0.18f) else Charcoal.copy(alpha = 0.92f),
            focusedContainerColor = Amber.copy(alpha = 0.24f),
            disabledContainerColor = Charcoal.copy(alpha = 0.86f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Amber),
                shape = RoundedCornerShape(18.dp),
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatClock(programme.startTime)} - ${formatClock(programme.endTime)}",
                    color = Silver,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .background(
                            if (isCurrent || canReplay || isReplayActive) Amber.copy(alpha = 0.2f) else Graphite.copy(alpha = 0.9f),
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = statusLabel,
                        color = if (isCurrent || canReplay || isReplayActive) Amber else Silver,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = programme.title,
                color = Snow,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            programme.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = Silver,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatClock(timestampMs: Long): String = currentChannelGuideTimeFormat.format(Date(timestampMs))
