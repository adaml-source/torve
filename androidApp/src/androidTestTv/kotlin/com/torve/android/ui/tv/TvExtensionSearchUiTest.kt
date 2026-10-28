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
    private fun fixture(model: TvExtensionSearchState, items: List<MediaItem> = emptyList()) {
        model.sourceCount = 1
        rule.setContent { MaterialTheme { TvExtensionSearchContent(model, items, FocusRequester(), {}, {}, {}, true, {}) } }
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
