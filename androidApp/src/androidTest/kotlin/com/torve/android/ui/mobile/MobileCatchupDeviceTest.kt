package com.torve.android.ui.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.channels.*
import com.torve.android.ui.components.*
import com.torve.android.ui.detail.MobileDetailMetadata
import com.torve.android.ui.product.ProductToolRow
import com.torve.android.ui.theme.TorveTheme
import com.torve.domain.model.*
import com.torve.presentation.channels.ChannelsSubTab
import com.torve.presentation.settings.ThemeMode
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileCatchupDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val movie = MediaItem("tt45368240", imdbId = "tt45368240", title = "You Can See Everything",
        type = MediaType.MOVIE, year = 2026, runtime = 174,
        ratings = MediaRatings(imdbScore = 8f, imdbVotes = 203, rottenTomatoesScore = 100))
    private val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES),
        providerOrder = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES), showRatingsOnDetailPage = true)

    @Test fun contentPlaceholdersDoNotStartMetadataRequests() {
        var calls = 0
        rule.setContent { MaterialTheme {
            val value = rememberMobileCurrentRatings(movie.copy(isContentPlaceholder = true), true) { calls++; it }
            Text(value?.imdbScore.toString())
        } }
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun settingsDirectoryKeepsAccountFirstAndAcceptsLargeLabels() {
        var selected: String? = null
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                com.torve.android.ui.community.PhoneSettingsDirectory { selected = it }
            }
        } }
        val account = rule.onNodeWithTag("settings.group.account").fetchSemanticsNode().boundsInRoot
        val sources = rule.onNodeWithTag("settings.group.connections").fetchSemanticsNode().boundsInRoot
        assertTrue(account.top < sources.top)
        rule.onNodeWithTag("settings.group.account").performClick()
        rule.runOnIdle { assertEquals("account", selected) }
    }

    @Test fun ratingsStayWithMetadataAndRespectVisibilityAtLargeTextSizes() {
        var visible by mutableStateOf(true)
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Column(Modifier.width(320.dp).background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    MobileDetailMetadata(movie, movie.ratings!!.copy(imdbScore = 9.3f, imdbVotes = null),
                        prefs.copy(showRatingsOnDetailPage = visible))
                    Text("Documentary", Modifier.testTag("fixture_genre"))
                }
            }
        } }
        rule.onNodeWithText("2026").assertIsDisplayed()
        rule.onNodeWithText("9.3").assertIsDisplayed()
        rule.onNodeWithText("100%").assertIsDisplayed()
        val metadata = rule.onNodeWithTag("detail_top_metadata").fetchSemanticsNode().boundsInRoot
        val genre = rule.onNodeWithTag("fixture_genre").fetchSemanticsNode().boundsInRoot
        assertTrue("Ratings must precede genres without overlap", metadata.bottom <= genre.top + 1f)
        assertTrue(rule.onNodeWithText("100%").fetchSemanticsNode().boundsInRoot.right <= metadata.right)
        save("phone-detail-large-text")
        rule.runOnIdle { visible = false }
        rule.onNodeWithText("9.3").assertDoesNotExist()
        rule.onNodeWithText("2026").assertIsDisplayed()
    }

    @Test fun settledCardRefreshReplacesOldPairAndRejectsLateSavedValues() {
        var source by mutableStateOf(movie)
        var active by mutableStateOf(false)
        var calls = 0
        var displayed: MediaRatings? = null
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            val current = rememberMobileCurrentRatings(source, active) {
                calls++
                it.copy(ratings = it.ratings!!.copy(imdbScore = 9.3f, imdbVotes = null))
            }
            SideEffect { displayed = current }
            MultiRatingPills(current!!, prefs = prefs)
        } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle { assertEquals(0, calls); active = true }
        rule.waitUntil(5_000) { displayed?.imdbScore == 9.3f }
        rule.onNodeWithText("9.3").assertIsDisplayed()
        rule.runOnIdle {
            assertNull(displayed?.imdbVotes)
            assertEquals(100, displayed?.rottenTomatoesScore)
            source = source.copy(ratings = source.ratings!!.copy(imdbScore = 8.1f))
        }
        rule.onNodeWithText("9.3").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun swipingAwayCancelsLookupAndAnOfflineCardKeepsItsOwnRating() {
        var source by mutableStateOf(movie)
        var started = false
        var cancelled = false
        rule.setContent { MaterialTheme {
            val value = rememberMobileCurrentRatings(source, true) {
                if (it.id == movie.id) {
                    started = true
                    try { awaitCancellation() } finally { cancelled = true }
                } else null
            }
            MultiRatingPills(value!!, prefs = prefs)
        } }
        rule.waitUntil(5_000) { started }
        rule.runOnIdle { source = movie.copy(id = "second-title", ratings = MediaRatings(imdbScore = 7.2f)) }
        rule.waitUntil(5_000) { cancelled }
        rule.onNodeWithText("7.2").assertIsDisplayed()
        rule.onNodeWithText("9.3").assertDoesNotExist()
    }

    @Test fun glassControlsWorkInBothThemesAndRetainTouchTargets() {
        var light by mutableStateOf(false)
        var clicks = 0
        var tab by mutableStateOf(ChannelsSubTab.LIVE)
        var played = false
        rule.setContent { TorveTheme(if (light) ThemeMode.LIGHT else ThemeMode.DARK) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary.copy(alpha = .10f))))
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MobileDetailMetadata(movie, movie.ratings!!.copy(imdbScore = 9.3f, imdbVotes = null), prefs)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MobileGlassButton({ clicks++ }, Modifier.testTag("glass_play"), accented = true) { Text("Watch now") }
                    MobileGlassOutlinedButton({}, enabled = false) { Text("Unavailable") }
                }
                ProductToolRow("Account", "Profiles and sign-in", Icons.Rounded.Settings, { clicks++ })
                ChannelsSubTabBar(tab, { tab = it })
                CategoryHeader("Germany News", 1, setOf("HD"), true, {})
                ChannelRow(EnrichedChannel(Channel(name = "News", url = "https://example.test/news", playlistId = "p1")),
                    { played = true }, {})
            }
        } }
        rule.onNodeWithText("Watch now").performClick()
        rule.onNodeWithText("Unavailable").assertIsNotEnabled()
        rule.onNodeWithTag("glass_play").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("GUIDE").performClick().assertIsSelected()
        rule.onNodeWithText("News").performClick()
        rule.runOnIdle { assertEquals(1, clicks); assertTrue(played); assertEquals(ChannelsSubTab.GUIDE, tab) }
        save("phone-glass-dark")
        rule.runOnIdle { light = true }
        rule.onNodeWithText("Watch now").assertIsDisplayed()
        rule.onNodeWithText("9.3").assertIsDisplayed()
        save("phone-glass-light")
    }

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
