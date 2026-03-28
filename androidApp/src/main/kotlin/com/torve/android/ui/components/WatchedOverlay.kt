package com.torve.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.domain.model.WatchState
import com.torve.domain.model.WatchedIndicatorPrefs
import com.torve.domain.model.WatchedIndicatorStyle

private val WatchedGreen = Color(0xFF4CAF50)
private val RewatchPurple = Color(0xFF7B1FA2)

@Composable
fun WatchedOverlay(
    watchState: WatchState,
    prefs: WatchedIndicatorPrefs,
    cornerRadiusDp: Int = 8,
    modifier: Modifier = Modifier,
) {
    if (!prefs.enabled || !watchState.isStarted) return

    Box(modifier) {
        // Dim overlay for fully watched items
        if (watchState.isCompleted && prefs.dimWatched) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = prefs.dimAmount)),
            )
        }

        // Watched indicator by style
        if (watchState.isCompleted) {
            when (prefs.style) {
                WatchedIndicatorStyle.CHECKMARK_BADGE -> {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .background(WatchedGreen, CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.watched_cd),
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }

                WatchedIndicatorStyle.CHECKMARK_OVERLAY -> {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.watched_cd),
                            tint = WatchedGreen.copy(alpha = 0.85f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                WatchedIndicatorStyle.EYE_ICON -> {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.watched_cd),
                            tint = WatchedGreen,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                WatchedIndicatorStyle.BANNER -> {
                    Canvas(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(50.dp),
                    ) {
                        val path = Path().apply {
                            moveTo(size.width * 0.3f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width, size.height * 0.7f)
                            close()
                        }
                        drawPath(path, WatchedGreen.copy(alpha = 0.85f))
                    }
                    Text(
                        "\u2713",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 6.dp),
                    )
                }

                WatchedIndicatorStyle.BORDER -> {
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(2.dp, WatchedGreen, RoundedCornerShape(cornerRadiusDp.dp)),
                    )
                }

                WatchedIndicatorStyle.DOT -> {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(10.dp)
                            .background(WatchedGreen, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                    )
                }

                WatchedIndicatorStyle.NONE -> { /* No indicator — just dim */ }
            }
        }

        // Progress bar for partially watched
        if (!watchState.isCompleted && watchState.progressPercent > 0f && prefs.progressBarForPartial) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(watchState.progressPercent)
                        .background(Color(0xFFE5A00D)),
                )
            }
        }

        // Rewatch count badge
        if (watchState.isCompleted && prefs.rewatchBadge && watchState.rewatchCount > 1) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(RewatchPurple, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    "\u00D7${watchState.rewatchCount}",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
