package com.torve.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import org.junit.Assert.assertEquals
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
}

@Composable
private fun TvNavRailFocusHarness(
    onMoveToContent: () -> Unit,
    onConfirm: () -> Unit,
) {
    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    var selectedRoute by remember { mutableStateOf(TvRoutes.MOVIES) }
    var highlightedRoute by remember { mutableStateOf(TvRoutes.MOVIES) }

    LaunchedEffect(Unit) {
        runCatching { railFocusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
    ) {
        TvNavRail(
            destinations = tvTopDestinations,
            selectedRoute = highlightedRoute,
            activeRoute = selectedRoute,
            isExpanded = true,
            railFocusRequester = railFocusRequester,
            onRailFocusChanged = {},
            onMoveToContent = { route ->
                selectedRoute = route
                highlightedRoute = route
                onMoveToContent()
                runCatching { contentFocusRequester.requestFocus() }
            },
            onConfirm = {
                onConfirm()
            },
            onNavigate = { route ->
                highlightedRoute = route
            },
            modifier = Modifier.width(220.dp),
        )

        Box(
            modifier = Modifier
                .testTag("content")
                .focusRequester(contentFocusRequester)
                .focusable()
                .weight(1f)
                .background(Color(0xFF202020)),
        )
    }
}
