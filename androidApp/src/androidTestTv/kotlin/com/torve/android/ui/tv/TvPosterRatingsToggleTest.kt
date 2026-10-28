package com.torve.android.ui.tv

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.screens.TvRatingsSettingsScreen
import com.torve.android.tv.settings.TV_SHOW_POSTER_RATINGS_KEY
import com.torve.android.tv.settings.setTvPosterRatingsEnabled
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.presentation.settings.SettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.mp.KoinPlatform

class TvPosterRatingsToggleTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()
    private var hadPreference = false
    private var originalValue = true
    private val uiPrefs get() = composeRule.activity.getSharedPreferences("tv_ui_prefs", Context.MODE_PRIVATE)

    @Before fun savePreference() {
        hadPreference = uiPrefs.contains(TV_SHOW_POSTER_RATINGS_KEY)
        originalValue = uiPrefs.getBoolean(TV_SHOW_POSTER_RATINGS_KEY, true)
        setTvPosterRatingsEnabled(composeRule.activity, true)
    }

    @After fun restorePreference() {
        val editor = uiPrefs.edit()
        if (hadPreference) editor.putBoolean(TV_SHOW_POSTER_RATINGS_KEY, originalValue)
        else editor.remove(TV_SHOW_POSTER_RATINGS_KEY)
        editor.commit()
    }

    @Test fun settingsTogglePersistsAndUpdatesPostersWithTitlesHidden() {
        val settings = KoinPlatform.getKoin().get<SettingsViewModel>()
        val detailRatingsBefore = settings.state.value.ratingPrefs.showRatingsOnDetailPage
        val label = composeRule.activity.getString(R.string.tv_ratings_show_on_posters)
        composeRule.setContent {
            MaterialTheme {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(400.dp)) {
                        TvRatingsSettingsScreen(FocusRequester(), {}, {}, {}, FocusRequester(), settingsViewModel = settings)
                    }
                    Box(Modifier.weight(1f)) {
                        TvMediaRails(
                            rails = listOf(testRail()), railFocusRequester = FocusRequester(),
                            onMediaClick = {}, onFirstContentRequester = {}, onContentFocused = {},
                            browseLayout = TvBrowseLayout.INFO_PANEL, shouldAutoFocus = false,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("tv_poster_ratings:toggle-movie", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("catalog_poster_caption:toggle-movie", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithTag("tv_poster_ratings:toggle-movie", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(uiPrefs.getBoolean(TV_SHOW_POSTER_RATINGS_KEY, true))
        assertEquals(detailRatingsBefore, settings.state.value.ratingPrefs.showRatingsOnDetailPage)
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithTag("tv_poster_ratings:toggle-movie", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(detailRatingsBefore, settings.state.value.ratingPrefs.showRatingsOnDetailPage)
    }

    @Test fun landscapeRatingsDisappearWithoutRemovingTheTitle() {
        var ratingsEnabled by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                com.torve.android.tv.components.TvBackdropCard(
                    item = testItem(), width = 292.dp, onClick = {}, onFocused = {},
                    ratingPrefs = com.torve.domain.model.RatingDisplayPrefs(),
                    showTitles = true, showRatings = ratingsEnabled,
                )
            }
        }
        composeRule.onNodeWithTag("tv_poster_ratings:toggle-movie", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("backdrop_caption:toggle-movie", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { ratingsEnabled = false }
        composeRule.onNodeWithTag("tv_poster_ratings:toggle-movie", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("backdrop_caption:toggle-movie", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun landscapeRatingsCanRenderWithTitlesHidden() {
        composeRule.setContent {
            MaterialTheme {
                com.torve.android.tv.components.TvBackdropCard(
                    item = testItem(), onClick = {}, onFocused = {},
                    ratingPrefs = com.torve.domain.model.RatingDisplayPrefs(), showTitles = false,
                )
            }
        }
        composeRule.onNodeWithTag("backdrop_caption:toggle-movie", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("tv_poster_ratings:toggle-movie", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun testRail() = TvContentRail("toggle-rail", "Movies", listOf(testItem()), TvCardStyle.POSTER)
    private fun testItem() = MediaItem(
        id = "toggle-movie", title = "Poster ratings toggle fixture", type = MediaType.MOVIE,
        posterUrl = "https://example.invalid/poster.jpg", backdropUrl = "https://example.invalid/backdrop.jpg",
        ratings = MediaRatings(imdbScore = 7.8f, rottenTomatoesScore = 84), rating = 7.4,
    )
}
