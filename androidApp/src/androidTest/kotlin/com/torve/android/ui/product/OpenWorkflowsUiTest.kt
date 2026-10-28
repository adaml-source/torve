package com.torve.android.ui.product

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.*
import com.torve.android.ui.system.TorveModalBottomSheet
import com.torve.android.ui.theme.*
import com.torve.domain.model.*
import com.torve.presentation.settings.ThemeMode
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class OpenWorkflowsUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val today = LocalDate(2026, 10, 8)
    private val episode = WatchingEpisode(MediaItem("12", tmdbId = 12, type = MediaType.SERIES, title = "The Gentlemen"),
        2, Episode(5, "A Suburban A-Road"), today, 4, resume = true, positionMs = 1_260_000, progressPercent = .42f)
    private fun capture(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "open-workflows-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun resumeRowOpensExactEpisodeWithPositionAndKeepsBacklogOutOfPrimaryView() {
        var opened: WatchingEpisode? = null
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(upNext = listOf(episode), today = today), {}, { opened = it }, {}, {}) } }
        rule.onNodeWithText("Resume at 21:00").assertIsDisplayed()
        rule.onNodeWithText("Unwatched aired episodes: 4").assertDoesNotExist()
        rule.onNodeWithText("Mark watched").assertDoesNotExist()
        capture("phone-next")
        rule.onNodeWithTag("episode_12_2_5").performClick()
        rule.runOnIdle { assertEquals(episode, opened); assertEquals(1_260_000L, opened?.positionMs) }
    }
    @Test fun episodeMenuRequiresExplicitConfirmationAndCancelPreservesHistory() {
        var marked: WatchingEpisode? = null
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(upNext = listOf(episode), today = today), {}, {}, {}, { marked = it }) } }
        rule.onNodeWithTag("mark_12_2_5").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Unwatched aired episodes: 4").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertNull(marked) }
        rule.onNodeWithTag("episode_12_2_5").assertIsDisplayed()
        rule.onNodeWithTag("mark_12_2_5").performClick()
        rule.onNodeWithTag("episode_mark_confirm").performClick()
        rule.runOnIdle { assertEquals(episode, marked) }
    }
    @Test fun upcomingEpisodesOfferDetailsWithoutWatchedControls() {
        val future = episode.copy(date = LocalDate(2026, 10, 9), resume = false, positionMs = null, progressPercent = null)
        rule.setContent { TorveTheme { MobileWatchingHubContent(WatchingHubState(calendar = listOf(future), today = today), {}, {}, {}, { fail("Upcoming episode was marked") }, initialCalendar = true) } }
        rule.onNodeWithText("Tomorrow").assertIsDisplayed()
        rule.onNodeWithText("Episode details").assertIsDisplayed()
        rule.onNodeWithTag("mark_12_2_5").assertDoesNotExist()
    }
    @Test fun ratingsExplanationAppearsOnlyWhenRequestedAndClosesWithoutChangingRatings() {
        rule.setContent { TorveTheme { DetailRatingsAttribution(MediaRatings(tmdbScore = 8.2f), RatingDisplayPrefs(enabledProviders = listOf(RatingSource.TMDB))) } }
        rule.onNodeWithText("Ratings shown from", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("ratings_about").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Ratings shown from", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithTag("ratings_about").assertIsDisplayed()
    }
    @Test fun darkSheetsKeepLightIconsAndRemoveAndroidContrastScrim() {
        var sheetWindow: Window? = null
        var open by mutableStateOf(true)
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            if (open) TorveModalBottomSheet(onDismissRequest = { open = false }) {
                val view = LocalView.current
                SideEffect { sheetWindow = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window }
                Text("Sheet controls", Modifier.padding(24.dp))
            }
        } }
        rule.onNodeWithText("Sheet controls").assertIsDisplayed()
        rule.runOnIdle {
            val window = requireNotNull(sheetWindow)
            if (Build.VERSION.SDK_INT >= 29) assertFalse(window.isNavigationBarContrastEnforced)
            assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars)
        }
        capture("dark-sheet")
        Espresso.pressBack()
        rule.onNodeWithText("Sheet controls").assertDoesNotExist()
    }
}
