package com.torve.android.ui.product

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.*
import com.torve.android.ui.home.MobilePlaybackShortcut
import com.torve.android.ui.home.HomeSearchBar
import com.torve.android.ui.settings.ProviderStatusList
import com.torve.android.ui.settings.StatusRepairNavigationHeader
import com.torve.android.ui.theme.*
import com.torve.android.ui.transfer.providerSettingsRouteFor
import com.torve.domain.model.*
import com.torve.domain.providerhealth.*
import com.torve.presentation.providerhealth.ProviderStatusMapper
import com.torve.presentation.watching.WatchingEpisode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Fixtures exercise controls without touching household watch history or requesting media. */
class MobilePlaybackPriorityUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val progress = WatchProgress("tmdb:1418", MediaType.SERIES, "The Big Bang Theory",
        positionMs = 300_000, durationMs = 1_200_000, seasonNumber = 3, episodeNumber = 11)
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "playback-priority-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun checkFullActionSheet(fontScale: Float, screenshotName: String) {
        var changes = 0
        val labels = listOf("Add to Watchlist", "Add to Favorites", "Trakt lists",
            "Mark show watched", "Clear show history", "Your rating")
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TorveTheme {
                    DetailSecondaryActions("The Big Bang Theory", labels.map {
                        DetailSecondaryActionSpec(it, Icons.Rounded.Star, Charcoal, Snow, onClick = { changes++ })
                    }, onTrailer = {})
                }
            }
        }
        rule.onNodeWithTag("detail_more").performClick()
        labels.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        val close = rule.onNodeWithTag("detail_more_close")
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val bottom = close.fetchSemanticsNode().boundsInWindow.bottom
        rule.runOnIdle {
            val decor = rule.activity.window.decorView
            val navigationBar = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            assertTrue("Close overlaps the navigation bar: $bottom", bottom <= decor.height - navigationBar)
            assertEquals(0, changes)
        }
        capture(screenshotName)
        close.performClick()
        rule.onNodeWithText("Your rating").assertDoesNotExist()
        rule.onNodeWithTag("detail_more").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun fullActionSheetFooterStaysAboveSystemNavigation() = checkFullActionSheet(1f, "full-menu")

    @Test fun fullActionSheetRemainsUsableWithLargeText() = checkFullActionSheet(1.5f, "full-menu-large-text")

    @Test fun resumeShortcutShowsExactEpisodeBeforeDiscoveryAndOpensThatTarget() {
        var chosen: WatchProgress? = null
        rule.setContent { TorveTheme { Surface { Column {
            MobilePlaybackShortcut(progress, null, { chosen = it }, { fail("Wrong target") }, Modifier.padding(16.dp))
            Text("Discover", Modifier.fillMaxWidth().height(400.dp))
        } } } }
        rule.onNodeWithTag("home_playback_shortcut").assertIsDisplayed().performClick()
        rule.onNodeWithText("Resume · S03E11").assertIsDisplayed()
        rule.runOnIdle { assertEquals(progress, chosen) }
        capture("resume")
    }

    @Test fun nextEpisodeShortcutDoesNotClaimItIsResume() {
        val next = WatchingEpisode(MediaItem("12", tmdbId = 12, type = MediaType.SERIES, title = "The Gentlemen"),
            2, Episode(5, "Episode title"), null, 4)
        var chosen: WatchingEpisode? = null
        rule.setContent { TorveTheme { MobilePlaybackShortcut(null, next, { fail("Wrong target") }, { chosen = it }) } }
        rule.onNodeWithText("Watch now · S02E05").assertIsDisplayed()
        rule.onNodeWithTag("home_playback_shortcut").performClick()
        rule.runOnIdle { assertEquals(next, chosen) }
    }

    @Test fun searchClearAndCloseHaveUsableTargetsAndDifferentEffects() {
        var query by mutableStateOf("")
        var visible by mutableStateOf(true)
        var closes = 0
        rule.setContent { TorveTheme {
            if (visible) HomeSearchBar(query, { query = it }, { query = "" }, { closes++; visible = false })
        } }
        rule.onNode(hasSetTextAction()).performTextInput("gent")
        rule.onNodeWithContentDescription("Clear search").assertWidthIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals("", query); assertEquals(0, closes) }
        rule.onNodeWithContentDescription("Close").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, closes); assertFalse(visible) }
    }

    @Test fun moreKeepsActionsHiddenAndBackDismissalDoesNotChangeHistory() {
        var changes = 0
        rule.setContent { TorveTheme { DetailSecondaryActions("The Gentlemen", listOf(
            DetailSecondaryActionSpec("Mark show watched", Icons.Rounded.Visibility, Charcoal, Snow, onClick = { changes++ })
        ), onTrailer = {}) } }
        rule.onNodeWithText("Mark show watched").assertDoesNotExist()
        rule.onNodeWithTag("detail_more").performClick()
        rule.onNodeWithText("Mark show watched").assertIsDisplayed()
        capture("more")
        Espresso.pressBack()
        rule.onNodeWithText("Mark show watched").assertDoesNotExist()
        rule.onNodeWithTag("detail_more").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun cancellingWholeShowChangeLeavesHistoryUntouched() {
        var confirmation by mutableStateOf(true)
        var changes = 0
        rule.setContent { TorveTheme {
            if (confirmation) DetailWatchedConfirmation(true, false, { changes++; confirmation = false }, { confirmation = false })
        } }
        rule.onNode(hasText("Clear show history") and hasClickAction()).assertExists()
        capture("history-confirmation")
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertFalse(confirmation); assertEquals(0, changes) }
    }

    @Test fun wholeShowChangeRequiresExplicitConfirmation() {
        var confirmation by mutableStateOf(true)
        var changes = 0
        rule.setContent { TorveTheme {
            if (confirmation) DetailWatchedConfirmation(true, true, { changes++; confirmation = false }, { confirmation = false })
        } }
        rule.runOnIdle { assertEquals(0, changes) }
        rule.onNode(hasText("Mark show watched") and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(1, changes); assertFalse(confirmation) }
    }

    @Test fun openingAndCancellingRatingDoesNotCycleTheRating() {
        var visible by mutableStateOf(true)
        var saves = 0
        rule.setContent { TorveTheme {
            if (visible) DetailRatingDialog(8, { saves++; visible = false }, { visible = false })
        } }
        rule.onNodeWithText("8/10").assertIsDisplayed()
        capture("rating")
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(0, saves); assertFalse(visible) }
    }

    @Test fun ratingSaveAndRemovalAreExplicit() {
        var result: Int? = null
        var saves = 0
        rule.setContent { TorveTheme { DetailRatingDialog(8, { result = it; saves++ }, {}) } }
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle { assertEquals(8, result); assertEquals(1, saves) }
        rule.onNodeWithText("Remove rating").performClick()
        rule.runOnIdle { assertNull(result); assertEquals(2, saves) }
    }

    @Test fun repairPrioritizesActualFailureAndCollapsesUnusedServices() {
        val arr = ProviderHealthEntry(ProviderHealthCategory.REQUEST_MANAGER, "automation:admin", "ARR automation stack",
            ProviderHealthStatus.RED, 1, "Could not reach Sonarr. Check the network or server address.", failure = ProviderHealthFailure.UNREACHABLE)
        val connected = ProviderHealthEntry(ProviderHealthCategory.DEBRID, "real-debrid", "Real-Debrid", ProviderHealthStatus.GREEN, 1)
        val unused = ProviderHealthEntry(ProviderHealthCategory.SIMKL, "simkl", "SIMKL", ProviderHealthStatus.UNCONFIGURED)
        var refreshed: String? = null
        var configured: String? = null
        rule.setContent { TorveTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            StatusRepairNavigationHeader({}, {})
            ProviderStatusList(ProviderStatusMapper.mapAll(listOf(unused, connected, arr)),
                { configured = it.providerKey }, { refreshed = it.providerKey }, {})
        } } } }
        rule.onNodeWithTag("repair_simkl").assertDoesNotExist()
        rule.onNodeWithText("Server unreachable").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.runOnIdle { assertEquals("automation:admin", refreshed); assertNull(configured) }
        rule.onNodeWithContentDescription("Connection settings · ARR automation stack").performClick()
        rule.runOnIdle { assertEquals("automation:admin", configured) }
        capture("repair")
        rule.onNodeWithTag("repair_unused_toggle").performClick()
        rule.onNodeWithTag("repair_simkl").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Connect").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("simkl", configured) }
        assertEquals("integrations?service=automation", providerSettingsRouteFor(arr))
    }

    @Test fun resumeAndSecondaryActionsRemainUsableAtLargeTextSize() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                TorveTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
                    MobilePlaybackShortcut(progress, null, {}, {})
                    DetailSecondaryActions("The Gentlemen", listOf(
                        DetailSecondaryActionSpec("Your rating", Icons.Rounded.Star, Charcoal, Snow, onClick = {})
                    ), {})
                } } }
            }
        }
        rule.onNodeWithTag("home_playback_shortcut").assertIsDisplayed()
        rule.onNodeWithTag("detail_more").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Your rating").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithTag("detail_more").assertIsDisplayed()
        capture("large-text")
    }
}
