package com.torve.android.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvLibraryChromeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    @Test fun allCollectionsKeepTheirMenuAboveScrollingRowTitles() {
        var selected by mutableStateOf(0)
        rule.setContent { MaterialTheme {
            val tools=remember { FocusRequester() }; val rail=remember { FocusRequester() }
            val tabs=remember { List(4) { FocusRequester() } }
            TvLibraryPage(header={ TvLibraryHeader(listOf("Watchlist","Favorites","Continue watching","History"),selected,tools,tabs,rail,{}, {selected=it},{}) }) {
                LazyColumn(Modifier.fillMaxSize().testTag("library.scroll")) {
                    items((0..60).toList()) { i -> Text("${listOf("Watchlist","Favorites","Continue watching","History")[selected]} row $i",
                        Modifier.fillMaxWidth().height(100.dp).testTag("library.row.$i")) }
                }
            }
        } }
        val header=rule.onNodeWithTag("library_header").fetchSemanticsNode().boundsInRoot
        repeat(4) { tab ->
            rule.onNodeWithTag("library_tab_$tab").performClick()
            rule.onNodeWithTag("library.scroll").performScrollToIndex(35)
            val viewport=rule.onNodeWithTag("library.content").fetchSemanticsNode().boundsInRoot
            val title=rule.onNodeWithTag("library.row.35").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val after=rule.onNodeWithTag("library_header").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(header,after)
            assertTrue("Content cannot draw inside the menu",viewport.top>=after.bottom)
            assertTrue("Scrolling headings remain below the menu",title.top>=after.bottom)
        }
    }
}
