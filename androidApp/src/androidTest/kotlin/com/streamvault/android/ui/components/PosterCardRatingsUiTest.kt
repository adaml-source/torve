package com.streamvault.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.streamvault.domain.model.CardStyle
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaRatings
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.RatingDisplayPrefs
import com.streamvault.domain.model.RatingPillPosition
import com.streamvault.domain.model.RatingSource
import org.junit.Rule
import org.junit.Test

class PosterCardRatingsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

        composeRule.onNodeWithTag("poster_ratings_inside").assertExists()
        composeRule.onNodeWithTag("poster_ratings_outside").assertDoesNotExist()
        composeRule.onNodeWithTag("rating_chip_TMDB").assertExists()
        composeRule.onNodeWithTag("rating_chip_IMDB").assertExists()
        composeRule.onNodeWithTag("rating_chip_ROTTEN_TOMATOES").assertDoesNotExist()
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

        composeRule.onNodeWithTag("poster_ratings_inside").assertDoesNotExist()
        composeRule.onNodeWithTag("poster_ratings_outside").assertExists()
        composeRule.onNodeWithTag("rating_chip_IMDB").assertExists()
        composeRule.onNodeWithTag("rating_chip_TMDB").assertExists()
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

