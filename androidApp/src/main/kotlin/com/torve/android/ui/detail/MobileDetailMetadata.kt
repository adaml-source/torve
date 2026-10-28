package com.torve.android.ui.detail

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.MultiRatingPills
import com.torve.domain.model.*
import com.torve.util.FormatUtil

/** Metadata and source ratings travel together, wrapping naturally on narrow phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobileDetailMetadata(item: MediaItem, ratings: MediaRatings?, prefs: RatingDisplayPrefs) {
    FlowRow(Modifier.fillMaxWidth().testTag("detail_top_metadata"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val centered = Modifier.align(Alignment.CenterVertically)
        item.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = centered.testTag("detail_metadata_year")) }
        item.runtime?.takeIf { it > 0 }?.let { Text(FormatUtil.formatRuntime(it),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = centered.testTag("detail_metadata_runtime")) }
        if (prefs.showRatingsOnDetailPage && ratings != null && ratings.hasAnyEnabledDisplayValue(prefs)) {
            MultiRatingPills(ratings, centered.testTag("detail_top_ratings"), prefs.copy(
                maxRatingsOnCard = prefs.enabledProviders.size.coerceAtLeast(1)))
            DetailRatingsAttribution(ratings, prefs, centered)
        }
        if (prefs.showTorveScoreOnDetailPage && ratings != null) {
            calculateTorveScore(ratings, prefs.torveWeights)?.let { score ->
                Text(localizedUiText("Torve ${"%.0f".format(score)}"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
