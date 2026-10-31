package com.torve.android.ui.tv

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.TvMainActivity
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.settings.setTvFocusPreview
import com.torve.presentation.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File

/** Exercise Search inside the real TV activity and navigation rail. */
@OptIn(ExperimentalTestApi::class)
class TvSearchRootNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<TvMainActivity>()
    @Test fun defaultGridHasTenPostersWrapsAndReturnsFromHeader() = checkSearch(false)
    @Test fun previewGridWrapsAndReentersTheExpandedRow() = checkSearch(true)

    private fun checkSearch(preview: Boolean) {
        val nav = "tv_nav_item_${TvRoutes.SEARCH}"
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(nav).fetchSemanticsNodes().isNotEmpty() }
        val settings = KoinPlatform.getKoin().get<SettingsViewModel>()
        val previousCatalogue = settings.state.value.tmdbCatalogueEnabled
        val ui = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
        val prefs = rule.activity.getSharedPreferences("tv_prefs", 0)
        val owner = settings.state.value.personalDataUserId
        val prefix = "focus_preview.v1.${owner ?: "local"}."
        val originalUi = listOf("global", "page:search").associateWith { ui.all[prefix + it] as? Boolean }
        val oldDensity = prefs.getString("tv_search_density_v2", null)
        try {
            rule.runOnUiThread {
                rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                TvScreenCache.clear()
                setTvFocusPreview(rule.activity, owner, "global", false)
                setTvFocusPreview(rule.activity, owner, "page:search", preview)
                prefs.edit().remove("tv_search_density_v2").commit()
                settings.setTmdbCatalogueEnabled(true)
            }
            rule.onNodeWithTag(nav).performClick()
            val cardPrefix = if (preview) "catalog_poster_card:" else "search_result:"
            val cards = SemanticsMatcher("Search poster") {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(cardPrefix)
            }
            rule.waitUntil(90_000) { rule.onAllNodes(cards).fetchSemanticsNodes().size >= 10 }
            val view = rule.onNode(hasText("View") and hasAnyAncestor(hasTestTag("search_toolbar")))
            view.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            view.performKeyInput { pressKey(Key.DirectionDown) }
            fun focusedTag(): String = rule.onNode(cards and isFocused()).fetchSemanticsNode().config[SemanticsProperties.TestTag]
            rule.waitUntil(10_000) { rule.onAllNodes(cards and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            val first = focusedTag()
            val firstBounds = rule.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot
            repeat(9) { rule.onNode(cards and isFocused()).performKeyInput { pressKey(Key.DirectionRight) } }
            if (!preview) assertEquals("Ten posters must be in the same row", firstBounds.top,
                rule.onNode(cards and isFocused()).fetchSemanticsNode().boundsInRoot.top, 2f)
            val last = focusedTag()
            rule.onNodeWithTag(last).performKeyInput { pressKey(Key.DirectionUp) }
            val toolbarFocus = isFocused() and hasAnyAncestor(hasTestTag("search_toolbar"))
            rule.waitUntil(10_000) { rule.onAllNodes(toolbarFocus).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(toolbarFocus).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(10_000) { rule.onAllNodes(cards and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            if (preview) rule.onNodeWithTag(last).assertIsFocused()
            else rule.onNodeWithTag(last).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            rule.onNode(cards and isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(first).assertIsFocused() }.isSuccess }
            File(rule.activity.getExternalFilesDir(null), "search-ten-posters-$preview.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithTag(first).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(10_000) { runCatching { focusedTag() != first }.getOrDefault(false) }
            view.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            view.performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(10_000) { rule.onAllNodes(cards and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(cards and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(10_000) { rule.onAllNodes(cards and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(cards and isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitUntil(10_000) { rule.onAllNodes(cards and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            File(rule.activity.getExternalFilesDir(null), "search-failure-$preview.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString())
            File(rule.activity.getExternalFilesDir(null), "search-failure-$preview.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            rule.runOnUiThread {
                settings.setTmdbCatalogueEnabled(previousCatalogue)
                ui.edit().also { edit -> originalUi.forEach { (key, value) ->
                    if (value == null) edit.remove(prefix + key) else edit.putBoolean(prefix + key, value)
                } }.apply()
                prefs.edit().also { if (oldDensity == null) it.remove("tv_search_density_v2") else it.putString("tv_search_density_v2", oldDensity) }.apply()
            }
        }
    }
}
