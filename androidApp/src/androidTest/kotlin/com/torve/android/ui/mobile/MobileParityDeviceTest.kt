package com.torve.android.ui.mobile

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.*
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.settings.*
import com.torve.android.ui.components.*
import com.torve.android.ui.navigation.rememberMobileDetailSession
import com.torve.android.ui.settings.AppFeaturesSettingsCard
import com.torve.android.ui.settings.RatingSettingsScreen
import com.torve.domain.model.*
import com.torve.presentation.detail.DetailViewModel
import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.OptionalAppFeatures
import org.junit.*
import org.junit.Assert.*

class MobileParityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private var hadPreference = false
    private var originalValue = true
    private val prefs get() = rule.activity.getSharedPreferences("tv_ui_prefs", Context.MODE_PRIVATE)

    private fun assertVisible(tag: String) {
        try { rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed() }
        catch (failure: AssertionError) {
            println(rule.onRoot(useUnmergedTree = true).printToString())
            val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val file = java.io.File(rule.activity.getExternalFilesDir(null), "$tag.png")
            java.io.FileOutputStream(file).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        }
    }

    @Before fun savePreference() {
        hadPreference = prefs.contains(TV_SHOW_POSTER_RATINGS_KEY)
        originalValue = prefs.getBoolean(TV_SHOW_POSTER_RATINGS_KEY, true)
        setTvPosterRatingsEnabled(rule.activity, true)
    }
    @After fun restorePreference() {
        val editor = prefs.edit()
        if (hadPreference) editor.putBoolean(TV_SHOW_POSTER_RATINGS_KEY, originalValue)
        else editor.remove(TV_SHOW_POSTER_RATINGS_KEY)
        editor.commit()
    }

    private val movie = MediaItem(id = "phone-parity", type = MediaType.MOVIE,
        title = "Visible title", ratings = MediaRatings(imdbScore = 8.1f))
    private fun ratingPrefs(position: RatingPillPosition = RatingPillPosition.INSIDE) =
        RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB),
            providerOrder = listOf(RatingSource.IMDB), pillPosition = position,
            showRatingsOnDetailPage = false, showTorveScoreOnCards = false)

    @Test fun actualSettingsTogglePersistsAndUpdatesPosterWithTitlesHidden() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRatingPrefs provides ratingPrefs()) {
                    Column {
                        Box(Modifier.weight(1f)) { RatingSettingsScreen(onBack = {}) }
                        PosterCard(movie, onClick = {}, showTitle = false)
                    }
                }
            }
        }
        assertVisible("rating_chip_IMDB")
        rule.onNodeWithTag("mobile_poster_ratings_toggle").performClick()
        rule.onNodeWithTag("rating_chip_IMDB", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { assertFalse(prefs.getBoolean(TV_SHOW_POSTER_RATINGS_KEY, true)) }
        rule.onNodeWithTag("mobile_poster_ratings_toggle").performClick()
        rule.onNodeWithTag("rating_chip_IMDB", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun outsidePosterRatingsRespectToggleAndLeaveTitleVisible() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRatingPrefs provides ratingPrefs(RatingPillPosition.OUTSIDE)) {
                    PosterCard(movie, onClick = {}, showTitle = true)
                }
            }
        }
        assertVisible("poster_ratings_outside")
        rule.runOnIdle { setTvPosterRatingsEnabled(rule.activity, false) }
        rule.onNodeWithTag("poster_ratings_outside", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Visible title").assertIsDisplayed()
    }

    @Test fun optionalFeatureControlsUpdateIndependently() {
        var flags by mutableStateOf(OptionalAppFeatures())
        val changed = mutableListOf<OptionalAppFeature>()
        rule.setContent { MaterialTheme { AppFeaturesSettingsCard(flags, true) { feature, enabled ->
            changed += feature
            flags = flags.withEnabled(feature, enabled)
        } } }
        OptionalAppFeature.entries.forEach { feature ->
            rule.onNodeWithTag("mobile_feature_${feature.name.lowercase()}").assertIsOn().performClick().assertIsOff()
        }
        rule.runOnIdle {
            assertEquals(OptionalAppFeature.entries, changed)
            assertEquals(OptionalAppFeatures(false, false, false, false), flags)
        }
    }

    @Test fun unloadedFeaturePreferencesCannotBeChanged() {
        rule.setContent { MaterialTheme { AppFeaturesSettingsCard(OptionalAppFeatures(), false) { _, _ ->
            fail("Unloaded preferences must not be changed")
        } } }
        OptionalAppFeature.entries.forEach {
            rule.onNodeWithTag("mobile_feature_${it.name.lowercase()}").assertIsNotEnabled()
        }
    }

    @Test fun realNavigationRetainsDetailSessionAcrossPlayerAndClearsOnProfileChange() {
        var owner by mutableStateOf("account/profile-a")
        var current: DetailViewModel? = null
        rule.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "detail") {
                    composable("detail") { entry ->
                        val session = rememberMobileDetailSession(entry, owner)
                        SideEffect { current = session }
                        Column {
                            Button(onClick = { nav.navigate("player") }) { Text("Play fixture") }
                            Button(onClick = { owner = "account/profile-b" }) { Text("Switch profile") }
                        }
                    }
                    composable("player") {
                        Button(onClick = { nav.popBackStack() }) { Text("Stop fixture") }
                    }
                }
            }
        }
        rule.waitForIdle()
        val before = current
        assertNotNull(before)
        rule.onNodeWithText("Play fixture").performClick()
        rule.onNodeWithText("Stop fixture").performClick()
        rule.onNodeWithText("Switch profile").assertIsDisplayed()
        rule.runOnIdle { assertSame(before, current) }
        rule.onNodeWithText("Switch profile").performClick()
        rule.runOnIdle { assertNotSame(before, current) }
    }
}
