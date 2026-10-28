package com.torve.android.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.replays.MobileReplayBrowserContent
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.components.FloatingBackButton
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.replays.*
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileReplayNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    @Test fun compactReplayControlsStayApartAndSelectWebsiteAndSport() {
        var site by mutableStateOf(""); var sport by mutableStateOf(""); var query by mutableStateOf("")
        var back=0; var settings=0; var refresh=0; var opened=""
        val state=WebReplayState(loaded=true,enabled=true,websites=listOf(ReplayWebsite("nba","NBA","https://basketball-video.com/"),ReplayWebsite("f1","Formula 1","https://fullraces.com/")),
            events=listOf(ReplayEvent("game","nba","NBA Finals replay","https://basketball-video.com/game",sport="Basketball"),ReplayEvent("race","f1","Grand Prix replay","https://fullraces.com/race",sport="Motorsport")))
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.3f)) {
                MobileReplayBrowserContent(state,query,site,sport,{query=it},{site=it},{sport=it},{back++},{settings++},{refresh++},{opened=it},{},Modifier.width(320.dp))
            }
        } }
        val header=rule.onNodeWithTag("replays.header").fetchSemanticsNode().boundsInRoot
        val field=rule.onNodeWithTag("replays.search").fetchSemanticsNode().boundsInRoot
        val filters=rule.onNodeWithTag("replays.filters").fetchSemanticsNode().boundsInRoot
        assertTrue(field.top>=header.bottom); assertTrue(filters.top>=field.bottom)
        val source=rule.onNodeWithTag("replays.site").fetchSemanticsNode().boundsInRoot
        val sports=rule.onNodeWithTag("replays.sport").fetchSemanticsNode().boundsInRoot
        assertTrue(source.right<sports.left); assertEquals(source.center.y,sports.center.y,1f)
        rule.onNodeWithText("Back").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        rule.onNodeWithContentDescription("Replay websites").performClick()
        rule.onNodeWithContentDescription("Refresh replays").performClick()
        rule.onNodeWithTag("replays.site").performClick(); rule.onNodeWithText("NBA",useUnmergedTree=true).performClick()
        rule.runOnIdle { assertEquals("nba",site); assertEquals(1,back); assertEquals(1,settings); assertEquals(1,refresh) }
        rule.onNodeWithText("NBA Finals replay").performClick(); rule.runOnIdle { assertEquals("game",opened) }
        rule.onNodeWithTag("replays.sport").performClick(); rule.onNodeWithText("Motorsport",useUnmergedTree=true).performClick()
        rule.onNodeWithText("No matching replays").assertIsDisplayed()
    }
    @Test fun bothBackEntryPointsAreCircularArrowsWithoutVisibleText() {
        var count=0
        rule.setContent { TorveTheme(ThemeMode.DARK) { Column {
            BackButton({count++}); FloatingBackButton({count++},label="Return to library",compact=false)
        } } }
        listOf("Back","Return to library").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
            rule.onNodeWithContentDescription(it).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        }
        rule.runOnIdle { assertEquals(2,count) }
    }
}
