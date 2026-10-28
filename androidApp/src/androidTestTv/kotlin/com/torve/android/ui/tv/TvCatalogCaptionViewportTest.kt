package com.torve.android.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import android.graphics.Bitmap
import java.io.File
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvFocusMemory
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvCatalogCaptionViewportTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun nextRowArtworkStaysBelowItsHeadingWithSmallPosters() { checkPreviewHeading(1f, 8) }
    @Test fun nextRowArtworkStaysBelowItsHeadingWithLargePostersAndText() { checkPreviewHeading(1.5f, 4) }

    private fun checkPreviewHeading(fontScale: Float, columns: Int) {
        val poster = File(composeRule.activity.cacheDir, "qa-peek-poster.png")
        Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
            poster.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val rows = (0..3).map { row -> TvContentRail("peek-$row", "Heading $row",
            listOf(MediaItem("peek-card-$row", title = "Card $row", type = MediaType.MOVIE,
                posterUrl = poster.toURI().toString()))) }
        var headingHeightPx = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    headingHeightPx = with(LocalDensity.current) { MaterialTheme.typography.headlineSmall.lineHeight.toPx() }.toInt()
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        TvMediaRails(rows, FocusRequester(), {}, {}, {}, screenId = "peek-heading-$fontScale",
                            forcePosterTitles = true, presentationMode = TvRailsPresentationMode.CatalogHero,
                            focusExclusive = true, browseLayout = TvBrowseLayout.POSTER_ONLY,
                            posterColumns = columns, showSeeAllCards = false)
                    }
                }
            }
        }
        for (activeRow in 0..2) {
            waitForFocusedTitle("Card $activeRow")
            val preview = composeRule.onNodeWithTag("catalog_rail_peek:peek-${activeRow + 1}")
            // Wait for actual decoded image pixels, so a blank image cannot
            // accidentally make the no-overlap assertion pass.
            composeRule.waitUntil(5_000) {
                runCatching {
                    val pixels = preview.captureToImage().toPixelMap()
                    (0 until pixels.height step 2).any { y ->
                        (0 until pixels.width step 2).any { x ->
                            val color = pixels[x, y]
                            color.red < .1f && color.green > .25f && color.blue > .25f
                        }
                    }
                }.getOrDefault(false)
            }
            val image = preview.captureToImage()
            val pixels = image.toPixelMap()
            val screenshot = File(composeRule.activity.getExternalFilesDir(null), "peek-heading-$fontScale-$activeRow.png")
            screenshot.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (y in 0 until headingHeightPx.coerceAtMost(pixels.height)) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    assertTrue("Poster draws over row ${activeRow + 1} heading at $x,$y ($columns columns, $fontScale text)",
                        !(color.red < .1f && color.green > .25f && color.blue > .25f))
                }
            }
            if (activeRow < 2) composeRule.onNodeWithText("Card $activeRow").performKeyInput { pressKey(Key.DirectionDown) }
        }
    }
    @Test fun calendarKeepsItsAirDateAndBothCaptionLinesInsideTheViewport() {
        val episode = MediaItem("trakt-calendar:42:1:2:2099-10-21T20:00:00Z", tmdbId = 42,
            title = "A series with a long title - S1E2 - Episode name", type = MediaType.SERIES,
            releaseDate = "2099-10-21T20:00:00Z")
        composeRule.setContent { MaterialTheme {
            TvMediaRails(listOf(TvContentRail("upcoming_schedule", "Upcoming Schedule", listOf(episode))),
                FocusRequester(), {}, {}, {}, heroOverlay = { Text("Hero") },
                presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                showSeeAllCards = false)
        } }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("Oct 21, 2099", substring = true).assertIsDisplayed() }.isSuccess
        }
        val date = composeRule.onNodeWithText("Oct 21, 2099", substring = true).fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("catalog_rails_viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Calendar air date must remain visible: $date / $viewport", date.bottom <= viewport.bottom)
    }
    @Test fun largeSavedCatalogueWithCustomSpacingKeepsWholeCardsVisibleAcrossRowChanges() {
        val settings = org.koin.java.KoinJavaComponent.getKoin().get<com.torve.presentation.settings.SettingsViewModel>()
        val previous = settings.state.value.railSpacingDp
        try {
            settings.setRailSpacingDp(64)
            val memory = TvFocusMemory().apply { lastFocusedRowKey = "saved-76" }
            val rails = (0 until 84).map { row -> TvContentRail("saved-$row", "Saved row $row",
                (0 until 20).map { item -> MediaItem("saved-$row-$item", title = "Saved movie $row/$item", type = MediaType.MOVIE) }) }
            composeRule.setContent { MaterialTheme {
                TvMediaRails(rails, FocusRequester(), {}, {}, {}, focusMemory = memory,
                    forcePosterTitles = true, heroOverlay = { Text("Hero") },
                    presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                    browseLayout = TvBrowseLayout.POSTER_ONLY, showSeeAllCards = false)
            } }
            for ((step, row) in listOf(76, 77, 78, 77, 76).withIndex()) {
                waitForFocusedTitle("Saved movie $row/0")
                val card = composeRule.onNode(hasText("Saved movie $row/0") and hasClickAction()).fetchSemanticsNode().boundsInRoot
                val viewport = composeRule.onNodeWithTag("catalog_rails_viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("Active card is clipped at row $row: $card / $viewport", card.top >= viewport.top && card.bottom <= viewport.bottom)
                assertTrue("Active poster became a strip at row $row: $card", card.height >= with(composeRule.density) { 140.dp.toPx() })
                val next = when (step) {
                    0, 1 -> Key.DirectionDown
                    2, 3 -> Key.DirectionUp
                    else -> null
                }
                next?.let { key -> composeRule.onNodeWithText("Saved movie $row/0").performKeyInput { pressKey(key) } }
            }
        } finally {
            if (previous == null) settings.resetRailSpacing() else settings.setRailSpacingDp(previous)
        }
    }
    @Test fun rememberedRowWithManyHiddenRowsKeepsBothTitleLinesInsideTheViewport() { checkViewport(1f) }
    @Test fun largeTextAndLargePostersKeepTheCompleteCaptionVisible() { checkViewport(1.5f) }

    @Test fun changingRowsKeepsFocusOnTheNewCardAndItsCaptionInsideTheViewport() {
        val memory = TvFocusMemory().apply { lastFocusedRowKey = "continue_watching_movies" }
        val searchRequester = FocusRequester()
        val rails = (0..3).map { index ->
            TvContentRail(if (index == 0) "continue_watching_movies" else "rail-$index", "Movies $index",
                listOf(MediaItem(id = "movie-$index", title = "Movie $index with a long caption",
                    type = MediaType.MOVIE, posterUrl = "https://example.invalid/poster-$index.jpg")),
                cardStyle = if (index == 0) TvCardStyle.BACKDROP else TvCardStyle.POSTER)
        }
        composeRule.setContent {
            MaterialTheme {
                TvMediaRails(rails, FocusRequester(), onMediaClick = {}, onFirstContentRequester = {},
                    onContentFocused = {}, focusMemory = memory, forcePosterTitles = true,
                    heroOverlay = { Box(Modifier.height(180.dp)) { Text("Hero") } },
                    leadingContent = { Box(Modifier.height(66.dp).focusRequester(searchRequester).focusable().testTag("caption_search_entry")) { Text("Search movies") } },
                    leadingContentFocusRequester = searchRequester,
                    presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                    posterColumns = 4, showSeeAllCards = false)
            }
        }
        composeRule.onNodeWithText("Movie 0 with a long caption").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        for (index in 1..3) {
            composeRule.onNodeWithText("Movie ${index - 1} with a long caption").performKeyInput { pressKey(Key.DirectionDown) }
            waitForFocusedTitle("Movie $index with a long caption")
            val caption = composeRule.onNodeWithTag("catalog_poster_caption:movie-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val viewport = composeRule.onNodeWithTag("catalog_rails_viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val safeBottom = viewport.bottom - with(composeRule.density) { 12.dp.toPx() }
            if (caption.bottom > safeBottom) {
                val screenshot = File(composeRule.activity.getExternalFilesDir(null), "caption-row-$index.png")
                screenshot.outputStream().use {
                    composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue("Caption extends below the TV viewport after moving to row $index: $caption / $viewport", caption.bottom <= safeBottom)
        }
        composeRule.onNodeWithText("Movie 3 with a long caption").performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocusedTitle("Movie 2 with a long caption")
        for (index in 2 downTo 1) {
            composeRule.onNodeWithText("Movie $index with a long caption").performKeyInput { pressKey(Key.DirectionUp) }
            waitForFocusedTitle("Movie ${index - 1} with a long caption")
        }
        composeRule.onNodeWithText("Movie 0 with a long caption").performKeyInput { pressKey(Key.DirectionUp) }
        try {
            composeRule.waitUntil(5_000) {
                runCatching { composeRule.onNodeWithTag("caption_search_entry").assertIsFocused().assertIsDisplayed() }.isSuccess
            }
        } catch (failure: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("TorveCatalogSearchReturn")
            val screenshot = File(composeRule.activity.getExternalFilesDir(null), "caption-search-return.png")
            screenshot.outputStream().use {
                composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw AssertionError("Search handoff hierarchy:\n${composeRule.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
        composeRule.onNodeWithTag("caption_search_entry").performKeyInput { pressKey(Key.DirectionDown) }
        waitForFocusedTitle("Movie 0 with a long caption")
    }

    private fun waitForFocusedTitle(title: String) {
        try {
            composeRule.waitUntil(5_000) {
                runCatching { composeRule.onNodeWithText(title).assertIsFocused() }.isSuccess
            }
        } catch (failure: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("TorveCatalogTest")
            throw failure
        }
    }

    private fun checkViewport(fontScale: Float) {
        val railRequester = FocusRequester()
        val memory = TvFocusMemory().apply { lastFocusedRowKey = "rail-7" }
        val rails = (0..8).map { index -> TvContentRail("rail-$index", "Movies $index",
            listOf(MediaItem(id = "movie-$index", title = "A long movie title with two caption lines", type = MediaType.MOVIE))) }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        TvMediaRails(rails, railRequester, onMediaClick = {}, onFirstContentRequester = {},
                            onContentFocused = {}, focusMemory = memory, forcePosterTitles = true,
                            heroOverlay = { Box(Modifier.height(180.dp)) { Text("Hero") } },
                            presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                            browseLayout = TvBrowseLayout.POSTER_ONLY, posterColumns = 4, showSeeAllCards = false)
                    }
                }
            }
        }
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithTag("catalog_poster_caption:movie-7", useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        val caption = composeRule.onNodeWithTag("catalog_poster_caption:movie-7", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("catalog_rails_viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Caption extends below the TV viewport: $caption / $viewport", caption.bottom <= viewport.bottom - with(composeRule.density) { 12.dp.toPx() })
        assertTrue(caption.top >= viewport.top)
    }
}
