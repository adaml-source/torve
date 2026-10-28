package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.screens.TvCatalogRailsScreen
import com.torve.android.tv.settings.setTvExpandingPosters
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TvCatalogDiscoveryParityTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    @org.junit.Before fun keepScreenAwake() { rule.runOnUiThread {
        rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } }

    @Test fun moviesHaveIosGenreShortcutsAndRemoteHandoff() = checkPage("movie", 28, "Action")
    @Test fun showsHaveIosGenreShortcutsAndRemoteHandoff() = checkPage("tv", 10759, "Action & Adventure")

    private fun checkPage(type: String, genre: Int, name: String) {
        val settings = org.koin.core.context.GlobalContext.get().get<com.torve.presentation.settings.SettingsViewModel>()
        val previous = settings.state.value.tmdbCatalogueEnabled
        var destination: Pair<String, String>? = null
        try {
            rule.runOnUiThread { settings.setTmdbCatalogueEnabled(true) }
            rule.setContent { MaterialTheme {
                TvCatalogRailsScreen(type, FocusRequester(), null, {}, {}, {},
                    onSeeAll = { key, title -> destination = key to title }, shouldAutoFocus = true)
            } }
            rule.waitUntil(30_000) {
                rule.onAllNodes(isFocused()).fetchSemanticsNodes().any {
                    val tag = it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }
                    tag.startsWith("catalog_poster_card:") || tag == "catalog_browse_search"
                }
            }
            // CatalogHero intentionally collapses the browse header while a poster
            // owns focus. Reveal it through the same UP action as the TV remote.
            if (rule.onAllNodesWithTag("catalog_browse_search").fetchSemanticsNodes().none {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
            }) rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
            val search = rule.onNodeWithTag("catalog_browse_search")
            rule.waitUntil(5_000) { runCatching { search.assertIsFocused().assertIsDisplayed() }.isSuccess }
            rule.onNodeWithText("Browse by genre").assertIsDisplayed()
            search.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            val chip = rule.onNodeWithTag("catalog_genre:$genre")
            chip.assertIsFocused().assertTextContains(name).performKeyInput { pressKey(Key.DirectionCenter) }
            rule.runOnIdle { assertEquals("genre_${type}_$genre" to name, destination) }
            screenshot("catalog-$type-genres")
            chip.performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(5_000) {
                rule.onAllNodes(isFocused()).fetchSemanticsNodes().any {
                    it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("catalog_poster_card:")
                }
            }
            rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitUntil(5_000) { runCatching { search.assertIsFocused().assertIsDisplayed() }.isSuccess }
            search.performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(5_000) { rule.onAllNodes(isFocused()).fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("catalog_poster_card:")
            } }
            rule.onNodeWithText("Trending").assertIsDisplayed()
            rule.onAllNodesWithText(if (type == "movie") "Trending Movies" else "Trending TV Shows").assertCountEquals(0)
            screenshot("catalog-$type-rail")
        } catch (failure: Throwable) {
            screenshot("catalog-$type-failure")
            File(rule.activity.getExternalFilesDir(null), "catalog-$type-failure-tree.txt")
                .writeText(rule.onRoot(useUnmergedTree = true).printToString())
            throw failure
        } finally { rule.runOnUiThread { settings.setTmdbCatalogueEnabled(previous) } }
    }

    @Test fun lateTrendingRowKeepsPopularSelectionWhenIosOrderingIsApplied() {
        val prefs = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
        val old = prefs.getString("tv_browse_layout", "POSTER_ONLY") == "EXPANDING"
        var arrived by mutableStateOf(false)
        val popular = TvContentRail("popular_movie", "Popular", listOf(MediaItem("popular-film", type = MediaType.MOVIE, title = "Popular film")))
        val trending = TvContentRail("trending_movie", "Trending", listOf(MediaItem("trending-film", type = MediaType.MOVIE, title = "Trending film")))
        try {
            setTvExpandingPosters(rule.activity, true)
            rule.setContent { MaterialTheme {
                TvMediaRails(if (arrived) listOf(trending, popular) else listOf(popular), FocusRequester(), {}, {}, {},
                    screenId = "catalog-order-arrival", browseLayout = TvBrowseLayout.POSTER_ONLY,
                    presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                    showSeeAllCards = false, forcePosterTitles = true)
            } }
            val selected = rule.onNodeWithTag("catalog_poster_card:popular-film")
            rule.waitUntil(5_000) { runCatching { selected.assertIsFocused() }.isSuccess }
            rule.runOnIdle { arrived = true }
            selected.assertIsFocused().assertIsDisplayed()
            selected.performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("catalog_poster_card:trending-film").assertIsFocused().assertIsDisplayed() }.isSuccess }
        } finally { setTvExpandingPosters(rule.activity, old) }
    }

    private fun screenshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
