package com.torve.android.ui.tv

import androidx.compose.material3.Surface
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.android.tv.settings.setTvExpandingPosters
import com.torve.android.ui.theme.TorveTheme
import com.torve.domain.model.*
import com.torve.presentation.settings.ThemeMode
import org.junit.Rule
import org.junit.Test

/** A missing title-free image must not hide the show's working normal backdrop. */
class TvExpandedArtworkFallbackTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun expandedCardRecoversFromMissingPreferredArtworkUsingNormalShowBackdrop() {
        val old = rule.activity.getSharedPreferences("tv_ui_prefs", 0)
            .getString("tv_browse_layout", "POSTER_ONLY") == "EXPANDING"
        val item = MediaItem("qa-expanded-artwork-fallback", type = MediaType.SERIES,
            title = "90 Day Fiancé: The Other Way", year = 2019, runtime = 45,
            overview = "A scheduled episode", cast = listOf(CastMember(1, "Cast")),
            logoUrl = "https://image.tmdb.org/t/p/w342/torve-missing-logo.png",
            titlelessBackdropUrl = "https://image.tmdb.org/t/p/w780/torve-missing-preferred-artwork.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w780/9jmxvWOt5xTjrJ03FNmViZCuZ5f.jpg",
            posterUrl = "https://image.tmdb.org/t/p/w342/torve-missing-poster.jpg",
            ratings = MediaRatings(imdbScore = 6.7f, rottenTomatoesScore = 80))
        try {
            setTvExpandingPosters(rule.activity, true)
            rule.setContent { TorveTheme(ThemeMode.DARK) { Surface {
                TvMediaRails(listOf(TvContentRail("upcoming", "Upcoming Schedule", listOf(item))),
                    FocusRequester(), {}, {}, {}, screenId = "qa-expanded-artwork-fallback",
                    presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
                    forcePosterTitles = true, showSeeAllCards = false)
            } } }
            val frame = rule.onNodeWithTag("catalog_poster_frame:${item.id}", useUnmergedTree = true)
            // In the upper scene area, a missing image is flat across each horizontal line.
            // The normal photographic backdrop has many distinct pixels across the same line.
            rule.waitUntil(20_000) {
                runCatching {
                    val pixels = frame.captureToImage().toPixelMap()
                    val row = pixels.height / 4
                    val colors = (pixels.width / 5 until pixels.width * 4 / 5 step 3).map { pixels[it, row] }
                    colors.distinct().size > 48 &&
                        ((colors.maxOf { it.red } - colors.minOf { it.red }) > .4f ||
                            (colors.maxOf { it.green } - colors.minOf { it.green }) > .4f ||
                            (colors.maxOf { it.blue } - colors.minOf { it.blue }) > .4f)
                }.getOrDefault(false)
            }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(rule.activity.getExternalFilesDir(null), "artwork-expanded-normal-backdrop-fallback.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        } finally { setTvExpandingPosters(rule.activity, old) }
    }
}
