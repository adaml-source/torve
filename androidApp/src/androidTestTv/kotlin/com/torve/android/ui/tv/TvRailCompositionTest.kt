package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.torve.android.TorveApp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvFocusMemory
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvRailCompositionTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Before fun waitForDependencies() {
        assertTrue((rule.activity.application as TorveApp).koinReady.await(15, TimeUnit.SECONDS))
    }

    private val rails = (0 until 20).map { row ->
        TvContentRail("row-$row", "Row $row", (0 until 12).map { item ->
            MediaItem(
                id = "row-$row-item-$item", tmdbId = row * 100 + item + 1,
                title = "Card $row/$item", type = MediaType.MOVIE,
            )
        })
    }

    private fun card(row: Int, item: Int) = rule.onNode(hasText("Card $row/$item") and hasClickAction())

    private fun waitForFocus(row: Int, item: Int) {
        rule.waitUntil(5_000) {
            runCatching { card(row, item).assertIsFocused().assertIsDisplayed() }.isSuccess
        }
    }

    @Test fun collapsedRowsDoNotComposeCards() {
        val evaluatedRows = mutableSetOf<String>()
        val memory = TvFocusMemory().apply { lastFocusedRowKey = "row-7" }
        rule.setContent {
            MaterialTheme {
                TvMediaRails(
                    rails, remember { FocusRequester() }, onMediaClick = {},
                    onFirstContentRequester = {}, onContentFocused = {},
                    focusMemory = memory, shouldAutoFocus = false,
                    presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                    browseLayout = TvBrowseLayout.POSTER_ONLY, forcePosterTitles = true,
                    heroOverlay = { Text("Movies") }, showSeeAllCards = false,
                    progressResolver = { item, progress ->
                        evaluatedRows += item.id.substringBefore("-item-")
                        progress
                    },
                )
            }
        }
        rule.waitUntil(5_000) { "row-7" in evaluatedRows }
        rule.runOnIdle {
            assertTrue("Collapsed rows built card trees: $evaluatedRows",
                evaluatedRows.all { it == "row-7" || it == "row-8" })
        }
    }

    @Test fun disposingAndReenteringATabRestoresItsRowAndScrolledCard() {
        var mounted by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                val holder = rememberSaveableStateHolder()
                if (mounted) holder.SaveableStateProvider("movies") {
                    val memory = rememberTvFocusMemory()
                    var enter by remember { mutableStateOf(true) }
                    TvMediaRails(
                        rails, remember { FocusRequester() }, onMediaClick = {},
                        onFirstContentRequester = {}, onContentFocused = { enter = false },
                        focusMemory = memory, shouldAutoFocus = enter,
                        screenId = "tab_reentry", presentationMode = TvRailsPresentationMode.CatalogHero,
                        focusExclusive = true, browseLayout = TvBrowseLayout.POSTER_ONLY,
                        forcePosterTitles = true, heroOverlay = { Text("Movies") }, showSeeAllCards = false,
                    )
                } else Text("Other tab")
            }
        }
        waitForFocus(0, 0)
        card(0, 0).performKeyInput { pressKey(Key.DirectionDown) }
        waitForFocus(1, 0)
        for (item in 1..7) {
            card(1, item - 1).performKeyInput { pressKey(Key.DirectionRight) }
            waitForFocus(1, item)
        }
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        rule.onNode(hasText("Other tab")).assertIsDisplayed()
        rule.runOnIdle { mounted = true }
        waitForFocus(1, 7)
        card(1, 7).performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocus(0, 0)
    }
}
