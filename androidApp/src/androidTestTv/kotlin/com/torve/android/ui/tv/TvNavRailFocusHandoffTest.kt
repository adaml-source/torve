package com.torve.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.TvScaffold
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvNavRailFocusHandoffTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun rightFromRail_entersContentOnce() {
        var moveToContentCalls = 0
        var confirmCalls = 0
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)

        composeRule.setContent {
            TvNavRailFocusHarness(
                onMoveToContent = { moveToContentCalls++ },
                onConfirm = { confirmCalls++ },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText(moviesLabel).assertIsFocused()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText(moviesLabel).performKeyInput {
            pressKey(Key.DirectionRight)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            moveToContentCalls == 1 &&
                runCatching {
                    composeRule.onNodeWithTag("content").assertIsFocused()
                    true
                }.getOrDefault(false)
        }

        assertEquals(1, moveToContentCalls)
        assertEquals(0, confirmCalls)
    }

    @Test
    fun enterFromRail_entersContentOnce() {
        var moveToContentCalls = 0
        var confirmCalls = 0
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)

        composeRule.setContent {
            TvNavRailFocusHarness(
                onMoveToContent = { moveToContentCalls++ },
                onConfirm = { confirmCalls++ },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText(moviesLabel).assertIsFocused()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithText(moviesLabel).performKeyInput {
            pressKey(Key.Enter)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            moveToContentCalls == 1 &&
                runCatching {
                    composeRule.onNodeWithTag("content").assertIsFocused()
                    true
                }.getOrDefault(false)
        }

        assertEquals(1, moveToContentCalls)
        assertEquals(0, confirmCalls)
    }

    @Test
    fun lateJellyfinActivation_keepsCurrentContentFocus() {
        var moveToContentCalls = 0
        var enableJellyfin: (() -> Unit)? = null
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)

        composeRule.setContent {
            TvNavRailFocusHarness(
                onMoveToContent = { moveToContentCalls++ },
                onConfirm = {},
                jellyfinInitiallyEnabled = false,
                onEnableJellyfinReady = { enableJellyfin = it },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText(moviesLabel).assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText(moviesLabel).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("content").assertIsFocused()
                true
            }.getOrDefault(false)
        }

        composeRule.runOnUiThread { requireNotNull(enableJellyfin).invoke() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("content").assertIsFocused()
        assertEquals(1, moveToContentCalls)
    }

    @Test
    fun disablingOptionalFeatures_shrinksAndCentersMenuWithoutLosingFocus() {
        var disableOptionalFeatures: (() -> Unit)? = null
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)
        composeRule.setContent {
            TvNavRailFocusHarness(
                onMoveToContent = {},
                onConfirm = {},
                onDisableOptionalFeaturesReady = { disableOptionalFeatures = it },
            )
        }
        composeRule.onNodeWithText(moviesLabel).assertIsFocused()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val expandedBounds = composeRule.onNodeWithTag("tv_nav_rail_panel").getUnclippedBoundsInRoot()

        composeRule.runOnIdle { requireNotNull(disableOptionalFeatures).invoke() }
        val compactBounds = composeRule.onNodeWithTag("tv_nav_rail_panel").getUnclippedBoundsInRoot()
        val expandedHeight = expandedBounds.bottom - expandedBounds.top
        val compactHeight = compactBounds.bottom - compactBounds.top
        // The amount released depends on the viewport and enabled destination count.
        // In particular Fire TV's 540dp viewport compresses the fully enabled menu.
        assertTrue("Disabled entries must release menu height ($expandedHeight -> $compactHeight)", compactHeight < expandedHeight)
        assertTrue("Compact menu must leave space above", compactBounds.top > expandedBounds.top)
        assertTrue("Compact menu must leave space below", compactBounds.bottom < expandedBounds.bottom)
        assertEquals(
            ((expandedBounds.top + expandedBounds.bottom) / 2).value,
            ((compactBounds.top + compactBounds.bottom) / 2).value,
            1f,
        )
        composeRule.onNodeWithText(moviesLabel).assertIsFocused()
        composeRule.onNodeWithContentDescription("Jellyfin").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Sports").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Channels").assertDoesNotExist()
        composeRule.onNodeWithText(moviesLabel).performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        composeRule.onNodeWithContentDescription("Settings").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun enteringContentKeepsBoundsStableAndLeftRestoresTheSameDestination() {
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)
        composeRule.setContent { TvNavRailFocusHarness(onMoveToContent = {}, onConfirm = {}) }
        composeRule.onNodeWithText(moviesLabel).assertIsFocused()
        val menuBounds = composeRule.onNodeWithTag("content").getUnclippedBoundsInRoot()
        val menuWidth = menuBounds.right - menuBounds.left
        composeRule.onNodeWithText(moviesLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("content").assertIsFocused()
        composeRule.onNodeWithText(moviesLabel).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Movies").assertIsDisplayed()
        val contentBounds = composeRule.onNodeWithTag("content").getUnclippedBoundsInRoot()
        val contentWidth = contentBounds.right - contentBounds.left
        assertEquals("Opening the menu must not resize content", menuWidth, contentWidth)
        assertEquals(menuBounds.left, contentBounds.left)
        composeRule.onNodeWithTag("content").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithText(moviesLabel).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun clickingAnotherDestinationGivesItsPersistentLabelEnoughSpace() {
        composeRule.setContent { TvNavRailFocusHarness(onMoveToContent = {}, onConfirm = {}) }
        composeRule.onNodeWithText("Movies").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithContentDescription("Library").performClick()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("tv_nav_bubble_${TvRoutes.LIBRARY}",useUnmergedTree=true).assertHeightIsEqualTo(52.dp)
        val icon=composeRule.onNodeWithTag("tv_nav_icon_${TvRoutes.LIBRARY}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val label=composeRule.onNodeWithTag("tv_nav_label_${TvRoutes.LIBRARY}",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The persistent label must sit below its icon",label.top>icon.bottom)
        composeRule.onNodeWithTag("content").assertIsFocused()
    }

    @Test
    fun backReturnsToIconsAndVerticalKeysKeepContentFocus() {
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)
        composeRule.setContent { TvNavRailFocusHarness(onMoveToContent = {}, onConfirm = {}) }
        composeRule.onNodeWithText(moviesLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("content").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("content").assertIsFocused()
        composeRule.onNodeWithContentDescription("Movies").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithText(moviesLabel).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun detailsKeepMainNavigationHiddenAndLeftDoesNotDropFocus() {
        var detailsOpen by mutableStateOf(false)
        val moviesLabel = composeRule.activity.getString(R.string.nav_movies)
        composeRule.setContent { TvNavRailFocusHarness(onMoveToContent = {}, onConfirm = {}, fullscreen = detailsOpen) }
        composeRule.onNodeWithText(moviesLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { detailsOpen = true }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("content").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("content").assertIsFocused()
        composeRule.onNodeWithText(moviesLabel).assertIsNotDisplayed()
        composeRule.onNodeWithContentDescription("Movies").assertIsNotDisplayed()
    }
}

@Composable
private fun TvNavRailFocusHarness(
    onMoveToContent: () -> Unit,
    onConfirm: () -> Unit,
    jellyfinInitiallyEnabled: Boolean = true,
    onEnableJellyfinReady: ((() -> Unit) -> Unit)? = null,
    onDisableOptionalFeaturesReady: ((() -> Unit) -> Unit)? = null,
    fullscreen: Boolean = false,
) {
    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    var selectedRoute by remember { mutableStateOf(TvRoutes.MOVIES) }
    var highlightedRoute by remember { mutableStateOf(TvRoutes.MOVIES) }
    var jellyfinEnabled by remember { mutableStateOf(jellyfinInitiallyEnabled) }
    var optionalFeaturesEnabled by remember { mutableStateOf(true) }
    var railFocused by remember { mutableStateOf(true) }
    BackHandler(enabled = !railFocused && !fullscreen) { railFocusRequester.requestFocus() }

    SideEffect {
        onEnableJellyfinReady?.invoke { jellyfinEnabled = true }
        onDisableOptionalFeaturesReady?.invoke { optionalFeaturesEnabled = false }
    }

    LaunchedEffect(Unit) {
        runCatching { railFocusRequester.requestFocus() }
    }

    TvScaffold(isFullscreen = fullscreen, showRail = !fullscreen, background = {}, leftRail = {
        TvNavRail(
            destinations = tvTopDestinations,
            enabledRoutes = tvTopDestinations
                .map { it.route }
                .filterTo(linkedSetOf()) {
                    (it != TvRoutes.JELLYFIN || jellyfinEnabled) &&
                        (optionalFeaturesEnabled || it !in setOf(TvRoutes.JELLYFIN, TvRoutes.SPORTS, TvRoutes.IPTV))
                },
            selectedRoute = highlightedRoute,
            activeRoute = selectedRoute,
            isExpanded = true,
            isVisible = !fullscreen,
            isFocusEnabled = !fullscreen,
            railFocusRequester = railFocusRequester,
            preferredEntryRoute = TvRoutes.MOVIES,
            preferredEntryRequestNonce = 1,
            onRailFocusChanged = { railFocused = it },
            onMoveToContent = { route ->
                selectedRoute = route
                highlightedRoute = route
                onMoveToContent()
                runCatching { contentFocusRequester.requestFocus() }
            },
            onConfirm = { route ->
                selectedRoute = route
                highlightedRoute = route
                onConfirm()
                runCatching { contentFocusRequester.requestFocus() }
            },
            onNavigate = { route ->
                highlightedRoute = route
            },
        )
    }, content = {
        Box(
            modifier = Modifier
                .testTag("content")
                .fillMaxSize()
                .focusRequester(contentFocusRequester)
                .focusProperties { left = railFocusRequester }
                .focusable()
                .background(Color(0xFF202020)),
        )
    })
}
