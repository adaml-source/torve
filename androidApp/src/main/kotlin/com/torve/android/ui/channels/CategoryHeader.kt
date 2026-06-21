package com.torve.android.ui.channels

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Badge1080p
import com.torve.android.ui.theme.Badge4K
import com.torve.android.ui.theme.Badge720p
import com.torve.android.ui.theme.BadgeSD
import com.torve.android.ui.theme.Torve

@Composable
fun CategoryHeader(
    name: String,
    channelCount: Int,
    qualityTags: Set<String>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    countryCode: String? = null,
) {
    val rotation by animateFloatAsState(
        if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "chevron",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Country code badge
        if (countryCode != null) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Amber.copy(alpha = 0.15f),
            ) {
                Text(
                    text = countryCode,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        // Category name — EXPLICIT color, never rely on theme defaults
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isExpanded) Amber else Torve.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(8.dp))

        // Quality tags — only show valid ones
        val validTags = qualityTags.filter { it in setOf("4K", "FHD", "HD", "SD") }
        if (validTags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.widthIn(max = 120.dp),
            ) {
                val sortOrder = listOf("4K", "FHD", "HD", "SD")
                validTags
                    .sortedBy { sortOrder.indexOf(it).takeIf { i -> i >= 0 } ?: 99 }
                    .take(3)
                    .forEach { tag -> QualityTag(tag) }
            }
            Spacer(Modifier.width(6.dp))
        }

        // Channel count — explicit color
        Text(
            text = "$channelCount",
            style = MaterialTheme.typography.labelMedium,
            color = Torve.colors.textTertiary,
        )

        // Chevron — explicit color
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
            tint = Torve.colors.textSecondary,
        )
    }
}

@Composable
private fun QualityTag(tag: String) {
    val color = when (tag) {
        "4K" -> Badge4K
        "FHD" -> Badge1080p
        "HD" -> Badge720p
        "SD" -> BadgeSD
        else -> Torve.colors.textTertiary
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = tag,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color, // ← EXPLICIT badge color
            fontWeight = FontWeight.Bold,
        )
    }
}
