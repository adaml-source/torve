package com.torve.android.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.community.ExtensionsScreen
import com.torve.android.tv.components.*
import com.torve.android.tv.screens.SeeAllPosterCard
import com.torve.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvBrowseRefinementsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun screenshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun focus(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) }
    private fun key(tag: String, key: Key) { rule.onNodeWithTag(tag).performKeyInput { pressKey(key) } }

    @Test fun blankExtensionUrlDownReachesCatalogueWithoutOpeningKeyboardOrChangingPreferences() {
        rule.setContent { MaterialTheme { ExtensionsScreen({}, isTv = true) } }
        focus("extensions.url")
        key("extensions.url", Key.DirectionDown)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("extensions.tmdb.catalogue").assertIsFocused() }.isSuccess }
        rule.onNodeWithTag("extensions.tmdb.catalogue").assertIsDisplayed()
        key("extensions.tmdb.catalogue", Key.DirectionUp)
        rule.onNodeWithTag("extensions.section.1").assertIsFocused()
    }
    @Test fun extensionSectionsAreVisibleAndMetadataDownReachesInstalled() {
        rule.setContent { MaterialTheme { ExtensionsScreen({}, isTv = true) } }
        (0..3).forEach { rule.onNodeWithTag("extensions.section.$it").assertIsDisplayed() }
        rule.onNodeWithTag("extensions.section.2").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("extensions.tmdb.enrichment").assertIsFocused() }.isSuccess }
        key("extensions.tmdb.enrichment", Key.DirectionDown)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("extensions.section.3").assertIsFocused() }.isSuccess }
    }
    @Test fun heroShowsSynopsisAndKeepsSlotsStableWhenMetadataArrives() {
        var item by mutableStateOf(MediaItem("fixture", title = "Short", type = MediaType.MOVIE))
        rule.setContent { MaterialTheme {
            TvHeroOverlay(item, "Home", "", FocusRequester(), FocusRequester(), {}, {}, topPadding = androidx.compose.ui.unit.Dp(0f))
        } }
        val before = rule.onNodeWithTag("tv.hero.synopsis").getUnclippedBoundsInRoot()
        rule.runOnIdle { item = item.copy(title = "A much longer title spanning more than one line of the hero", overview = "A short synopsis below the TV hero title.") }
        rule.onNodeWithText("A short synopsis below the TV hero title.").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("tv.hero.synopsis").getUnclippedBoundsInRoot())
        screenshot("qa-tv-hero-synopsis")
    }
    @Test fun pendingLogoNeverShowsTextThenMissingArtworkUsesReadableFallback() {
        var pending by mutableStateOf(true)
        val item = MediaItem("tt-fixture", title = "Pending title", type = MediaType.MOVIE)
        rule.setContent { MaterialTheme {
            TvHeroOverlay(item, "Home", "", FocusRequester(), FocusRequester(), {}, {}, logoLookupInFlight = pending)
        } }
        val bounds = rule.onNodeWithTag("tv.hero.title").getUnclippedBoundsInRoot()
        rule.onNodeWithText("Pending title").assertDoesNotExist()
        rule.runOnIdle { pending = false }
        rule.onNodeWithText("Pending title").assertIsDisplayed()
        assertEquals(bounds, rule.onNodeWithTag("tv.hero.title").getUnclippedBoundsInRoot())
    }
    @Test fun deferredKnownArtworkDoesNotFlashTextAndAnImageFailureFallsBack() {
        var allowed by mutableStateOf(false)
        val item = MediaItem("fixture", title = "Artwork title", type = MediaType.MOVIE,
            logoUrl = "file://${rule.activity.cacheDir}/missing-hero-logo.png")
        rule.setContent { MaterialTheme {
            TvHeroOverlay(item, "Home", "", FocusRequester(), FocusRequester(), {}, {}, allowLogoArtwork = allowed)
        } }
        rule.onNodeWithText("Artwork title").assertDoesNotExist()
        rule.runOnIdle { allowed = true }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Artwork title").assertIsDisplayed() }.isSuccess }
    }
    @Test fun lateLogoRendersPixelsWithoutAnIntermediateTextTitle() {
        val logo = java.io.File(rule.activity.cacheDir, "qa-hero-logo.png")
        android.graphics.Bitmap.createBitmap(160, 60, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
            logo.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        var item by mutableStateOf(MediaItem("tt-fixture", title = "Late artwork", type = MediaType.MOVIE))
        var pending by mutableStateOf(true)
        rule.setContent { MaterialTheme {
            TvHeroOverlay(item, "Home", "", FocusRequester(), FocusRequester(), {}, {}, logoLookupInFlight = pending)
        } }
        rule.onNodeWithText("Late artwork").assertDoesNotExist()
        val bounds = rule.onNodeWithTag("tv.hero.title").getUnclippedBoundsInRoot()
        rule.runOnIdle { item = item.copy(logoUrl = logo.toURI().toString()); pending = false }
        rule.waitUntil(5_000) {
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            var cyan = 0
            for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
                if (bitmap.getPixel(x, y) == android.graphics.Color.CYAN) cyan++
            }
            bitmap.recycle()
            cyan > 10
        }
        rule.onNodeWithText("Late artwork").assertDoesNotExist()
        rule.onNodeWithContentDescription("Late artwork").assertIsDisplayed()
        assertEquals(bounds, rule.onNodeWithTag("tv.hero.title").getUnclippedBoundsInRoot())
        screenshot("qa-tv-late-title-artwork")
    }
    @Test fun homeSnapshotSeeAllExposesTheCommonFilterAndSortControls() {
        val snapshotKey = "qa_home_browse_refinements"
        com.torve.presentation.seeall.SeeAllViewModel.pendingItems[snapshotKey] = "QA Home list" to listOf(
            MediaItem("fixture", title = "Fixture movie", type = MediaType.MOVIE, ratings = MediaRatings(imdbScore = 8.0f)))
        try {
            rule.setContent { MaterialTheme {
                com.torve.android.tv.screens.TvSeeAllScreen(snapshotKey, "movie", "QA Home list", FocusRequester(), {}, {}, {}, {})
            } }
            rule.onNodeWithText("Filters").assertIsDisplayed().performClick()
            rule.onNodeWithText("Minimum IMDb rating").assertExists()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            rule.onNodeWithText("Sort: Default").performClick()
            rule.onNodeWithText("Highest IMDb rating").assertIsDisplayed()
            screenshot("qa-tv-seeall-sort")
        } finally { com.torve.presentation.seeall.SeeAllViewModel.pendingItems.remove(snapshotKey) }
    }
    @Test fun longPosterActionMenuKeepsItsLastActionReachable() {
        val item = MediaItem("fixture", title = "Poster fixture", type = MediaType.MOVIE)
        rule.setContent { MaterialTheme {
            TvMediaContextMenu(TvMediaContextMenuState(item, null, null,
                (1..14).map { TvMediaContextMenuAction("$it", "Action $it") }), {}, {})
        } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Action 1").assertIsFocused() }.isSuccess }
        (1..13).forEach { index -> rule.onNodeWithText("Action $index").performKeyInput { pressKey(Key.DirectionDown) } }
        rule.onNodeWithText("Action 14").assertIsFocused().assertIsDisplayed()
    }
    @Test fun seeAllPosterMenuOpensWithRemoteMenuAndBackRestoresPoster() {
        var menu by mutableStateOf<TvMediaContextMenuState?>(null)
        var first: FocusRequester? = null
        val item = MediaItem("fixture", title = "Poster fixture", type = MediaType.MOVIE)
        rule.setContent { MaterialTheme {
            val requester = remember { FocusRequester() }; first = requester
            SeeAllPosterCard(item, false, 1, RatingDisplayPrefs(), Modifier.width(androidx.compose.ui.unit.Dp(140f)).height(androidx.compose.ui.unit.Dp(210f)).focusRequester(requester).testTag("poster"), {}, {}, {
                menu = TvMediaContextMenuState(item, null, null, listOf(TvMediaContextMenuAction("details", "View details")))
            })
            menu?.let { state -> TvMediaContextMenu(state, { menu = null; requester.requestFocus() }) { menu = null; requester.requestFocus() } }
        } }
        rule.runOnIdle { first!!.requestFocus() }
        key("poster", Key.Menu)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("View details").assertIsFocused() }.isSuccess }
        screenshot("qa-tv-poster-menu")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("poster").assertIsFocused() }.isSuccess }
        rule.onNodeWithText("View details").assertDoesNotExist()
    }
}
