package com.torve.android.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.settings.setTvExpandingPosters
import com.torve.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import com.torve.android.tv.settings.setTvFocusPreview
import com.torve.android.tv.settings.tvFocusPreviewPolicy
import org.koin.core.context.GlobalContext

@OptIn(ExperimentalTestApi::class)
class TvExpandingPostersTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val rows = (0..2).map { row -> TvContentRail("expand-$row", "Row $row", (0..2).map { column ->
        MediaItem("expand-$row-$column", type = MediaType.MOVIE, title = "Film $row/$column",
            year = 2024, runtime = 65, ratings = MediaRatings(imdbScore = 8.3f, rottenTomatoesScore = 94, imdbVotes = 3120),
            cast = listOf(CastMember(1, "Tom Hardy"), CastMember(2, "Pierce Brosnan"), CastMember(3, "Helen Mirren")),
            overview = "A synopsis under the expanded landscape artwork.") }) }
    private fun card(row: Int, column: Int) = rule.onNodeWithTag("catalog_poster_card:expand-$row-$column")
    private fun oldPreference() = rule.activity.getSharedPreferences("tv_ui_prefs", 0).getString("tv_browse_layout", "POSTER_ONLY") == "EXPANDING"
    private fun content(fontScale: Float = 1f) { rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { MaterialTheme {
            TvMediaRails(rows, FocusRequester(), {}, {}, {}, screenId = "expansion-test-$fontScale",
                heroOverlay = { Box(Modifier.height(180.dp)) { Text("Hero") } },
                browseLayout = TvBrowseLayout.POSTER_ONLY, presentationMode = TvRailsPresentationMode.CatalogHero,
                focusExclusive = true, forcePosterTitles = true, showSeeAllCards = false, posterColumns = 6)
        } }
    } }
    private fun waitFocused(row: Int, column: Int) { rule.waitUntil(5_000) { runCatching { card(row, column).assertIsFocused().assertIsDisplayed() }.isSuccess } }
    @Test fun expansionReplacesHeroAndAnchorsEverySelectedCardToTheLeft() {
        val previous = oldPreference()
        try {
            setTvExpandingPosters(rule.activity, false); content(); waitFocused(0, 0)
            val normal = card(0, 0).getUnclippedBoundsInRoot()
            val viewport = rule.onNodeWithTag("catalog_rails_viewport").getUnclippedBoundsInRoot()
            rule.runOnIdle { setTvExpandingPosters(rule.activity, true) }; rule.mainClock.advanceTimeBy(300)
            val expanded = card(0, 0).getUnclippedBoundsInRoot()
            assertTrue(expanded.right - expanded.left > (normal.right - normal.left) * 2f)
            assertTrue("Preview must fit beside the adjacent posters", expanded.right - expanded.left <= 440.dp)
            assertTrue(rule.onNodeWithTag("catalog_rails_viewport").getUnclippedBoundsInRoot().top < viewport.top)
            rule.onNodeWithText("Hero").assertDoesNotExist()
            rule.onNodeWithTag("catalog_expanded_synopsis:expand-0-0", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithTag("catalog_expanded_votes:expand-0-0", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithTag("catalog_expanded_logo:expand-0-0", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithTag("catalog_expanded_cast:expand-0-0", useUnmergedTree = true).assertTextContains("Tom Hardy", substring = true)
            val metadata = rule.onNodeWithTag("catalog_expanded_metadata:expand-0-0", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val frame = rule.onNodeWithTag("catalog_poster_frame:expand-0-0", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val caption = rule.onNodeWithTag("catalog_poster_caption:expand-0-0", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Focus frame must end at the image, above the text", frame.bottom <= caption.top && frame.bottom < expanded.bottom)
            assertTrue("Section heading needs a top safe margin", rule.onNodeWithTag("catalog_rails_viewport").getUnclippedBoundsInRoot().top >= 24.dp)
            val votes = rule.onNodeWithTag("catalog_expanded_votes:expand-0-0", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(votes.top >= metadata.top && votes.bottom <= metadata.bottom)
            // The artwork title belongs inside the image, never in the caption.
            rule.onNodeWithTag("catalog_poster_caption:expand-0-0", useUnmergedTree = true)
                .onChildren().filter(hasText("Film 0/0")).assertCountEquals(0)
            card(0, 0).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 1)
            rule.mainClock.advanceTimeBy(300)
            val second = card(0, 1).getUnclippedBoundsInRoot()
            card(0, 0).assertIsNotDisplayed()
            assertEquals(expanded.top, second.top)
            assertEquals(expanded.bottom - expanded.top, second.bottom - second.top)
            assertTrue("Selected card must stay at the left edge", kotlin.math.abs(expanded.left.value - second.left.value) < 2f)
            card(0, 1).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 2)
            rule.mainClock.advanceTimeBy(400)
            val final = card(0, 2).getUnclippedBoundsInRoot()
            assertTrue("Even the final card must stay at the left edge", kotlin.math.abs(expanded.left.value - final.left.value) < 2f)
            card(0, 2).performKeyInput { pressKey(Key.DirectionLeft) }; waitFocused(0, 1)
            card(0, 1).performKeyInput { pressKey(Key.DirectionDown) }; waitFocused(1, 0)
            card(1, 0).performKeyInput { pressKey(Key.DirectionUp) }; waitFocused(0, 1)
            rule.runOnIdle { setTvExpandingPosters(rule.activity, false) }
            rule.onNodeWithText("Hero").assertIsDisplayed()
        } finally { setTvExpandingPosters(rule.activity, previous) }
    }
    @Test fun movingFocusNeverSqueezesThePreviewOrChangesItsVerticalBounds() {
        val previous = oldPreference()
        try {
            setTvExpandingPosters(rule.activity, true); content(); waitFocused(0, 0)
            val initial = card(0, 0).getUnclippedBoundsInRoot()
            // Keep automatic idling from hiding intermediate animation frames.
            rule.mainClock.autoAdvance = false
            var current = 0
            for (next in listOf(1, 2, 1, 2, 1, 0)) {
                card(0, current).performKeyInput { pressKey(if (next > current) Key.DirectionRight else Key.DirectionLeft) }
                repeat(5) {
                    rule.mainClock.advanceTimeByFrame()
                    card(0, next).assertIsFocused()
                    val bounds = card(0, next).getUnclippedBoundsInRoot()
                    assertEquals(initial.right - initial.left, bounds.right - bounds.left)
                    assertEquals(initial.top, bounds.top)
                    assertEquals(initial.bottom, bounds.bottom)
                    assertTrue("Focus anchor moved during a frame: $bounds", kotlin.math.abs(initial.left.value - bounds.left.value) < 2f)
                }
                current = next
            }
        } finally { rule.mainClock.autoAdvance = true; setTvExpandingPosters(rule.activity, previous) }
    }
    @Test fun largeTextKeepsExpandedSynopsisAndFocusedCardInsideViewport() {
        val previous = oldPreference()
        try {
            setTvExpandingPosters(rule.activity, true); content(1.5f); waitFocused(0, 0)
            rule.mainClock.advanceTimeBy(300)
            val card = card(0, 0).fetchSemanticsNode().boundsInRoot
            val viewport = rule.onNodeWithTag("catalog_rails_viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("Expanded card is clipped: $card / $viewport", card.top >= viewport.top && card.bottom <= viewport.bottom)
            rule.onNodeWithTag("catalog_expanded_synopsis:expand-0-0", useUnmergedTree = true).assertIsDisplayed()
        } finally { setTvExpandingPosters(rule.activity, previous) }
    }

    @Test fun verticalTransitionMovesInOneDirectionAndRestoresTheRememberedPoster() {
        val previous = oldPreference()
        try {
            setTvExpandingPosters(rule.activity, true); content(); waitFocused(0, 0)
            card(0, 0).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 1)
            val anchor = card(0, 1).getUnclippedBoundsInRoot()
            rule.mainClock.autoAdvance = false
            card(0, 1).performKeyInput { pressKey(Key.DirectionDown) }
            val tops = mutableListOf<Float>()
            repeat(60) {
                rule.mainClock.advanceTimeByFrame()
                val node = rule.onAllNodesWithTag("catalog_rail_section:expand-1", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                node?.let { tops.add(it.boundsInRoot.top) }
            }
            assertTrue("The destination must be observed throughout its scroll", tops.size > 10)
            assertTrue("Vertical motion reversed: $tops", tops.zipWithNext().all { (a, b) -> b <= a + 1f })
            card(1, 0).assertIsFocused()
            assertEquals(anchor.top, card(1, 0).getUnclippedBoundsInRoot().top)
            rule.mainClock.autoAdvance = true
            card(1, 0).performKeyInput { pressKey(Key.DirectionUp) }; waitFocused(0, 1)
            assertEquals(anchor, card(0, 1).getUnclippedBoundsInRoot())
        } finally { rule.mainClock.autoAdvance = true; setTvExpandingPosters(rule.activity, previous) }
    }

    @Test fun individualNormalRowRestoresHeroAndReturningUpRestoresExpandedSelection() {
        val owner = GlobalContext.get().get<com.torve.presentation.settings.SettingsViewModel>().state.value.personalDataUserId
        val prefs = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
        val prefix = "focus_preview.v1.${owner ?: "local"}."
        val choices = mapOf("global" to false, "row:expansion-test-1.0:expand-0" to true,
            "row:expansion-test-1.0:expand-1" to false)
        val before = choices.keys.associateWith { prefs.all[prefix + it] as? Boolean }
        try {
            choices.forEach { (key, value) -> setTvFocusPreview(rule.activity, owner, key, value) }
            content(); waitFocused(0, 0)
            rule.onNodeWithText("Hero").assertDoesNotExist()
            card(0, 0).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 1)
            card(0, 1).performKeyInput { pressKey(Key.DirectionDown) }; waitFocused(1, 0)
            rule.onNodeWithText("Hero").assertIsDisplayed()
            card(1, 0).performKeyInput { pressKey(Key.DirectionUp) }; waitFocused(0, 1)
            rule.onNodeWithText("Hero").assertDoesNotExist()
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "expanded-preview.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            val edit = prefs.edit()
            before.forEach { (key, value) -> if (value == null) edit.remove(prefix + key) else edit.putBoolean(prefix + key, value) }
            edit.apply()
        }
    }

    @Test fun globalPreviewOnGridPagesKeepsToolbarEntryAndVerticalReturnFocus() {
        val previous = oldPreference()
        val entry = FocusRequester(); val header = FocusRequester()
        try {
            setTvExpandingPosters(rule.activity, true)
            rule.setContent { MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button({}, Modifier.focusRequester(header)) { Text("Grid toolbar") }
                    TvFocusPreviewGrid(rows.flatMap { it.items }, "see_all:test:movie", 3, FocusRequester(), entry,
                        headerRequester = header, modifier = Modifier.weight(1f), hero = { Text("Grid hero") }, onClick = {})
                }
            } }
            rule.runOnIdle { entry.requestFocus() }; waitFocused(0, 0)
            rule.onNodeWithText("Grid hero").assertDoesNotExist()
            val image = rule.onNodeWithTag("catalog_poster_frame:expand-0-0", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals("Grid landscape must retain 16:9 instead of becoming a panoramic strip", 16f / 9f,
                (image.right - image.left).value / (image.bottom - image.top).value, .03f)
            card(0, 0).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 1)
            card(0, 1).performKeyInput { pressKey(Key.DirectionDown) }; waitFocused(1, 0)
            card(1, 0).performKeyInput { pressKey(Key.DirectionUp) }; waitFocused(0, 1)
            card(0, 1).performKeyInput { pressKey(Key.DirectionUp) }
            rule.onNodeWithText("Grid toolbar").assertIsFocused()
            rule.onNodeWithText("Grid hero").assertDoesNotExist()
        } finally { setTvExpandingPosters(rule.activity, previous) }
    }

    @Test fun onlyTheFocusedPosterExpandsAndSeeAllNeverRestoresADuplicateHero() {
        val previous = oldPreference()
        fun portrait(row: Int, column: Int) {
            val bounds = rule.onNodeWithTag("catalog_poster_frame:expand-$row-$column", true).getUnclippedBoundsInRoot()
            assertEquals(2f / 3f, (bounds.right - bounds.left).value / (bounds.bottom - bounds.top).value, .03f)
        }
        try {
            setTvExpandingPosters(rule.activity, true)
            rule.setContent { MaterialTheme {
                TvMediaRails(rows, FocusRequester(), {}, {}, {}, screenId = "focused-only-test",
                    heroOverlay = { Text("Duplicate hero") }, onSeeAll = { _, _ -> },
                    browseLayout = TvBrowseLayout.POSTER_ONLY, presentationMode = TvRailsPresentationMode.CatalogHero,
                    focusExclusive = true, posterColumns = 5)
            } }
            waitFocused(0, 0)
            portrait(0, 1); portrait(1, 0); portrait(1, 1)
            rule.onNodeWithText("Duplicate hero").assertDoesNotExist()
            card(0, 0).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 1)
            portrait(1, 0)
            card(0, 1).performKeyInput { pressKey(Key.DirectionRight) }; waitFocused(0, 2)
            card(0, 2).performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("See All") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Duplicate hero").assertDoesNotExist()
            rule.onNodeWithTag("catalog_expanded_synopsis:expand-0-2", true).assertDoesNotExist()
            portrait(1, 0)
        } finally { setTvExpandingPosters(rule.activity, previous) }
    }
}
