package com.torve.android.ui.product

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.home.RecommendationFeedbackSheet
import com.torve.android.ui.theme.TorveTheme
import com.torve.domain.model.*
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpgradeGapUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun capture(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(350)
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(rule.activity.getExternalFilesDir(null), "gaps-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun recommendationFeedbackRequiresTheExplicitHideAction() {
        var hidden = 0; var reset = 0; var dismissed = 0
        rule.setContent { TorveTheme { RecommendationFeedbackSheet(MediaItem("1", type = MediaType.MOVIE, title = "Film"),
            { dismissed++ }, { hidden++ }, { reset++ }) } }
        capture("recommendation-feedback")
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, hidden); assertEquals(0, reset); assertEquals(1, dismissed)
        rule.onNodeWithText("Not interested").performClick()
        assertEquals(1, hidden)
    }
    @Test fun episodeAlertsOfferAManualCheckWithoutChangingThePreference() {
        var checks = 0; var changes = 0
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(episodeNotificationsEnabled = true),
            {}, {}, {}, {}, onAlerts = { changes++ }, onCheckAlerts = { checks++ }) } }
        rule.onNodeWithTag("shows_options").performClick()
        capture("alert-options")
        rule.onNodeWithTag("shows_alerts_check").performScrollTo().performClick()
        assertEquals(1, checks); assertEquals(0, changes)
    }
    @Test fun returningViewerHomeLimitsTheQueueAndKeepsExactEpisodeNavigation() {
        var selected: WatchingEpisode? = null
        val entries = (1..4).map { WatchingEpisode(MediaItem("$it", tmdbId = it, type = MediaType.SERIES, title = "Show $it"),
            2, Episode(3, "Episode"), LocalDate(2026, 10, 8), resume = true, positionMs = 60000, progressPercent = .1f) }
        rule.setContent { TorveTheme { MobileUpNextShelf(WatchingHubState(upNext = entries), { selected = it }, {}, returningViewer = true) } }
        rule.onNodeWithText("Show 4").assertDoesNotExist()
        capture("returning-viewer-queue")
        rule.onNodeWithText("Show 1").performClick()
        assertEquals(2, selected?.season); assertEquals(3, selected?.episode?.episodeNumber)
        assertEquals(60000L, selected?.positionMs)
    }
}
