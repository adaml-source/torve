package com.torve.android.tv.components

import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillStyle
import com.torve.domain.model.RatingSource

internal fun RatingDisplayPrefs.tvExternalCardRatingPrefs(): RatingDisplayPrefs {
    val preferredOrder = listOf(
        RatingSource.IMDB,
        RatingSource.ROTTEN_TOMATOES,
        RatingSource.RT_AUDIENCE,
        RatingSource.TMDB,
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
