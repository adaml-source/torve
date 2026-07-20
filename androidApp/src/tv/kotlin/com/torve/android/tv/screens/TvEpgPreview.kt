package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
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
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val progress = focusedProgramme?.let { programme ->
        val total = (programme.endTime - programme.startTime).coerceAtLeast(1L)
        ((nowMs - programme.startTime).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } ?: 0f
    val nextProgramme = focusedChannel?.nextProgramme
    val channelLogo = channel?.tvgLogo?.takeIf { it.isNotBlank() }
    val programmeTitle = focusedProgramme?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.tv_live_no_programme_data)
    val timeRange = focusedProgramme?.let {
        "${previewTimeFormat.format(Date(it.startTime))} - ${previewTimeFormat.format(Date(it.endTime))}"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Obsidian.copy(alpha = 0.94f),
                        Charcoal.copy(alpha = 0.82f),
                        Graphite.copy(alpha = 0.62f),
                    ),
                ),
            )
            .border(1.dp, Steel.copy(alpha = 0.34f), RoundedCornerShape(26.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Amber.copy(alpha = 0.16f),
                            Graphite.copy(alpha = 0.72f),
                            Obsidian.copy(alpha = 0.96f),
                        ),
                    ),
                )
                .border(1.dp, Steel.copy(alpha = 0.28f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (channelLogo != null) {
                AsyncImage(
                    model = channelLogo,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(140.dp)
                        .height(80.dp),
                )
            } else {
                Text(
                    text = channel?.name?.take(2)?.uppercase() ?: stringResource(R.string.tv_iptv_preview_idle),
                    color = Snow.copy(alpha = if (isActive) 1f else 0.72f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
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
                text = programmeTitle,
                color = if (focusedProgramme != null) Amber else Silver,
                fontSize = if (focusedProgramme != null) 21.sp else 18.sp,
                fontWeight = if (focusedProgramme != null) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (focusedProgramme == null && channel != null) {
                Text(
                    text = "Live playback available",
                    color = Silver.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                timeRange?.let {
                    Text(
                        text = it,
                        color = Silver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                channel?.tvgLanguage?.takeIf { it.isNotBlank() }?.let { language ->
                    PreviewBadge(language.uppercase().take(5))
                }
                channel?.tvgCountry?.takeIf { it.isNotBlank() }?.let { country ->
                    PreviewBadge(country.uppercase().take(3))
                }
                channel?.groupTitle?.takeIf { it.isNotBlank() }?.let { group ->
                    PreviewBadge(group.take(10))
                }
            }

            focusedProgramme?.subTitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = Silver.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            focusedProgramme?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = Silver,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Spacer(Modifier.weight(1f))

            if (focusedProgramme != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Steel.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(5.dp)
                            .background(Amber),
                    )
                }
            }

            nextProgramme?.let { next ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Next: ${next.title.takeIf { it.isNotBlank() } ?: "Live programme"}",
                    color = Silver,
                    fontSize = 11.sp,
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
        text = label,
        color = Snow,
        fontSize = 9.sp,
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
