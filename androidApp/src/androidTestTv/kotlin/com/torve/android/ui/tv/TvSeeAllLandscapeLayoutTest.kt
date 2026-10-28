package com.torve.android.ui.tv

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTheme
import com.torve.android.tv.screens.TvSeeAllScreen
import com.torve.android.tv.settings.*
import com.torve.data.addon.StremioMeta
import com.torve.data.addon.toMediaItem
import com.torve.domain.model.MediaRatings
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Exercise the real See All screen and toolbar, rather than a rail-only host.
 * Local snapshots of public metadata/artwork keep geometry checks independent
 * of network speed and provider changes. Never writes production app data. */
@OptIn(ExperimentalTestApi::class)
class TvSeeAllLandscapeLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun card(index: Int) = rule.onNodeWithTag("catalog_poster_card:qa-seeall-$index")
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun realFiveColumnSeeAllKeepsSinglePreviewCompactRowsAndToolbarFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val metas = Json { ignoreUnknownKeys = true }.decodeFromString<List<StremioMeta>>(
            assets.open("fixtures/seeall/metas.json").bufferedReader().use { it.readText() })
        val cachedArtwork = mutableMapOf<String, java.io.File>()
        fun local(name: String?, posterArtwork: Boolean = false): String? = name?.let {
            val file = java.io.File(rule.activity.cacheDir, "seeall-$it")
            assets.open("fixtures/seeall/$it").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            if (posterArtwork) "https://seeall-fixture.invalid/$it".also { alias -> cachedArtwork[alias] = file }
            else file.toURI().toString()
        }
        val fixtures = metas.map { it.toMediaItem().copy(
            posterUrl = local(it.poster, true), backdropUrl = local(it.background), logoUrl = local(it.logo),
            titlelessBackdropUrl = local(it.background, true),
            runtime = 125, ratings = MediaRatings(imdbScore = 8.3f, rottenTomatoesScore = 94, imdbVotes = 31000)) }
        runBlocking {
            for ((url, file) in cachedArtwork) {
                assertTrue("Fixture artwork must decode before the visual check", SingletonImageLoader.get(rule.activity)
                    .execute(ImageRequest.Builder(rule.activity).data(file.toURI().toString()).memoryCacheKey(url)
                        .size(if (file.name.endsWith("-poster")) 512 else 1280,
                            if (file.name.endsWith("-poster")) 768 else 720).build()) is SuccessResult)
            }
        }
        val items = (0..14).map { index -> fixtures[index % fixtures.size].copy(id = "qa-seeall-$index") }
        val key = "qa_home_landscape_layout"
        val owner = GlobalContext.get().get<SettingsViewModel>().state.value.personalDataUserId
        val prefs = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
        val globalKey = "focus_preview.v1.${owner ?: "local"}.global"
        val previousGlobal = prefs.all[globalKey] as? Boolean
        val previousLayout = prefs.getString(TV_BROWSE_LAYOUT_KEY, "POSTER_ONLY")
        val previousColumns = prefs.all[TV_SEE_ALL_POSTER_COLUMNS_KEY] as? Int
        try {
            setTvExpandingPosters(rule.activity, true)
            setTvFocusPreview(rule.activity, owner, "global", true)
            setTvSeeAllPosterColumns(rule.activity, 5)
            SeeAllViewModel.pendingItems[key] = "Recently Watched" to items
            var registeredEntry: FocusRequester? = null
            rule.setContent { TorveTheme {
                TvSeeAllScreen(key, "movie", "Recently Watched", FocusRequester(), {}, {},
                    { registeredEntry = it }, {})
            } }
            // TvRoot enters this sub-route through the registered requester.
            // The standalone host must supply that same navigation handoff.
            rule.waitUntil(10_000) { runCatching { card(0).assertExists() }.isSuccess }
            rule.runOnIdle { checkNotNull(registeredEntry).requestFocus() }
            rule.waitUntil(10_000) { runCatching { card(0).assertIsFocused() }.isSuccess }
            rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithText("Recently Watched").assertIsDisplayed()
            rule.onNodeWithText("Posters: 5").assertIsDisplayed()
            val image = rule.onNodeWithTag("catalog_poster_frame:qa-seeall-0", true).getUnclippedBoundsInRoot()
            assertEquals(16f / 9f, (image.right - image.left).value / (image.bottom - image.top).value, .03f)
            val inactive = rule.onNodeWithTag("catalog_poster_frame:qa-seeall-5", true).getUnclippedBoundsInRoot()
            assertEquals("An unfocused row must contain portrait posters", 2f / 3f,
                (inactive.right - inactive.left).value / (inactive.bottom - inactive.top).value, .03f)
            rule.onNodeWithTag("catalog_expanded_cast:qa-seeall-0", true)
                .assertIsDisplayed().assertTextContains(items[0].cast.first().name, substring = true)
            val caption = rule.onNodeWithTag("catalog_poster_caption:qa-seeall-0", true).getUnclippedBoundsInRoot()
            val nextRow = rule.onNodeWithTag("catalog_rail_section:see_all:$key:movie:row:1", true).getUnclippedBoundsInRoot()
            assertTrue("Blank space between metadata and next row: $caption / $nextRow", nextRow.top - caption.bottom <= 40.dp)
            val synopsisStart = items[0].overview!!.take(35)
            rule.onAllNodesWithText(synopsisStart, substring = true, useUnmergedTree = true).assertCountEquals(1)
            val pixels = rule.onNodeWithTag("catalog_poster_frame:qa-seeall-0", true).fetchSemanticsNode().boundsInRoot
            rule.waitUntil(5_000) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                val colors = mutableSetOf<Int>()
                for (y in pixels.top.toInt() + 15 until (pixels.top + pixels.height * .5f).toInt() step 12)
                    for (x in pixels.left.toInt() + 15 until pixels.right.toInt() - 15 step 12) colors.add(bitmap.getPixel(x, y))
                bitmap.recycle()
                colors.size > 150
            }
            screenshot("seeall-five-columns-poster")
            card(0).performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Sort: Default").assertIsFocused() }.isSuccess }
            rule.onAllNodesWithText(synopsisStart, substring = true, useUnmergedTree = true).assertCountEquals(0)
            val unfocused = rule.onNodeWithTag("catalog_poster_frame:qa-seeall-0", true).getUnclippedBoundsInRoot()
            assertEquals("Toolbar focus must leave every poster portrait", 2f / 3f,
                (unfocused.right - unfocused.left).value / (unfocused.bottom - unfocused.top).value, .03f)
            screenshot("seeall-five-columns-toolbar")
            rule.onNodeWithText("Sort: Default").performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(5_000) { runCatching { card(0).assertIsFocused() }.isSuccess }
            card(0).performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitUntil(5_000) { runCatching { card(1).assertIsFocused() }.isSuccess }
            val second = rule.onNodeWithTag("catalog_poster_frame:qa-seeall-1", true).getUnclippedBoundsInRoot()
            assertEquals(image.top, second.top)
            assertTrue(kotlin.math.abs(image.left.value - second.left.value) < 2f)
            card(1).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(5_000) { runCatching { card(5).assertIsFocused() }.isSuccess }
            card(5).performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitUntil(5_000) { runCatching { card(1).assertIsFocused() }.isSuccess }
            screenshot("seeall-five-columns-return")
        } catch (error: Throwable) {
            screenshot("seeall-failure-before-cleanup")
            java.io.File(rule.activity.getExternalFilesDir(null), "seeall-failure-semantics.txt")
                .writeText(rule.onRoot(useUnmergedTree = true).printToString())
            throw error
        } finally {
            SeeAllViewModel.pendingItems.remove(key)
            prefs.edit().putString(TV_BROWSE_LAYOUT_KEY, previousLayout).apply {
                if (previousGlobal == null) remove(globalKey) else putBoolean(globalKey, previousGlobal)
                if (previousColumns == null) remove(TV_SEE_ALL_POSTER_COLUMNS_KEY) else putInt(TV_SEE_ALL_POSTER_COLUMNS_KEY, previousColumns)
            }.apply()
        }
    }
}
