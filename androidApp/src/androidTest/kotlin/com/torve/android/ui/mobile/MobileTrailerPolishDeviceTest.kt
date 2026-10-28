package com.torve.android.ui.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.components.MobileGlassButton
import com.torve.android.ui.detail.*
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.detail.DetailUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileTrailerPolishDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val bookmark = DetailSecondaryActionSpec("Watchlist", Icons.Rounded.Bookmark, Color.Transparent, Color.White, onClick = {})

    @Test fun trailerSitsBesidePlayAndMakesPlayNarrowerWithoutChangingHeight() {
        var trailer by mutableStateOf(true)
        var opened = 0
        rule.setContent { TorveTheme {
            DetailSecondaryActions("Movie", emptyList(), if (trailer) ({ opened++ }) else null, Modifier.width(370.dp),
                libraryStatus = DetailUiState(isInLibrary = true), watchlistAction = bookmark, primaryContent = {
                    MobileGlassButton({}, Modifier.weight(1f).height(48.dp).testTag("primary")) { Text("Watch now") }
                })
        } }
        val primary = rule.onNodeWithTag("primary").fetchSemanticsNode().boundsInRoot
        val icon = rule.onNodeWithTag("detail_trailer_button").fetchSemanticsNode().boundsInRoot
        assertTrue(icon.left >= primary.right)
        assertEquals(primary.center.y, icon.center.y, 1f)
        rule.onNodeWithTag("primary").assertHeightIsEqualTo(48.dp)
        rule.onNodeWithTag("detail_trailer_button").assertHeightIsEqualTo(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, opened); trailer = false }
        rule.onNodeWithTag("detail_trailer_button").assertDoesNotExist()
        assertTrue(rule.onNodeWithTag("primary").fetchSemanticsNode().boundsInRoot.width > primary.width)
    }

    @Test fun narrowPhonesKeepPlayReadableAndMoveLibraryStatusIntoMore() {
        rule.setContent { TorveTheme {
            DetailSecondaryActions("Movie", emptyList(), {}, Modifier.width(300.dp),
                libraryStatus = DetailUiState(isInLibrary = true), watchlistAction = bookmark, primaryContent = {
                    MobileGlassButton({}, Modifier.weight(1f).height(48.dp).testTag("primary")) { Text("Watch now") }
                })
        } }
        rule.onNodeWithText("Watch now").assertIsDisplayed()
        val primary = rule.onNodeWithTag("primary").fetchSemanticsNode().boundsInRoot
        assertTrue(primary.width > 100 * rule.activity.resources.displayMetrics.density)
        rule.onNodeWithTag("detail_more").performClick()
        rule.onNodeWithText("Library").assertIsDisplayed()
    }
}
