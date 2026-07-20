package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.ui.components.LiveDot
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Coral
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.EnrichedChannel

@Composable
fun ChannelGridCard(
    enriched: EnrichedChannel,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = enriched.channel

    Surface(
        modifier = modifier
            .width(130.dp)
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(10.dp),
        color = Torve.colors.elevatedSurface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(10.dp),
        ) {
            // Logo
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Torve.colors.inputBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.tvgLogo != null) {
                    AsyncImage(
                        model = channel.tvgLogo,
                        contentDescription = channel.name,
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Favorite badge — top right
                if (channel.isFavorite) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Coral,
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.TopEnd)
                            .padding(2.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Channel name
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = Torve.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Current programme
            enriched.currentProgramme?.let { prog ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = prog.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
