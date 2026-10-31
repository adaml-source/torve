package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.*
import com.torve.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvExtensionSearchUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun fixture(model: TvExtensionSearchState, items: List<MediaItem> = emptyList(), preview: Boolean = false) {
        model.sourceCount = 1
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { MaterialTheme { TvExtensionSearchContent(model, items, FocusRequester(), {}, {}, {}, true, {}, focusPreview = preview) } }
    }
    private val navigationItems = (0..36).map { MediaItem("fixture$it", type = MediaType.MOVIE, title = "Fixture $it", year = 2026) }

    @Test fun tenPosterRowsWrapAndKeepVerticalAndHeaderNavigation() {
        fixture(TvExtensionSearchState().apply { query = "fixture" }, navigationItems)
        rule.onNodeWithText("fixture").performKeyInput { pressKey(Key.DirectionDown) }
        fun poster(index: Int) = rule.onNodeWithTag("search_result:fixture$index")
        fun key(index: Int, key: Key) { poster(index).performKeyInput { pressKey(key) } }
        poster(0).assertIsFocused()
        for (index in 0..8) key(index, Key.DirectionRight)
        poster(9).assertIsFocused()
        val first = poster(0).fetchSemanticsNode().boundsInRoot
        val last = poster(9).fetchSemanticsNode().boundsInRoot
        assertEquals("Ten posters must share a row", first.top, last.top, 2f)
        key(9, Key.DirectionRight); poster(0).assertIsFocused()
        key(0, Key.DirectionDown); poster(10).assertIsFocused()
        for (index in 10..18) key(index, Key.DirectionRight)
        key(19, Key.DirectionRight); poster(10).assertIsFocused()
        key(10, Key.DirectionDown); poster(20).assertIsFocused()
        key(20, Key.DirectionDown); poster(30).assertIsFocused()
        for (index in 30..35) key(index, Key.DirectionRight)
        key(36, Key.DirectionRight); poster(30).assertIsFocused()
        key(30, Key.DirectionUp); key(20, Key.DirectionUp); key(10, Key.DirectionUp)
        key(0, Key.DirectionUp); rule.onNodeWithText("fixture").assertIsFocused()
        rule.onNodeWithText("fixture").performKeyInput { pressKey(Key.DirectionDown) }
        poster(0).assertIsFocused()
    }

    @Test fun previewRowsWrapAndHeaderReturnsToTheActiveRow() {
        fixture(TvExtensionSearchState().apply { query = "fixture" }, navigationItems, preview = true)
        fun poster(index: Int) = rule.onNodeWithTag("catalog_poster_card:fixture$index")
        fun waitFor(index: Int) { rule.waitUntil(10_000) { runCatching { poster(index).assertIsFocused() }.isSuccess } }
        fun key(index: Int, key: Key) { poster(index).performKeyInput { pressKey(key) }; rule.waitForIdle() }
        rule.onNodeWithText("fixture").performKeyInput { pressKey(Key.DirectionDown) }; waitFor(0)
        for (index in 0..8) { key(index, Key.DirectionRight); waitFor(index + 1) }
        key(9, Key.DirectionUp); rule.onNodeWithText("fixture").assertIsFocused()
        rule.onNodeWithText("fixture").performKeyInput { pressKey(Key.DirectionDown) }; waitFor(9)
        key(9, Key.DirectionRight); waitFor(0)
        key(0, Key.DirectionDown); waitFor(10)
        // A header focus request must target the expanded row, even after row 0 collapsed.
        rule.onNodeWithText("fixture").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithText("fixture").performKeyInput { pressKey(Key.DirectionDown) }; waitFor(10)
        key(10, Key.DirectionDown); waitFor(20)
        key(20, Key.DirectionDown); waitFor(30)
        for (index in 30..35) { key(index, Key.DirectionRight); waitFor(index + 1) }
        key(36, Key.DirectionRight); waitFor(30)
        key(30, Key.DirectionUp); waitFor(20)
    }
    @Test fun filtersAreAvailableBeforeSearchingAndPickerBackKeepsFocus() {
        fixture(TvExtensionSearchState())
        rule.onNodeWithText("Filters").assertIsDisplayed().performClick()
        rule.onNodeWithText("Genre: All genres").assertIsFocused()
        rule.onNodeWithText("Genre: All genres").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Release year from: Any").performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText("Any").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("Release year from: Any").assertIsFocused()
    }
    @Test fun imdbFilterIsRemoteReachableAndApplyReturnsToToolbarThenResults() {
        val state = TvExtensionSearchState().apply { query = "fixture" }
        val high = MediaItem("good", type = MediaType.MOVIE, title = "High IMDb", ratings = MediaRatings(imdbScore = 8.2f, rottenTomatoesScore = 90))
        fixture(state, listOf(high, high.copy(id = "tmdb", title = "Only TMDB", ratings = MediaRatings(tmdbScore = 9.9f))))
        rule.onNodeWithText("Filters").performClick()
        rule.onNodeWithText("Minimum IMDb rating: Any").performClick()
        var previous = "Any"
        for (step in 1..14) {
            rule.onNodeWithText(previous).performKeyInput { pressKey(Key.DirectionDown) }
            previous = "${step / 2f} / 10"
            rule.onNodeWithText(previous).assertIsFocused()
        }
        rule.onNodeWithText("7.0 / 10").performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText("Minimum IMDb rating: 7.0 / 10").assertIsFocused()
        rule.onNodeWithText("Apply filters").performClick()
        rule.onNodeWithText("Filters (1)").assertIsFocused()
        rule.onNodeWithText("Only TMDB").assertDoesNotExist()
        rule.onNodeWithText("Filters (1)").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("High IMDb").assertIsFocused()
    }
    @Test fun providerErrorsUseWideReadablePanelAndOfferTitleSearch() {
        val state = TvExtensionSearchState().apply {
            query = "movies with an actor on a beach"; aiMode = true; hasAi = true
            error = "The selected Gemini Model is invalid or not found. Please try a different model in the extension settings."
        }
        fixture(state)
        val bounds = rule.onNodeWithTag("search.provider.notice").getUnclippedBoundsInRoot()
        assertTrue("Error panel must be wide, not a poster or circular chip: $bounds", (bounds.right - bounds.left).value > (bounds.bottom - bounds.top).value * 2)
        rule.onNodeWithText(state.error!!).assertIsDisplayed()
        rule.onNodeWithText(state.query).performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Use title search").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertFalse(state.aiMode) }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(rule.activity.getExternalFilesDir(null), "qa-extension-search-error.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
