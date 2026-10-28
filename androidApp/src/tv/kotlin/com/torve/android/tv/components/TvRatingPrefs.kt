package com.torve.android.tv.components

import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillStyle
import com.torve.domain.model.RatingSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.domain.model.MediaItem
import com.torve.domain.model.withFallbackTmdbScore

@Composable
internal fun TvPosterRatingPills(item: MediaItem, prefs: RatingDisplayPrefs, modifier: Modifier = Modifier, maxRatings: Int = 2) {
    item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
        PreferredRatingPills(
            ratings = ratings,
            prefs = prefs.tvExternalCardRatingPrefs().copy(maxRatingsOnCard = maxRatings),
            modifier = modifier.testTag("tv_poster_ratings:${item.id}"),
        )
    }
}

internal fun RatingDisplayPrefs.tvExternalCardRatingPrefs(): RatingDisplayPrefs {
    val preferredOrder = listOf(
        RatingSource.IMDB,
        RatingSource.ROTTEN_TOMATOES,
    )
    return copy(
        enabledProviders = preferredOrder,
        providerOrder = preferredOrder + providerOrder.filterNot { it in preferredOrder },
        maxRatingsOnCard = 2,
        showTorveScoreOnCards = false,
        torveWeights = emptyMap(),
        pillStyle = RatingPillStyle.ICON,
    )
}
