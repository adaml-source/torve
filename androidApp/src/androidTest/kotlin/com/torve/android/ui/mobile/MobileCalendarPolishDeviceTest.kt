package com.torve.android.ui.mobile

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.calendar.*
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.presentation.calendar.CalendarUiState
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileCalendarPolishDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun entry(number: Int, day: Int = 29) = TraktCalendarEpisode("Show $number", 2, number,
        "An upcoming episode", "2026-10-${day}T18:00:00Z", number)

    @Test fun dedicatedCalendarShowsDatesAndPinsTheCurrentDayWhileScrolling() {
        val episodes = (1..6).map { entry(it) } + (7..12).map { entry(it, 30) }
        var opened: TraktCalendarEpisode? = null
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            MobileCalendarContent(CalendarUiState(episodes = episodes, traktConnected = true), { opened = it }, {})
        } }
        rule.onNodeWithText("Up Next").assertDoesNotExist()
        rule.onNodeWithTag("calendar_date_2026-10-29").assertIsDisplayed()
        rule.onNodeWithTag("calendar_episode_1_2_1").performClick()
        rule.runOnIdle { assertEquals(1, opened?.showTmdbId); assertEquals(2, opened?.season); assertEquals(1, opened?.episode) }
        rule.onNodeWithTag("calendar_list").performScrollToIndex(4)
        val top = rule.onNodeWithTag("calendar_list").fetchSemanticsNode().boundsInRoot.top
        val pinned = rule.onNodeWithTag("calendar_date_2026-10-29").fetchSemanticsNode().boundsInRoot
        assertEquals(top, pinned.top, 1.5f)
        rule.onNodeWithTag("calendar_list").performScrollToIndex(9)
        val next = rule.onNodeWithTag("calendar_date_2026-10-30").fetchSemanticsNode().boundsInRoot
        assertEquals(top, next.top, 1.5f)
        save("polish-calendar-pinned")
    }

    @Test fun emptyAndRefreshStatesRemainActionable() {
        var refreshes = 0
        var state by mutableStateOf(CalendarUiState(traktConnected = true))
        rule.setContent { TorveTheme(ThemeMode.LIGHT) { MobileCalendarContent(state, {}, { refreshes++ }) } }
        rule.onNodeWithText("No upcoming episodes").assertIsDisplayed()
        rule.onNodeWithTag("calendar_refresh").performClick()
        rule.runOnIdle { assertEquals(1, refreshes); state = state.copy(isLoading = true) }
        rule.onNodeWithTag("calendar_refresh").assertIsNotEnabled()
        rule.onNodeWithText("Loading calendar…").assertIsDisplayed()
    }

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
