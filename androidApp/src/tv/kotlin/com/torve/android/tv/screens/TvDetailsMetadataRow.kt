package com.torve.android.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.getRatingValue
import com.torve.android.ui.components.ratingSourceIconRes
import com.torve.android.ui.theme.Silver
import com.torve.domain.model.MediaItem
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.domain.model.deriveProvidersToRender
import com.torve.domain.model.withFallbackTmdbScore

/** Year, runtime and correctly labelled provider scores share the line above actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvDetailsMetadataRow(item: MediaItem, prefs: RatingDisplayPrefs, modifier: Modifier = Modifier) {
    val ratings = item.ratings.withFallbackTmdbScore(item.rating)
    val selected = prefs.enabledProviders.filterNot { it == RatingSource.TORVE }
    val providers = deriveProvidersToRender(selected, prefs.providerOrder,
        maxRatingsOnCard = selected.size.coerceAtLeast(1),
        fallbackToTmdbWhenNoneSelected = prefs.enabledProviders.isEmpty())
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val metadata = listOfNotNull(item.year?.toString(), item.runtime?.let { "${it}m" }).joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.titleMedium,
            color = Silver, modifier = Modifier.align(Alignment.CenterVertically))
        if (prefs.showRatingsOnDetailPage && ratings != null) providers.forEach { source ->
            getRatingValue(source, ratings, prefs)?.let { value ->
                TvRatingChip(source.displayName, value, ratingSourceIconRes(source, ratings))
            }
        }
    }
}
