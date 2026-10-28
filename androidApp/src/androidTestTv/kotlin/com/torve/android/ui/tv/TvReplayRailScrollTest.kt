package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.settings.setTvExpandingPosters
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvReplayRailScrollTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    @Test fun replayRowScrollsWithOtherRowsUsingExpandingFocusFrames() = checkScrolling(true)
    @Test fun replayRowScrollsWithFocusFramesDisabled() = checkScrolling(false)

    private fun checkScrolling(expanding: Boolean) {
        val old = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
            .getString("tv_browse_layout", "POSTER_ONLY") == "EXPANDING"
        val rows = listOf(
            TvContentRail("web_replays", "Sports Replays", (0..8).map {
                MediaItem("web-replay:qa-$it", title = "Replay $it", type = MediaType.MOVIE,
                    familySourceKey = "web-replays")
            }, cardStyle = TvCardStyle.BACKDROP),
            TvContentRail("watchlist", "My Watchlist", listOf(MediaItem("42", title = "A film", type = MediaType.MOVIE))),
        )
        fun card(index: Int) = rule.onNode(hasText("Replay $index") and hasClickAction())
        fun visible(index: Int) {
            rule.waitUntil(5_000) { runCatching { card(index).assertIsFocused().assertIsDisplayed() }.isSuccess }
            val bounds = card(index).getUnclippedBoundsInRoot()
            val viewport = rule.onNodeWithTag("catalog_rails_viewport").getUnclippedBoundsInRoot()
            assertTrue("Focused replay $index must scroll completely into view: $bounds / $viewport",
                bounds.left >= viewport.left - 4.dp && bounds.right <= viewport.right + 4.dp)
        }
        try {
            setTvExpandingPosters(rule.activity, expanding)
            rule.setContent { MaterialTheme {
                TvMediaRails(rows, FocusRequester(), {}, {}, {}, screenId = "home-replay-scroll",
                    presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                    forcePosterTitles = true, showSeeAllCards = false, posterColumns = 6)
            } }
            visible(0)
            for (index in 1..8) { card(index - 1).performKeyInput { pressKey(Key.DirectionRight) }; visible(index) }
            for (index in 7 downTo 0) { card(index + 1).performKeyInput { pressKey(Key.DirectionLeft) }; visible(index) }
        } finally { setTvExpandingPosters(rule.activity, old) }
    }
}
