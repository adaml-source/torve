package com.torve.android.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.torve.android.test.TorveTestHostActivity
import com.torve.domain.model.CardStyle
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingPillPosition
import com.torve.domain.model.RatingSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PosterCardRatingsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun posterCard_rendersInsidePills_withProviderFilterAndMaxLimit() {
        composeRule.setContent {
            PosterCard(
                item = testItem(),
                onClick = {},
                showTitle = false,
                cardStyle = CardStyle(
                    ratingPrefs = RatingDisplayPrefs(
                        enabledProviders = listOf(
                            RatingSource.IMDB,
                            RatingSource.TMDB,
                            RatingSource.ROTTEN_TOMATOES,
                        ),
                        providerOrder = listOf(
                            RatingSource.TMDB,
                            RatingSource.IMDB,
                            RatingSource.ROTTEN_TOMATOES,
                        ),
                        maxRatingsOnCard = 2,
                        pillPosition = RatingPillPosition.INSIDE,
                    ),
                ),
            )
        }

        composeRule.onNodeWithTag("poster_ratings_inside").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("poster_ratings_outside").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("rating_chip_TMDB").assertIsDisplayed()
        composeRule.onNodeWithTag("rating_chip_IMDB").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("rating_chip_ROTTEN_TOMATOES").fetchSemanticsNodes().size)
    }

    @Test
    fun posterCard_rendersOutsidePills_whenOutsidePositionSelected() {
        composeRule.setContent {
            PosterCard(
                item = testItem(),
                onClick = {},
                showTitle = false,
                cardStyle = CardStyle(
                    ratingPrefs = RatingDisplayPrefs(
                        enabledProviders = listOf(RatingSource.IMDB, RatingSource.TMDB),
                        providerOrder = listOf(RatingSource.IMDB, RatingSource.TMDB),
                        maxRatingsOnCard = 2,
                        pillPosition = RatingPillPosition.OUTSIDE,
                    ),
                ),
            )
        }

        assertEquals(0, composeRule.onAllNodesWithTag("poster_ratings_inside").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("poster_ratings_outside").assertIsDisplayed()
        composeRule.onNodeWithTag("rating_chip_IMDB").assertIsDisplayed()
        composeRule.onNodeWithTag("rating_chip_TMDB").assertIsDisplayed()
    }

    private fun testItem(): MediaItem = MediaItem(
        id = "ui_test_1",
        type = MediaType.MOVIE,
        title = "UI Test Item",
        ratings = MediaRatings(
            imdbScore = 7.8f,
            tmdbScore = 7.4f,
            rottenTomatoesScore = 84,
        ),
        rating = 7.8,
    )
}
