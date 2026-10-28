package com.torve.presentation.apple

import com.torve.domain.model.*

/** Shared preset edits preserve fields that the TV editor does not expose. */
internal fun editAppleCardStyle(style: CardStyle, field: String, value: String): CardStyle {
    val appearance = style.appearance
    return when (field) {
        "orientation" -> CardOrientation.entries.firstOrNull { it.name == value }?.let { style.copy(size = style.size.copy(orientation = it)) } ?: style
        "size" -> CardSizePreset.entries.firstOrNull { it.name == value }?.let { style.copy(size = style.size.copy(preset = it)) } ?: style
        "title" -> CardTitlePosition.entries.firstOrNull { it.name == value }?.let { style.copy(appearance = appearance.copy(titlePosition = it)) } ?: style
        "year" -> if (value == "HIDDEN") style.copy(appearance = appearance.copy(showYear = false)) else
            CardOverlayPosition.entries.firstOrNull { it.name == value }?.let { style.copy(appearance = appearance.copy(showYear = true, yearPosition = it)) } ?: style
        "ratings" -> CardOverlayPosition.entries.firstOrNull { it.name == value }?.let {
            style.copy(appearance = appearance.copy(ratingPosition = it), ratingPrefs = style.ratingPrefs.copy(
                pillPosition = if (it == CardOverlayPosition.BELOW) RatingPillPosition.OUTSIDE else RatingPillPosition.INSIDE))
        } ?: style
        "ratingsCount" -> value.toIntOrNull()?.let { style.copy(ratingPrefs = style.ratingPrefs.copy(maxRatingsOnCard = it.coerceIn(0, 3))) } ?: style
        "landscapeRatings" -> style.copy(ratingPrefs = style.ratingPrefs.copy(allowRatingsOnLandscapeCards = value == "true"))
        "postersPerRow" -> value.toIntOrNull()?.let { style.copy(appearance = appearance.copy(postersPerRow = if (it == 0) 0 else it.coerceIn(1, 12))) } ?: style
        "spacing" -> value.toIntOrNull()?.let { style.copy(appearance = appearance.copy(cardSpacingDp = it.coerceIn(8, 48))) } ?: style
        "radius" -> value.toIntOrNull()?.let { style.copy(appearance = appearance.copy(cornerRadiusDp = it.coerceIn(0, 32))) } ?: style
        else -> style
    }
}
