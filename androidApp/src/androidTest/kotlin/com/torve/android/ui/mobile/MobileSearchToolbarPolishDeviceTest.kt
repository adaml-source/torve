package com.torve.android.ui.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.community.PageGridState
import com.torve.android.ui.search.SearchBrowseToolbar
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileSearchToolbarPolishDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun phoneTypeIconsToggleExclusivelyAndClearBackToAll() = checkToolbar(400, 1f)

    @Test fun narrowPhoneTypeIconsAndActionsKeepOneRowWithLargeText() = checkToolbar(300, 1.5f)

    private fun checkToolbar(width: Int, fontScale: Float) {
        var selected by mutableStateOf("all")
        var filters = 0
        var sends = 0
        val grid = PageGridState(3) {}
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SearchBrowseToolbar(selected, { selected = it }, grid, 2, { filters++ }, true, "22 results", { sends++ }, Modifier.width(width.dp))
            }
        } }
        rule.onNodeWithTag("search.toolbar").assertHeightIsEqualTo(48.dp)
        val types = listOf("movie", "tv", "people")
        val controls = (types.map { "search.category.$it" } + listOf("search.filters", "search.more")).map {
            rule.onNodeWithTag(it).assertIsDisplayed().assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
        }
        controls.zipWithNext().forEach { (left, right) ->
            assertTrue("Actions need a visible gap", right.left - left.right >= 5 * rule.activity.resources.displayMetrics.density)
            assertEquals("All controls align on one row", left.center.y, right.center.y, 1f)
        }
        rule.onNodeWithText("All types").assertDoesNotExist()
        rule.onNodeWithContentDescription("Movies").assertIsOff()
        rule.onNodeWithContentDescription("TV shows").assertIsOff()
        rule.onNodeWithContentDescription("People").assertIsOff()
        types.forEach { type ->
            rule.onNodeWithTag("search.category.$type").performClick().assertIsOn()
            types.filterNot { it == type }.forEach { other -> rule.onNodeWithTag("search.category.$other").assertIsOff() }
            rule.runOnIdle { assertEquals(type, selected) }
            rule.onNodeWithTag("search.category.$type").performClick().assertIsOff()
            rule.runOnIdle { assertEquals("all", selected) }
        }
        rule.onNodeWithTag("search.category.movie").performClick()
        rule.onNodeWithTag("search.category.tv").performClick().assertIsOn()
        rule.onNodeWithTag("search.category.movie").assertIsOff()
        rule.runOnIdle { assertEquals("tv", selected); assertEquals(0, filters); assertEquals(0, sends) }
        rule.onNodeWithTag("search.filters").performClick()
        rule.onNodeWithTag("search.more").performClick()
        rule.onNodeWithTag("search.send").performClick()
        rule.runOnIdle { assertEquals(1, filters); assertEquals(1, sends) }
        rule.onNodeWithTag("search.more").performClick()
        rule.onNodeWithTag("search.grid").performClick()
        rule.onNodeWithTag("search.grid.4").performClick()
        rule.runOnIdle { assertEquals(4, grid.columns) }
        rule.onNodeWithTag("search.grid.4").assertDoesNotExist()
        rule.onNodeWithTag("search.category.tv").performClick().assertIsOff()
        rule.runOnIdle { assertEquals("all", selected) }
        if (width == 400) {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(rule.activity.getExternalFilesDir(null), "polish-search-toolbar.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
