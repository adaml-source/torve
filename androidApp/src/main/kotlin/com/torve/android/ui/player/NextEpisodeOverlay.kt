package com.torve.android.ui.player

import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.domain.player.NextEpisodeInfo

@Composable
fun NextEpisodeOverlay(
    nextEpisodeInfo: NextEpisodeInfo,
    countdown: Int,
    isResolving: Boolean,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val playNowFocus = remember { FocusRequester() }
    var playNowFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        runCatching { playNowFocus.requestFocus() }
    }
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
                    text = stringResource(if (nextEpisodeInfo.isNewSeason) R.string.player_next_season else R.string.player_next_episode),
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
                        modifier = Modifier
                            .focusRequester(playNowFocus)
                            .onFocusChanged { playNowFocused = it.isFocused }
                            .focusable()
                            .then(
                                if (playNowFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp))
                                else Modifier
                            ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (playNowFocused) Amber else Amber.copy(alpha = 0.75f),
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
                            Text(stringResource(R.string.common_loading))
                        } else {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.common_play_now))
                        }
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .onFocusChanged { cancelFocused = it.isFocused }
                            .focusable()
                            .then(
                                if (cancelFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                else Modifier
                            ),
                    ) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = if (cancelFocused) Color.White else Color.White.copy(alpha = 0.7f),
                        )
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
