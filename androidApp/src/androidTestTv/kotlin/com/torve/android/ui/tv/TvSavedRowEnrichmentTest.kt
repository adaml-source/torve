package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.toMediaItemOrNull
import com.torve.android.tv.settings.setTvExpandingPosters
import com.torve.data.addon.StremioAddonClient
import com.torve.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Live installed-provider contract, including the exact titles reported missing.
 * Descriptions and cast come from the extension; numeric IDs can use external-ID mapping. */
class TvSavedRowEnrichmentTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun continueWatchingWithNumericIdLoadsTheCorrectIronManSynopsisAndCast() {
        val provider = InstalledAddon("https://v3-cinemeta.strem.io/manifest.json", AddonManifest("org.stremio.cinemeta", "Cinemeta", "1",
            resources = listOf("meta", "catalog"), types = listOf("movie", "series"), idPrefixes = listOf("tt"),
            catalogs = listOf(AddonCatalog("movie", "top", extra = listOf(AddonExtra("search"))))))
        val source = ClientTvAddonPreviewLookup(GlobalContext.get().get<StremioAddonClient>(),
            GlobalContext.get().get<com.torve.data.trakt.TraktClient>())
        val sparse = WatchProgress("1726", MediaType.MOVIE, "Iron Man").toMediaItemOrNull()!!
        assertNull(sparse.imdbId); assertNull(sparse.overview); assertTrue(sparse.cast.isEmpty())
        val value = runBlocking { kotlinx.coroutines.withTimeout(8_000) {
            loadTvAddonPreviewDetails(sparse, listOf(provider), source)
        } }
        assertNotNull("Exact numeric identity must resolve without enabling TMDB", value)
        requireNotNull(value)
        assertEquals("1726", value.id); assertEquals("tt0371746", value.imdbId)
        assertEquals(2008, value.year); assertEquals(126, value.runtime)
        assertFalse(value.overview.isNullOrBlank()); assertFalse(value.logoUrl.isNullOrBlank()); assertTrue(value.cast.isNotEmpty())
        val previous = rule.activity.getSharedPreferences("tv_ui_prefs", 0).getString("tv_browse_layout", "POSTER_ONLY") == "EXPANDING"
        try {
            setTvExpandingPosters(rule.activity, true)
            rule.setContent { com.torve.android.ui.theme.TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) {
                androidx.compose.material3.Surface {
                    TvMediaRails(listOf(TvContentRail("continue_watching", "Continue Watching", listOf(value))),
                        FocusRequester(), {}, {}, {}, screenId = "continue-watching-provider-test",
                        browseLayout = TvBrowseLayout.POSTER_ONLY, presentationMode = TvRailsPresentationMode.CatalogHero,
                        focusExclusive = true, showSeeAllCards = false)
                }
            } }
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("catalog_poster_card:1726").assertIsFocused() }.isSuccess }
            rule.onNodeWithTag("catalog_expanded_synopsis:1726", useUnmergedTree = true)
                .assertIsDisplayed().assertTextContains(value.overview!!.take(30), substring = true)
            rule.onNodeWithTag("catalog_expanded_cast:1726", useUnmergedTree = true)
                .assertIsDisplayed().assertTextContains(value.cast.first().name, substring = true)
            rule.onNodeWithText("2008", substring = true, useUnmergedTree = true).assertIsDisplayed()
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            java.io.File(rule.activity.getExternalFilesDir(null), "saved-row-continue-watching-imdb-identity.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { setTvExpandingPosters(rule.activity, previous) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun watchlistRecentAndPopularCardsRenderActualExtensionDetails() {
        val provider = InstalledAddon("https://v3-cinemeta.strem.io/manifest.json", AddonManifest("org.stremio.cinemeta", "Cinemeta", "1",
            resources=listOf("meta","catalog"), types=listOf("movie","series"), idPrefixes=listOf("tt"),
            catalogs=listOf(AddonCatalog("movie","top",extra=listOf(AddonExtra("search"))))))
        val source = ClientTvAddonPreviewLookup(GlobalContext.get().get<StremioAddonClient>())
        val sparse = listOf(
            MediaItem("106379",tmdbId=106379,imdbId="tt12637874",type=MediaType.SERIES,title="Fallout",year=2024),
            MediaItem("legacy-recent",type=MediaType.MOVIE,title="The Love Hypothesis"),
            MediaItem("tt12042730",type=MediaType.MOVIE,title="Project Hail Mary",year=2026))
        val values = runBlocking { sparse.map { loadTvAddonPreviewDetails(it,listOf(provider),source) } }
        values.forEachIndexed { index, value ->
            assertNotNull(sparse[index].title,value)
            assertEquals(sparse[index].id,value!!.id)
            assertFalse(value.title,value.overview.isNullOrBlank())
            assertFalse(value.title,value.logoUrl.isNullOrBlank())
            assertTrue(value.title,value.cast.isNotEmpty())
        }
        val prefs=rule.activity.getSharedPreferences("tv_ui_prefs",0)
        val previous=prefs.getString("tv_browse_layout","POSTER_ONLY")=="EXPANDING"
        try {
            setTvExpandingPosters(rule.activity,true)
            val keys=listOf("watchlist","recently_watched","popular_movies")
            rule.setContent { MaterialTheme {
                TvMediaRails(values.mapIndexed { index, value -> TvContentRail(keys[index],keys[index],listOf(value!!)) },
                    FocusRequester(),{},{},{},screenId="saved-row-provider-test",browseLayout=TvBrowseLayout.POSTER_ONLY,
                    presentationMode=TvRailsPresentationMode.CatalogHero,focusExclusive=true,showSeeAllCards=false)
            } }
            values.forEachIndexed { index,value ->
                val id=value!!.id
                rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("catalog_poster_card:$id").assertIsFocused() }.isSuccess }
                rule.onNodeWithTag("catalog_expanded_synopsis:$id",useUnmergedTree=true).assertTextContains(value.overview!!.take(30),substring=true)
                rule.onNodeWithTag("catalog_expanded_cast:$id",useUnmergedTree=true).assertTextContains(value.cast.first().name,substring=true)
                val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"saved-row-${keys[index]}.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                if(index<values.lastIndex) rule.onNodeWithTag("catalog_poster_card:$id").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            }
        } finally { setTvExpandingPosters(rule.activity,previous) }
    }
}
