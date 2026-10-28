package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvBackdropCard
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.RatingDisplayPrefs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TvBackdropCaptionTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun titleAndRatingArtworkShareOneFooterRow() { checkCaption(1f) }
    @Test fun largerTextKeepsRatingsBesideTheTitle() { checkCaption(1.5f) }

    private fun checkCaption(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    TvBackdropCard(MediaItem(id = "gentlemen", title = "The Gentlemen", type = MediaType.SERIES,
                        ratings = MediaRatings(imdbScore = 8.1f, rottenTomatoesScore = 80)), width = 230.dp,
                        onClick = {}, onFocused = {}, ratingPrefs = RatingDisplayPrefs())
                }
            }
        }
        val caption = composeRule.onNodeWithTag("backdrop_caption:gentlemen", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rating = composeRule.onNodeWithTag("rating_pills", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(rating.top >= caption.top && rating.bottom <= caption.bottom + 1)
        assertTrue(caption.height <= with(composeRule.density) { 34.dp.toPx() })
    }
}
