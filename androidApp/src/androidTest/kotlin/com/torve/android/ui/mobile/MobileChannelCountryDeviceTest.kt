package com.torve.android.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.channels.ChannelCountryDialog
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class MobileChannelCountryDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun filterSheetSupportsCountrySearchMultiSelectAndReset() {
        var selected by mutableStateOf(emptySet<String>())
        rule.setContent {
            MaterialTheme {
                ChannelCountryDialog(
                    listOf("DE", "FR", "GB", "US"), selected,
                    { selected = if (it in selected) selected - it else selected + it },
                    { selected = emptySet() }, {})
            }
        }
        rule.onNodeWithTag("channel_country_all").assertIsSelected()
        rule.onNodeWithTag("channel_country_search").performTextInput("DE")
        rule.onNodeWithTag("channel_country_DE").performClick().assertIsSelected()
        rule.onNodeWithTag("channel_country_FR").assertDoesNotExist()
        rule.onNodeWithTag("channel_country_search").performTextReplacement("FR")
        rule.onNodeWithTag("channel_country_FR").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(setOf("DE", "FR"), selected) }
        rule.onNodeWithTag("channel_country_all").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(emptySet<String>(), selected) }
    }

    @Test fun savedCountryCanBeClearedWhenPlaylistHasNoCountryMetadata() {
        var selected by mutableStateOf(setOf("DE"))
        rule.setContent {
            MaterialTheme {
                ChannelCountryDialog(
                    emptyList(), selected, {}, { selected = emptySet() }, {})
            }
        }
        rule.onNodeWithTag("channel_country_all").assertIsNotSelected().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(emptySet<String>(), selected) }
    }
    @Test fun countryListSwipesWithoutMovingSearchOrDone() {
        rule.setContent { MaterialTheme {
            ChannelCountryDialog(java.util.Locale.getISOCountries().toList(), emptySet(), {}, {}, {})
        } }
        val searchTop = rule.onNodeWithTag("channel_country_search").fetchSemanticsNode().boundsInRoot.top
        val doneTop = rule.onNodeWithTag("channel_country_done").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("channel_country_AF").assertIsDisplayed()
        repeat(3) { rule.onNodeWithTag("channel_country_list").performTouchInput { swipeUp(durationMillis = 500) } }
        rule.onNodeWithTag("channel_country_AF").assertDoesNotExist()
        assertEquals(searchTop, rule.onNodeWithTag("channel_country_search").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(doneTop, rule.onNodeWithTag("channel_country_done").fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.onNodeWithTag("channel_country_search").performTextInput("United States")
        rule.onNodeWithTag("channel_country_US").assertIsDisplayed()
    }

    @Test fun groupHeaderKeepsBadgeAndUsesShortLabelForTheSameClickTarget() {
        var clicks = 0
        rule.setContent { MaterialTheme {
            com.torve.android.ui.channels.CategoryHeader("Albania Sport", 25, emptySet(), false,
                { clicks++ }, countryCode = "AL")
        } }
        rule.onNodeWithText("AL", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Sport", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
        rule.onNodeWithText("Albania Sport").assertDoesNotExist()
    }

}
