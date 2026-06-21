package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.LiveDot
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Coral
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.EnrichedChannel

@Composable
fun ChannelRow(
    enriched: EnrichedChannel,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = enriched.channel
    val hasProgramme = enriched.currentProgramme != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel logo — rounded rectangle, not circle
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Torve.colors.inputBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo != null) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = channel.name,
                    modifier = Modifier.size(52.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Channel info — name, current programme, EPG progress
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Country code badge
                channel.tvgCountry?.takeIf { it.isNotBlank() }?.let { country ->
                    Text(
                        text = country.take(3).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Torve.colors.textTertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Torve.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Live indicator
                if (hasProgramme) {
                    Spacer(Modifier.width(6.dp))
                    LiveDot()
                }
            }

            // Current programme
            enriched.currentProgramme?.let { prog ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = prog.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // EPG progress bar — shows how far through the current programme
                val now = System.currentTimeMillis()
                val total = (prog.endTime - prog.startTime).coerceAtLeast(1)
                val elapsed = (now - prog.startTime).coerceIn(0, total)
                val progress = elapsed.toFloat() / total.toFloat()

                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = Amber,
                    trackColor = Torve.colors.inputBackground,
                )
            }

            // Next programme
            enriched.nextProgramme?.let { prog ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.next_format, prog.title),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Favorite button
        IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
            Icon(
                if (channel.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(R.string.channels_favorite_cd),
                tint = if (channel.isFavorite) Coral else Torve.colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
