package com.torve.desktop.ui.v2.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.MediaItem
import kotlinx.coroutines.delay

/**
 * Compute the next episode after the one that just finished. Returns null when
 * there is no subsequent episode (e.g. end of series) or when `mediaItem.seasons`
 * doesn't have enough metadata.
 */
data class NextEpisode(val seasonNumber: Int, val episodeNumber: Int)

fun resolveNextEpisode(
    mediaItem: MediaItem,
    currentSeason: Int,
    currentEpisode: Int,
): NextEpisode? {
    val seasons = mediaItem.seasons
        .filter { it.seasonNumber > 0 }
        .sortedBy { it.seasonNumber }
    val currentSeasonInfo = seasons.firstOrNull { it.seasonNumber == currentSeason }
    if (currentSeasonInfo != null && currentEpisode < currentSeasonInfo.episodeCount) {
        return NextEpisode(currentSeason, currentEpisode + 1)
    }
    val nextSeason = seasons.firstOrNull { it.seasonNumber > currentSeason }
    if (nextSeason != null && nextSeason.episodeCount > 0) {
        return NextEpisode(nextSeason.seasonNumber, 1)
    }
    return null
}

private const val AUTOPLAY_SECONDS = 15

/**
 * Overlay shown after an episode ends when a next episode is known. Counts
 * down from 15 seconds then calls [onPlayNow] unless cancelled.
 */
@Composable
fun V2NextEpisodePrompt(
    showTitle: String,
    nextEpisodeLabel: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    var secondsLeft by remember { mutableIntStateOf(AUTOPLAY_SECONDS) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        onPlayNow()
    }

    Box(
        modifier = modifier
            .width(440.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.cardSurface)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "${ds("Up next")}: $nextEpisodeLabel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = showTitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { (AUTOPLAY_SECONDS - secondsLeft).toFloat() / AUTOPLAY_SECONDS },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colors.accent,
                trackColor = colors.borderSubtle,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.weight(1f))
                TorveGhostButton(text = ds("Cancel"), onClick = onCancel)
                TorvePrimaryButton(
                    text = "${ds("Play now")} (${secondsLeft}s)",
                    onClick = onPlayNow,
                )
            }
        }
    }
}
