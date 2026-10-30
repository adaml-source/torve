package com.torve.android.ui.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.settings.setTvFocusPreview
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.settings.SettingsViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TvLibraryRailViewportTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    @Test fun libraryCollectionsKeepWholeCardsVisibleBelowTheirMenu() = checkCollections(1f, false)
    @Test fun largeTextKeepsLibraryCardsAndMetadataVisible() = checkCollections(1.5f, false)
    @Test fun expandingLibraryCardsKeepTheirDetailsInsideThePage() = checkCollections(1.5f, true)

    private fun checkCollections(fontScale: Float, previews: Boolean) {
        val owner = GlobalContext.get().get<SettingsViewModel>().state.value.personalDataUserId
        val prefs = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
        val keys = listOf("global", "page:library").map { "focus_preview.v1.${owner ?: "local"}.$it" }
        val before = keys.associateWith { prefs.all[it] as? Boolean }
        try {
            setTvFocusPreview(rule.activity, owner, "global", previews)
            setTvFocusPreview(rule.activity, owner, "page:library", previews)
            rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            var selected by mutableStateOf(0)
            val labels = listOf("Watchlist", "Favorites", "Continue watching", "History")
            rule.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MaterialTheme {
                        val tools = remember { FocusRequester() }; val rail = remember { FocusRequester() }
                        val tabs = remember { List(4) { FocusRequester() } }
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            TvLibraryPage(header = {
                                TvLibraryHeader(labels, selected, tools, tabs, rail, {}, { selected = it }, {})
                            }) {
                                key(selected) {
                                    val rows = (0..2).map { row -> TvContentRail("collection-$row", "${labels[selected]} $row",
                                        (0..5).map { column -> MediaItem("library-$selected-$row-$column",
                                            title = "Saved title $row/$column", type = MediaType.MOVIE, year = 2025,
                                            runtime = 100, overview = "Details about the selected title.") },
                                        cardStyle = if (selected == 2) TvCardStyle.BACKDROP else TvCardStyle.POSTER) }
                                    TvMediaRails(rows, rail, {}, {}, {}, screenId = "library_viewport_$selected",
                                        headerFocusRequester = tabs[selected], shouldAutoFocus = false,
                                        heroOverlay = { TvHeroOverlay(rows.first().items.first(), "Library", "Library",
                                            FocusRequester(), rail, {}, {}, topPadding = 12.dp, compact = true) },
                                        presentationMode = TvRailsPresentationMode.LibraryHero,
                                        focusExclusive = true, showSeeAllCards = false)
                                }
                            }
                        }
                    }
                }
            }
            for (tab in labels.indices) {
                rule.onNodeWithTag("library_tab_$tab").performClick()
                rule.onNodeWithTag("library_tab_$tab").performSemanticsAction(SemanticsActions.RequestFocus)
                rule.waitForIdle()
                capture("library-$fontScale-$previews-$tab-header.png")
                assertWholeCard(tab, 0, fontScale, previews)
                card(tab, 0).performSemanticsAction(SemanticsActions.RequestFocus)
                waitFocused(tab, 0)
                assertWholeCard(tab, 0, fontScale, previews)
                for ((from, to, key) in listOf(Triple(0, 1, Key.DirectionDown), Triple(1, 2, Key.DirectionDown), Triple(2, 1, Key.DirectionUp))) {
                    card(tab, from).performKeyInput { pressKey(key) }
                    waitFocused(tab, to)
                    assertWholeCard(tab, to, fontScale, previews)
                }
                capture("library-$fontScale-$previews-$tab-content.png")
            }
        } finally {
            val edit = prefs.edit()
            before.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putBoolean(key, value) }
            edit.apply()
        }
    }

    private fun card(tab: Int, row: Int) = if (tab == 2)
        rule.onNode(hasText("Saved title $row/0") and hasClickAction())
    else rule.onNodeWithTag("catalog_poster_card:library-$tab-$row-0")

    private fun waitFocused(tab: Int, row: Int) = rule.waitUntil(5_000) {
        runCatching { card(tab, row).assertIsFocused() }.isSuccess
    }

    private fun assertWholeCard(tab: Int, row: Int, fontScale: Float, previews: Boolean) {
        val header = rule.onNodeWithTag("library_header").getUnclippedBoundsInRoot()
        val viewport = rule.onNodeWithTag("catalog_rails_viewport").getUnclippedBoundsInRoot()
        val card = card(tab, row).getUnclippedBoundsInRoot()
        val metrics = "tab=$tab row=$row font=$fontScale previews=$previews menu=$header viewport=$viewport card=$card\n"
        File(rule.activity.getExternalFilesDir(null), "library-viewport-metrics.txt").appendText(metrics)
        assertTrue("A complete Library card must fit below its menu: $metrics", card.top >= header.bottom && card.top >= viewport.top && card.bottom + 8.dp <= viewport.bottom)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
