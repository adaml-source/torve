package com.streamvault.android.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.Amber
import com.streamvault.domain.player.NextEpisodeInfo

@Composable
fun NextEpisodeOverlay(
    nextEpisodeInfo: NextEpisodeInfo,
    countdown: Int,
    isResolving: Boolean,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 80.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.85f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (nextEpisodeInfo.isNewSeason) "Next Season" else "Next Episode",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("S${nextEpisodeInfo.seasonNumber.toString().padStart(2, '0')}")
                        append("E${nextEpisodeInfo.episodeNumber.toString().padStart(2, '0')}")
                        if (nextEpisodeInfo.episodeName.isNotBlank()) {
                            append(" - ${nextEpisodeInfo.episodeName}")
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayNow,
                        enabled = !isResolving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (isResolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Loading...")
                        } else {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Play Now")
                        }
                    }
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                    }
                    if (!isResolving && countdown > 0) {
                        Text(
                            text = "${countdown}s",
                            style = MaterialTheme.typography.titleMedium,
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
