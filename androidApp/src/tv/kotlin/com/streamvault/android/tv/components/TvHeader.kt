package com.streamvault.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.domain.model.MediaItem

@Composable
fun TvHeader(
    sectionTitle: String,
    subtitle: String,
    featuredItem: MediaItem?,
    primaryActionFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onPlayFeatured: () -> Unit,
    onOpenFeatured: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .padding(top = 18.dp, end = 34.dp)
            .clip(RoundedCornerShape(22.dp)),
    ) {
        AsyncImage(
            model = featuredItem?.backdropUrl ?: featuredItem?.posterUrl,
            contentDescription = featuredItem?.title,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF7101422),
                            Color(0xE6101526),
                            Color(0xA2101526),
                            Color(0x55101526),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xCCECF1FF),
            )
            if (!featuredItem?.title.isNullOrBlank()) {
                Text(
                    text = featuredItem?.title.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!featuredItem?.overview.isNullOrBlank()) {
                Text(
                    text = featuredItem?.overview.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xCCECF1FF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(700.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlayFeatured,
                    modifier = Modifier
                        .focusRequester(primaryActionFocusRequester)
                        .focusProperties { left = railFocusRequester },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD6A45B),
                        contentColor = Color(0xFF111419),
                    ),
                ) {
                    Text(stringResource(R.string.tv_action_play))
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x55273247))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenFeatured,
                        )
                        .focusable()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tv_action_details),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

