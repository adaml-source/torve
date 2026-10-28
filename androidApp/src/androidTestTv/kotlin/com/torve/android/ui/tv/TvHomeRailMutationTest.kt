package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.torve.android.TorveApp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvHomeRailMutationTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private var rows by mutableStateOf(listOf(row("continue", 1, 2, 3), row("watchlist", 4, 5)))

    @Before fun waitForApplicationDependencies() {
        assertTrue((rule.activity.application as TorveApp).koinReady.await(15, TimeUnit.SECONDS))
    }

    private fun row(key: String, vararg ids: Int) = TvContentRail(key, key,
        ids.map { MediaItem(it.toString(), tmdbId = it, type = MediaType.MOVIE, title = "Card $it") })
    private fun node(id: Int) = rule.onNode(hasText("Card $id") and hasClickAction())
    private fun focused(id: Int) {
        try {
            rule.waitUntil(5_000) { runCatching { node(id).assertIsFocused().assertIsDisplayed(); true }.getOrDefault(false) }
        } catch (failure: Throwable) {
            throw AssertionError("Card $id failed to receive visible focus.\n${rule.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
        val viewport = rule.onNodeWithTag("catalog_rails_viewport").fetchSemanticsNode().boundsInRoot
        val card = node(id).fetchSemanticsNode().boundsInRoot
        assertTrue("Focused card must fit the usable viewport: $card in $viewport", card.top >= viewport.top && card.bottom <= viewport.bottom)
    }
    private fun openRows() {
        rule.setContent {
            MaterialTheme {
                var enter by remember { mutableStateOf(true) }
                TvMediaRails(
                    rails = rows, railFocusRequester = remember { FocusRequester() },
                    onMediaClick = {}, onFirstContentRequester = {}, onContentFocused = { enter = false },
                    screenId = "home_mutation", presentationMode = TvRailsPresentationMode.CatalogHero,
                    focusExclusive = true, shouldAutoFocus = enter, browseLayout = TvBrowseLayout.POSTER_ONLY,
                    heroOverlay = { Text("Home") },
                )
            }
        }
        focused(1)
        node(1).performKeyInput { pressKey(Key.DirectionRight) }
        focused(2)
    }

    @Test fun insertingAvailabilityAndReorderingResumeItemsKeepsTheActualFocusedTitleVisible() {
        openRows()
        rule.runOnIdle { rows = listOf(row("available", 9), row("continue", 3, 1, 2), row("watchlist", 4, 5)) }
        rule.waitForIdle()
        focused(2)
        node(2).performKeyInput { pressKey(Key.DirectionDown) }
        focused(4)
    }

    @Test fun removingTheFocusedTitleSelectsAVisibleSiblingAndRemoteNavigationStillWorks() {
        openRows()
        rule.runOnIdle { rows = listOf(row("continue", 1, 3), row("watchlist", 4, 5)) }
        rule.waitForIdle()
        focused(3)
        node(3).performKeyInput { pressKey(Key.DirectionLeft) }
        focused(1)
        node(1).performKeyInput { pressKey(Key.DirectionDown) }
        focused(4)
    }

    @Test fun repeatedVerticalMovesKeepEveryDestinationFullyVisible() {
        openRows()
        repeat(6) {
            node(2).performKeyInput { pressKey(Key.DirectionDown) }
            focused(4)
            node(4).performKeyInput { pressKey(Key.DirectionUp) }
            focused(2)
        }
    }
}
