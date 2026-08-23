package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TvGuideProgrammeActionOverlay(
    channel: Channel,
    programme: EpgProgramme?,
    canStartFromBeginning: Boolean,
    onWatchLive: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onRecord: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val firstFocusRequester = remember { FocusRequester() }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(channel.url, programme?.startTime) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.86f),
                    0.65f to Color.Black.copy(alpha = 0.68f),
                    1f to Color.Black.copy(alpha = 0.42f),
                ),
            )
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Menu)
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(Obsidian.copy(alpha = 0.98f), RoundedCornerShape(24.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = programme?.title?.takeIf { it.isNotBlank() } ?: channel.name,
                color = Snow,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    add(channel.name)
                    programme?.let {
                        add("${timeFormat.format(Date(it.startTime))}–${timeFormat.format(Date(it.endTime))}")
                    }
                }.joinToString("  •  "),
                color = Silver,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            programme?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = Silver,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GuideActionButton(
                    label = "Watch live",
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (canStartFromBeginning) Modifier
                            else Modifier.focusRequester(firstFocusRequester),
                        ),
                    onClick = onWatchLive,
                )
                if (canStartFromBeginning) {
                    GuideActionButton(
                        label = "Start from beginning",
                        modifier = Modifier
                            .weight(1.35f)
                            .focusRequester(firstFocusRequester),
                        onClick = onStartFromBeginning,
                    )
                }
                onRecord?.let { record ->
                    GuideActionButton(
                        label = "Record",
                        modifier = Modifier.weight(1f),
                        onClick = record,
                    )
                }
            }
            Text(
                text = "Press Menu or Back to close",
                color = Silver.copy(alpha = 0.78f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun GuideActionButton(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Graphite,
            focusedContainerColor = Amber.copy(alpha = 0.28f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, Amber),
                shape = shape,
            ),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Snow else Snow.copy(alpha = 0.92f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
