package com.torve.android.ui.mobile

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.settings.MobileProfileActions
import com.torve.android.ui.settings.MobileDeviceActions
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileAccountMenusPolishDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun accountProfileActionsUseEqualSpacingAndSyncDisablesWhileBusy() {
        val clicks = mutableListOf<String>()
        var busy by mutableStateOf(false)
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            MobileProfileActions({ clicks += "profiles" }, { clicks += "family" }, { clicks += "sync" }, busy, Modifier.width(360.dp))
        } }
        checkSpacing(listOf("account.profiles", "account.family", "account.sync"))
        rule.onNodeWithTag("account.profiles").performClick()
        rule.onNodeWithTag("account.family").performClick()
        rule.onNodeWithTag("account.sync").performClick()
        rule.runOnIdle { assertEquals(listOf("profiles", "family", "sync"), clicks); busy = true }
        rule.onNodeWithTag("account.sync").assertIsNotEnabled()
        rule.onNodeWithText("Calendar").assertDoesNotExist()
        rule.onNodeWithText("Configure Sources").assertDoesNotExist()
    }

    @Test fun deviceActionsUseEqualSpacingAndKeepEveryDestination() {
        val clicks = mutableListOf<String>()
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            MobileDeviceActions({ clicks += "pairings" }, { clicks += "manage" }, { clicks += "signin" },
                { clicks += "send" }, { clicks += "receive" }, Modifier.width(360.dp))
        } }
        val keys = listOf("pairings", "manage", "signin", "send", "receive")
        checkSpacing(keys.map { "devices.$it" })
        keys.forEach { rule.onNodeWithTag("devices.$it").performClick() }
        rule.runOnIdle { assertEquals(keys, clicks) }
    }

    private fun checkSpacing(tags: List<String>) {
        val density = rule.activity.resources.displayMetrics.density
        val bounds = tags.map { rule.onNodeWithTag(it).assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (first, second) ->
            assertEquals(12 * density, second.top - first.bottom, 1f)
            assertEquals(first.left, second.left, 1f)
            assertEquals(first.right, second.right, 1f)
        }
    }
}
