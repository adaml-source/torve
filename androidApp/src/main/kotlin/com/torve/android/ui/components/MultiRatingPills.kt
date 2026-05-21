package com.torve.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillStyle
import com.torve.domain.model.RatingSource
import com.torve.domain.model.calculateTorveScore
import com.torve.domain.model.deriveProvidersToRender
import com.torve.domain.model.hasValueFor

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
@OptIn(ExperimentalLayoutApi::class)
fun MultiRatingPills(
    ratings: MediaRatings,
    modifier: Modifier = Modifier,
    prefs: RatingDisplayPrefs = LocalRatingPrefs.current,
) {
    val enabledProviders = prefs.enabledProviders.filterNot {
        it == RatingSource.TORVE && !prefs.showTorveScoreOnCards
    }
    val providersToRender = deriveProvidersToRender(
        enabledProviders = enabledProviders,
        providerOrder = prefs.providerOrder,
        maxRatingsOnCard = prefs.maxRatingsOnCard,
        fallbackToTmdbWhenNoneSelected = prefs.enabledProviders.isEmpty(),
    )

    val pills = providersToRender.mapNotNull { source ->
        val value = getRatingValue(source, ratings, prefs)
        if (value != null) Triple(source, value, false) else null
    }

    if (pills.isEmpty()) return

    FlowRow(
        modifier = modifier
            .testTag("rating_pills")
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        pills.forEach { (source, value, _) ->
            key(source) {
                RatingChip(
                    source = source,
                    displayValue = value,
                    isMissing = false,
                    style = prefs.pillStyle,
                    ratings = ratings,
                )
            }
        }
    }
}

@Composable
fun PreferredRatingPills(
    ratings: MediaRatings,
    modifier: Modifier = Modifier,
    prefs: RatingDisplayPrefs = LocalRatingPrefs.current,
) {
    val selectedExternalProviders = if (prefs.enabledProviders.isEmpty()) {
        listOf(RatingSource.TMDB)
    } else {
        prefs.enabledProviders.filterNot { it == RatingSource.TORVE }
    }
    val orderedExternalProviders = (prefs.providerOrder + RatingSource.entries)
        .distinct()
        .filter { it in selectedExternalProviders && ratings.hasValueFor(it) }
    if (orderedExternalProviders.isNotEmpty()) {
        MultiRatingPills(
            ratings = ratings,
            modifier = modifier,
            prefs = prefs.copy(
                enabledProviders = orderedExternalProviders,
                maxRatingsOnCard = prefs.maxRatingsOnCard.coerceAtLeast(1),
                showTorveScoreOnCards = false,
            ),
        )
        return
    }

    if (calculateTorveScore(ratings, prefs.torveWeights) != null) {
        MultiRatingPills(
            ratings = ratings,
            modifier = modifier,
            prefs = prefs.copy(
                enabledProviders = listOf(RatingSource.TORVE),
                providerOrder = listOf(RatingSource.TORVE),
                maxRatingsOnCard = 1,
                showTorveScoreOnCards = true,
            ),
        )
    }
}

@Composable
private fun RatingChip(
    source: RatingSource,
    displayValue: String,
    isMissing: Boolean,
    style: RatingPillStyle,
    ratings: MediaRatings,
) {
    val (iconColor, textColor) = if (isMissing) {
        val muted = Color(0xFF777777)
        muted to muted
    } else {
        getSourceColors(source, displayValue, ratings)
    }

    Row(
        modifier = Modifier
            .testTag("rating_chip_${source.name}")
            .background(ChipBg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (style) {
            RatingPillStyle.ICON -> {
                val iconRes = ratingSourceIconRes(source, ratings)
                if (iconRes != null) {
                    Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = source.displayName,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Text(
                        text = source.iconChar,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = iconColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            RatingPillStyle.LETTER -> {
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
        RatingSource.TORVE -> Color(0xFFFFC940) to Color(0xFFFFC940)
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

fun getRatingValue(source: RatingSource, ratings: MediaRatings, prefs: RatingDisplayPrefs): String? {
    return when (source) {
        RatingSource.TORVE -> calculateTorveScore(ratings, prefs.torveWeights)?.let { "%.0f".format(it) }
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
    RatingSource.TORVE -> Color(0xFFFFC940)
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
/**
 * Map rating sources to their bundled icon drawables for ICON pill style.
 * RT icons are score-aware:
 *   Critics: ≥75% → Certified Fresh, ≥60% → Fresh, <60% → Rotten
 *   Audience: ≥60% → Full Popcorn, <60% → Tipped Popcorn
 */
fun ratingSourceIconRes(source: RatingSource, ratings: MediaRatings? = null): Int? = when (source) {
    RatingSource.IMDB -> R.drawable.ic_rating_imdb
    RatingSource.ROTTEN_TOMATOES -> {
        val pct = ratings?.rottenTomatoesScore ?: 0
        when {
            pct >= 75 -> R.drawable.ic_rt_certified_fresh
            pct >= 60 -> R.drawable.ic_rt_fresh
            else -> R.drawable.ic_rt_rotten
        }
    }
    RatingSource.RT_AUDIENCE -> {
        val pct = ratings?.rtAudienceScore ?: 0
        if (pct >= 60) R.drawable.ic_rt_audience_fresh else R.drawable.ic_rt_audience_rotten
    }
    RatingSource.TMDB -> R.drawable.tmbd_logo
    RatingSource.METACRITIC -> R.drawable.ic_rating_metacritic
    RatingSource.LETTERBOXD -> R.drawable.ic_rating_letterboxd
    RatingSource.TRAKT -> R.drawable.ic_rating_trakt
    RatingSource.MDBLIST -> R.drawable.ic_rating_mdblist
    RatingSource.MAL -> R.drawable.ic_rating_mal
    RatingSource.TORVE -> R.drawable.ic_rating_torve
}

/**
 * Example display values for the settings screen.
 */
fun getRatingSourceExample(source: RatingSource): String = when (source) {
    RatingSource.TORVE -> "e.g. 84/100 weighted score"
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
