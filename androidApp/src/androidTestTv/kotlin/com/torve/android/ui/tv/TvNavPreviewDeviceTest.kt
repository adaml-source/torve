package com.torve.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTypography
import com.torve.android.tv.TvScaffold
import com.torve.android.tv.TV_CONTENT_START_INSET
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvNavPreviewDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun movingThroughMenuChangesPageWithoutClickAndRejectsPageAutofocus() {
        rule.setContent { PreviewHarness() }
        rule.onNodeWithText("Movies").assertIsFocused()
        val bounds = rule.onNodeWithTag("preview-page").getUnclippedBoundsInRoot()
        rule.onNodeWithText("Movies").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Shows").assertIsFocused()
        rule.onNodeWithText("Page: ${TvRoutes.SHOWS}").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Shows").assertIsFocused()
        rule.onNodeWithText("Shows").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Search").assertIsFocused()
        rule.onNodeWithText("Page: ${TvRoutes.SEARCH}").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Search").assertIsFocused()
        assertEquals(bounds, rule.onNodeWithTag("preview-page").getUnclippedBoundsInRoot())
    }

    @Test fun rightEntersPreviewAndBackRestoresItsMenuDestination() {
        rule.setContent { PreviewHarness() }
        rule.onNodeWithText("Movies").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Shows").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("preview-page").assertIsFocused() }.isSuccess }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("Shows").assertIsFocused().assertIsDisplayed()
        rule.onNodeWithText("Shows").performKeyInput { pressKey(Key.Enter) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("preview-page").assertIsFocused() }.isSuccess }
    }

    @Test fun settingsPreviewKeepsFocusAndItsLabelRemainsVisible() {
        rule.setContent { PreviewHarness() }
        rule.onNodeWithText("Movies").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionUp) }
        rule.onNodeWithText("Settings").assertIsFocused().assertIsDisplayed()
        rule.onNodeWithText("Page: ${TvRoutes.SETTINGS}").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Settings").assertIsFocused()
    }

    @Test fun onlyFocusedBubbleGrowsAndIconsRemainAvailableWhileBrowsing() {
        rule.setContent { PreviewHarness() }
        rule.mainClock.advanceTimeBy(400)
        val pageBounds = rule.onNodeWithTag("preview-page").getUnclippedBoundsInRoot()
        fun bubbleHeight(route: String) = rule.onNodeWithTag("tv_nav_bubble_$route", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val compact = bubbleHeight(TvRoutes.SHOWS)
        val expanded = bubbleHeight(TvRoutes.MOVIES)
        assertTrue("Focused bubble should grow", expanded > compact)
        rule.onNodeWithTag("tv_nav_label_${TvRoutes.MOVIES}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("tv_nav_label_${TvRoutes.SHOWS}", useUnmergedTree = true).assertDoesNotExist()

        rule.onNodeWithContentDescription("Movies").performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Shows").assertIsFocused()
        rule.onNodeWithTag("tv_nav_label_${TvRoutes.SHOWS}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("tv_nav_label_${TvRoutes.MOVIES}", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(compact, bubbleHeight(TvRoutes.MOVIES))
        assertEquals(pageBounds, rule.onNodeWithTag("preview-page").getUnclippedBoundsInRoot())

        rule.onNodeWithContentDescription("Shows").performKeyInput { pressKey(Key.DirectionRight) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("preview-page").assertIsFocused()
        rule.onNodeWithContentDescription("Shows").assertIsDisplayed()
        rule.onNodeWithTag("tv_nav_label_${TvRoutes.SHOWS}", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("preview-page").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("preview-page").assertIsFocused()
        rule.onNodeWithTag("preview-page").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Shows").assertIsFocused()
        rule.onNodeWithTag("tv_nav_label_${TvRoutes.SHOWS}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun labelsBelowStationaryIconsLeaveContentClear() {
        rule.setContent { MaterialTheme(typography = TorveTypography) { PreviewHarness() } }
        rule.mainClock.advanceTimeBy(400)
        val pageBounds = rule.onNodeWithTag("preview-page").getUnclippedBoundsInRoot()
        assertEquals("Content should reclaim the old horizontal label margin", TV_CONTENT_START_INSET, pageBounds.left)
        assertTrue("The icon strip must stay compact", pageBounds.left < 60.dp)
        val menuBounds = rule.onNodeWithTag("tv_nav_rail_panel").getUnclippedBoundsInRoot()
        assertTrue("Menu should form a compact group instead of filling the screen",
            menuBounds.bottom - menuBounds.top < 400.dp)
        val idleIcon = rule.onNodeWithTag("tv_nav_icon_${TvRoutes.HOME}", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val routes = tvTopDestinations.map { it.route }
        val start = routes.indexOf(TvRoutes.MOVIES)
        repeat(routes.size) { offset ->
            val route = routes[(start + offset) % routes.size]
            val item = rule.onNodeWithTag("tv_nav_item_$route")
            item.assertIsFocused()
            val bubble = rule.onNodeWithTag("tv_nav_bubble_$route", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val icon = rule.onNodeWithTag("tv_nav_icon_$route", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val label = rule.onNodeWithTag("tv_nav_label_$route", useUnmergedTree = true)
            val labelBounds = label.getUnclippedBoundsInRoot()
            assertEquals("Bubble must meet the screen edge", 0.dp, bubble.left)
            assertTrue("Bubble must leave content clear", bubble.right < pageBounds.left)
            val nextRoute = routes[(start + offset + 1) % routes.size]
            if (nextRoute != routes.first()) {
                val nextBubble = rule.onNodeWithTag("tv_nav_bubble_$nextRoute", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                assertTrue("Adjacent bubbles should have a small, clear gap",
                    nextBubble.top - bubble.bottom in 4.dp..12.dp)
            }
            assertEquals("Icon must grow without sliding sideways",
                (idleIcon.left + idleIcon.right) / 2, (icon.left + icon.right) / 2)
            assertTrue("Label must sit below its icon", labelBounds.top >= icon.bottom)
            assertTrue("Label needs padding on the left", labelBounds.left - bubble.left >= 5.dp)
            assertTrue("Label needs padding on the right", bubble.right - labelBounds.right >= 5.dp)
            assertTrue("Focused icon must grow", icon.right - icon.left > idleIcon.right - idleIcon.left)
            val textLayouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            val layout = textLayouts.single()
            // Ellipsis retains the paragraph's max constraint even when Text wraps
            // to a shorter word; inspect the visible line rather than paragraph width.
            assertFalse("$route label must be complete", layout.isLineEllipsized(0))
            assertFalse("$route label must fit vertically", layout.didOverflowHeight)
            assertTrue("$route text must fit its bounds", layout.getLineRight(0) <= layout.size.width)
            assertEquals(pageBounds, rule.onNodeWithTag("preview-page").getUnclippedBoundsInRoot())
            item.performKeyInput { pressKey(Key.DirectionDown) }
            rule.mainClock.advanceTimeBy(400)
        }
    }

    @Test fun transparentMenuSharesChangingPageBackgroundAcrossTheInset() {
        var pageColor by mutableStateOf(Color(0xFF20344D))
        rule.setContent {
            PreviewHarness(pageBackground = { Box(Modifier.fillMaxSize().background(pageColor)) })
        }
        fun assertSharedBackground(expected: Color) {
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val emptyRow = pixels.height / 16
            assertEquals("Menu must reveal the page background", expected, pixels[10, emptyRow])
            assertEquals("Content must use the same background", expected, pixels[pixels.width / 2, emptyRow])
        }
        assertSharedBackground(Color(0xFF20344D))
        rule.runOnIdle { pageColor = Color(0xFF472735) }
        assertSharedBackground(Color(0xFF472735))
    }
}

@Composable
private fun PreviewHarness(pageBackground: @Composable () -> Unit = {}) {
    val rail = remember { FocusRequester() }
    var route by remember { mutableStateOf(TvRoutes.MOVIES) }
    var railFocused by remember { mutableStateOf(true) }
    var entryRequested by remember { mutableStateOf(false) }
    var preferred by remember { mutableStateOf<String?>(TvRoutes.MOVIES) }
    BackHandler(!railFocused) { entryRequested = false; preferred = route; rail.requestFocus() }
    TvScaffold(
        contentFocusEnabled = !railFocused || entryRequested,
        showRail = true,
        background = {},
        pageBackground = pageBackground,
        leftRail = {
            TvNavRail(tvTopDestinations, selectedRoute = route, activeRoute = route,
                isExpanded = false, isVisible = true, railFocusRequester = rail,
                preferredEntryRoute = preferred,
                onPreferredEntryRouteConsumed = { preferred = null },
                onRailFocusChanged = { railFocused = it },
                onMoveToContent = { route = it; entryRequested = true },
                navigateOnFocus = true,
                onNavigate = { route = it },
            )
        },
        content = {
            key(route) {
                val page = remember { FocusRequester() }
                // Exercise real page-entry effects that attempt to take focus
                // as they mount. The preview boundary must reject every attempt.
                LaunchedEffect(route, entryRequested) {
                    repeat(4) { page.requestFocus(); delay(50) }
                }
                Box(Modifier.fillMaxSize().testTag("preview-page").focusRequester(page)
                    .focusProperties { left = rail }.focusable()) {
                    Text("Page: $route")
                }
            }
        },
    )
}
