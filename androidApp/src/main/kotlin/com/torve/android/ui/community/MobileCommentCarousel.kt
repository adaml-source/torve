package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.mobileGlassSurface
import com.torve.presentation.detail.ContentComment

internal fun commentRatingColor(rating: Int, dark: Boolean): Color = when {
    rating >= 7 -> if (dark) Color(0xFF72D69A) else Color(0xFF176B3C)
    rating >= 4 -> if (dark) Color(0xFFFFB55C) else Color(0xFF925000)
    else -> if (dark) Color(0xFFFF8888) else Color(0xFFB32532)
}

@Composable
internal fun MobileCommentCarousel(comments: List<ContentComment>, onOpen: (ContentComment) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 28.dp).coerceAtMost(340.dp)
        LazyRow(Modifier.fillMaxWidth().testTag("comment_carousel"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(comments, key = { it.id }) { comment ->
                Surface(onClick = { onOpen(comment) }, shape = RoundedCornerShape(22.dp), color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(cardWidth).height((232f * fontScale.coerceIn(1f, 1.5f)).dp)
                        .mobileGlassSurface(RoundedCornerShape(22.dp), quiet = true).testTag("comment_${comment.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(comment.author, Modifier.weight(1f).testTag("comment_author_${comment.id}"),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            comment.userRating?.let { rating ->
                                Text("$rating/10", color = commentRatingColor(rating, MaterialTheme.colorScheme.surface.luminance() < .5f),
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("comment_rating_${comment.id}"))
                            }
                        }
                        Text(localizedUiText(if (comment.containsSpoilers) "Contains spoilers · Tap to reveal" else comment.comment),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Text(localizedUiText("${comment.createdAt.take(10)} · ${comment.likes} likes"), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
