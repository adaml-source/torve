package com.torve.android.ui.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.components.TvProviderHealthBanner
import com.torve.android.tv.components.rememberTvProviderNoticeVisible
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import com.torve.presentation.tvhome.TvProviderBanner
import com.torve.presentation.tvhome.TvProviderBannerTone
import org.junit.Rule
import org.junit.Test

class TvProviderNoticeTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun noticeExpiresWithoutDroppingFocusOrRestartingForRepeatedHealthUpdates() {
        val hasIssue = mutableStateOf(true)
        val isActive = mutableStateOf(true)
        val message = mutableStateOf("First check failed")
        composeRule.setContent {
            MaterialTheme {
                val requester = remember { FocusRequester() }
                val visible = rememberTvProviderNoticeVisible(hasIssue.value, isActive.value)
                Column {
                    if (visible) TvProviderHealthBanner(TvProviderBanner(TvProviderBannerTone.ERROR, "Provider needs attention", message.value, 1, 0))
                    Text("Focused content", Modifier.focusRequester(requester).clickable {})
                }
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
        composeRule.onNodeWithText("Focused content").assertIsFocused()
        composeRule.onAllNodesWithText("Provider needs attention").assertCountEquals(1)
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.runOnIdle { message.value = "The same provider still needs attention" }
        composeRule.mainClock.advanceTimeBy(4_100)
        composeRule.onAllNodesWithText("Provider needs attention").assertCountEquals(0)
        composeRule.onNodeWithText("Focused content").assertIsFocused()
        composeRule.runOnIdle { isActive.value = false }
        composeRule.runOnIdle { isActive.value = true }
        composeRule.onAllNodesWithText("Provider needs attention").assertCountEquals(0)
        composeRule.runOnIdle { hasIssue.value = false }
        composeRule.runOnIdle { hasIssue.value = true }
        composeRule.onAllNodesWithText("Provider needs attention").assertCountEquals(1)
    }

    @Test
    fun settingsCogRetainsItsMarkerUntilTheIssueIsResolved() {
        val needsAttention = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                TvNavRail(
                    destinations = tvTopDestinations.filter { it.route == TvRoutes.SETTINGS },
                    selectedRoute = TvRoutes.SETTINGS,
                    isExpanded = false,
                    railFocusRequester = remember { FocusRequester() },
                    onRailFocusChanged = {},
                    onMoveToContent = {},
                    onNavigate = {},
                    settingsNeedAttention = needsAttention.value,
                )
            }
        }
        composeRule.onAllNodesWithContentDescription("Needs attention", useUnmergedTree = true).assertCountEquals(1)
        composeRule.runOnIdle { needsAttention.value = false }
        composeRule.onAllNodesWithContentDescription("Needs attention", useUnmergedTree = true).assertCountEquals(0)
    }
}
