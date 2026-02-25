package com.streamvault.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.ui.theme.Snow
import com.streamvault.domain.model.MediaRatings
import com.streamvault.domain.model.RatingDisplayPrefs
import com.streamvault.domain.model.RatingPillStyle
import com.streamvault.domain.model.RatingSource

val LocalRatingPrefs = staticCompositionLocalOf { RatingDisplayPrefs() }

// Source-specific brand colors
private val ImdbYellow = Color(0xFFF5C518)
private val TmdbGreen = Color(0xFF01D277)
private val LetterboxdGreen = Color(0xFF00E054)
private val TraktRed = Color(0xFFED1C24)
private val MdblistOrange = Color(0xFFFF6B00)
private val MalBlue = Color(0xFF2E51A2)
private val ChipBg = Color(0xFF1A1A2E)

@Composable
fun MultiRatingPills(
    ratings: MediaRatings,
    modifier: Modifier = Modifier,
    prefs: RatingDisplayPrefs = LocalRatingPrefs.current,
) {
    if (!prefs.showRatingsOnCards) return

    val enabledSources = prefs.sources
        .filter { it.enabled }
        .sortedBy { it.order }
        .map { it.source }

    val pills = enabledSources.mapNotNull { source ->
        val value = getRatingValue(source, ratings) ?: return@mapNotNull null
        source to value
    }.take(prefs.maxPillsOnCard)

    if (pills.isEmpty()) return

    Row(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pills.forEach { (source, value) ->
            RatingChip(source = source, displayValue = value, style = prefs.pillStyle, ratings = ratings)
        }
    }
}

@Composable
private fun RatingChip(
    source: RatingSource,
    displayValue: String,
    style: RatingPillStyle,
    ratings: MediaRatings,
) {
    val (iconColor, textColor) = getSourceColors(source, displayValue, ratings)

    Row(
        modifier = Modifier
            .background(ChipBg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (style) {
            RatingPillStyle.COMPACT -> {
                Text(
                    text = source.iconChar,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = iconColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            RatingPillStyle.MINIMAL -> {
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            RatingPillStyle.DETAILED -> {
                Text(
                    text = source.iconChar,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = iconColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Returns (iconColor, textColor) for a given source.
 * RT and Metacritic are score-aware — green for fresh/high, red for rotten/low.
 */
private fun getSourceColors(source: RatingSource, displayValue: String, ratings: MediaRatings): Pair<Color, Color> {
    val defaultText = Color(0xFFCCCCCC)
    return when (source) {
        RatingSource.IMDB -> ImdbYellow to defaultText
        RatingSource.ROTTEN_TOMATOES -> {
            val pct = ratings.rottenTomatoesScore ?: 0
            val c = if (pct >= 60) Color(0xFF67B346) else Color(0xFFFA320A)
            c to c
        }
        RatingSource.RT_AUDIENCE -> {
            val pct = ratings.rtAudienceScore ?: 0
            val c = if (pct >= 60) Color(0xFF67B346) else Color(0xFFFA320A)
            c to defaultText
        }
        RatingSource.TMDB -> TmdbGreen to defaultText
        RatingSource.METACRITIC -> {
            val score = ratings.metacriticScore ?: 0
            val c = when {
                score >= 61 -> Color(0xFF66CC33)
                score >= 40 -> Color(0xFFFFCC33)
                else -> Color(0xFFFF0000)
            }
            c to c
        }
        RatingSource.LETTERBOXD -> LetterboxdGreen to defaultText
        RatingSource.TRAKT -> TraktRed to defaultText
        RatingSource.MDBLIST -> MdblistOrange to defaultText
        RatingSource.MAL -> MalBlue to defaultText
    }
}

private fun getRatingValue(source: RatingSource, ratings: MediaRatings): String? {
    return when (source) {
        RatingSource.IMDB -> ratings.imdbScore?.let { "%.1f".format(it) }
        RatingSource.ROTTEN_TOMATOES -> ratings.rottenTomatoesScore?.let { "${it}%" }
        RatingSource.RT_AUDIENCE -> ratings.rtAudienceScore?.let { "${it}%" }
        RatingSource.TMDB -> ratings.tmdbScore?.let { "%.1f".format(it) }
        RatingSource.METACRITIC -> ratings.metacriticScore?.let { "$it" }
        RatingSource.LETTERBOXD -> ratings.letterboxdScore?.let { "%.1f".format(it) }
        RatingSource.TRAKT -> ratings.traktScore?.let { "%.0f%%".format(it) }
        RatingSource.MDBLIST -> ratings.mdblistScore?.let { "%.0f".format(it) }
        RatingSource.MAL -> ratings.malScore?.let { "%.1f".format(it) }
    }
}

/**
 * Get the brand color for a rating source — used in settings screen.
 */
fun getRatingSourceColor(source: RatingSource): Color = when (source) {
    RatingSource.IMDB -> ImdbYellow
    RatingSource.ROTTEN_TOMATOES -> Color(0xFFFA320A)
    RatingSource.RT_AUDIENCE -> Color(0xFFFFA500)
    RatingSource.TMDB -> TmdbGreen
    RatingSource.METACRITIC -> Color(0xFFFFCC33)
    RatingSource.LETTERBOXD -> LetterboxdGreen
    RatingSource.TRAKT -> TraktRed
    RatingSource.MDBLIST -> MdblistOrange
    RatingSource.MAL -> MalBlue
}

/**
 * Example display values for the settings screen.
 */
fun getRatingSourceExample(source: RatingSource): String = when (source) {
    RatingSource.IMDB -> "e.g. 7.5/10"
    RatingSource.ROTTEN_TOMATOES -> "e.g. 81% (critics)"
    RatingSource.RT_AUDIENCE -> "e.g. 92% (audience)"
    RatingSource.TMDB -> "e.g. 7.2/10"
    RatingSource.METACRITIC -> "e.g. 75/100"
    RatingSource.LETTERBOXD -> "e.g. 3.8/5"
    RatingSource.TRAKT -> "e.g. 85%"
    RatingSource.MDBLIST -> "e.g. 78/100 (aggregate)"
    RatingSource.MAL -> "e.g. 8.2/10 (anime)"
}
