package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.settings.SettingsViewModel
import org.junit.*
import org.junit.Assert.*
import org.koin.java.KoinJavaComponent.getKoin

@OptIn(ExperimentalTestApi::class)
class TvCommunitySpacingTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private lateinit var settings: SettingsViewModel
    private var prior: Int? = null
    private val rails = (0..2).map { row -> TvContentRail("spacing-$row", "Spacing row $row", listOf(
        MediaItem("spacing-$row", title = "Spacing card $row", type = MediaType.MOVIE, isContentPlaceholder = true)), cardStyle = TvCardStyle.BACKDROP) }
    @Before fun prepare() {
        settings = getKoin().get(); prior = settings.state.value.railSpacingDp
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun restore() { rule.runOnUiThread { if (prior == null) settings.resetRailSpacing() else settings.setRailSpacingDp(prior!!) } }
    @Test fun changingSpacingMovesTheActualSecondRailByTheRequestedDistance() {
        rule.runOnUiThread { settings.setRailSpacingDp(0) }
        rule.setContent { MaterialTheme { TvMediaRails(rails, remember { FocusRequester() }, {}, {}, {}, shouldAutoFocus = false) } }
        rule.waitForIdle()
        val before = rule.onNodeWithText("Spacing row 1").fetchSemanticsNode().boundsInRoot.top
        rule.runOnUiThread { settings.setRailSpacingDp(64) }
        rule.waitForIdle()
        val after = rule.onNodeWithText("Spacing row 1").fetchSemanticsNode().boundsInRoot.top
        assertEquals(64 * rule.activity.resources.displayMetrics.density, after - before, 2f)
    }
    @Test fun focusedRowModeKeepsRemoteNavigationWithCustomSpacing() {
        rule.runOnUiThread { settings.setRailSpacingDp(64) }
        rule.setContent { MaterialTheme { TvMediaRails(rails, remember { FocusRequester() }, {}, {}, {},
            presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
            heroOverlay = { Text("Spacing check") }, forcePosterTitles = true) } }
        val first = hasText("Spacing card 0") and hasClickAction()
        val second = hasText("Spacing card 1") and hasClickAction()
        rule.waitUntil(8_000) { runCatching { rule.onNode(first).assertIsFocused() }.isSuccess }
        rule.onNode(first).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitUntil(8_000) { runCatching { rule.onNode(second).assertIsFocused().assertIsDisplayed() }.isSuccess }
    }
}
