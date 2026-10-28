package com.torve.android.ui.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.components.*
import com.torve.android.ui.community.*
import com.torve.android.ui.channels.ChannelsSubTabBar
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.channels.ChannelsSubTab
import com.torve.presentation.detail.ContentComment
import com.torve.presentation.detail.ContentCommentUser
import com.torve.presentation.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobilePolishDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun advancedFiltersScrollToApplyAndKeepYearEdits() {
        var applied: com.torve.presentation.search.SearchFilter? = null
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            com.torve.android.ui.search.SearchFilterSheet(com.torve.presentation.search.SearchFilter(yearFrom = 2021), { applied = it }, {})
        } }
        val power = rule.onNodeWithText("Torve 75+")
        power.performScrollTo()
        assertTrue(power.fetchSemanticsNode().boundsInRoot.height <= 48 * rule.activity.resources.displayMetrics.density + 1f)
        rule.onNodeWithTag("search_filter_year_from").performScrollTo().performTextReplacement("2024")
        val apply = rule.activity.getString(com.torve.android.R.string.catalog_apply_filters)
        rule.onNodeWithText(apply).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(2024, applied?.yearFrom) }
        save("polish-advanced-filters")
    }

    @Test fun advancedFiltersClearAllBeforeApplying() {
        var applied: com.torve.presentation.search.SearchFilter? = null
        rule.setContent { TorveTheme(ThemeMode.LIGHT) {
            com.torve.android.ui.search.SearchFilterSheet(com.torve.presentation.search.SearchFilter(yearFrom = 2021), { applied = it }, {})
        } }
        rule.onNodeWithText(rule.activity.getString(com.torve.android.R.string.catalog_clear_all)).performClick()
        rule.onNodeWithText(rule.activity.getString(com.torve.android.R.string.catalog_apply_filters)).performScrollTo().performClick()
        rule.runOnIdle { assertNull(applied?.yearFrom) }
    }

    @Test fun bottomItemsHaveCenteredContentsAndSymmetricInset() {
        var active by mutableIntStateOf(0)
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            Row(Modifier.fillMaxWidth().mobileGlassSurface(RoundedCornerShape(34.dp)).padding(6.dp).testTag("bar")) {
                listOf("Home", "Channels", "Search", "Calendar", "Library", "Settings").forEachIndexed { index, label ->
                    MobileBottomDestination(label, Icons.Rounded.Home, active == index, { active = index }, Modifier.weight(1f))
                }
            }
        } }
        for (label in listOf("Home", "Channels", "Settings")) {
            val item = rule.onNodeWithTag("bottom_$label").fetchSemanticsNode().boundsInRoot
            val icon = rule.onNodeWithTag("bottom_icon_$label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val text = rule.onNodeWithTag("bottom_label_$label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(item.center.x, icon.center.x, 1f)
            assertEquals(item.center.x, text.center.x, 1f)
            assertEquals(item.center.y, (icon.top + text.bottom) / 2f, 1.5f)
        }
        rule.onNodeWithTag("bottom_Settings").performClick().assertIsSelected()
        rule.onNodeWithTag("bottom_Home").assertIsNotSelected()
        save("polish-bottom-menu")
    }

    @Test fun libraryOnlyExpandsActiveDestinationAndGlassRepairsDarkText() {
        var selected by mutableIntStateOf(0)
        var textColor = Color.Unspecified
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Watchlist", "Favorites", "Jellyfin", "Downloads").forEachIndexed { index, label ->
                        MobileLibraryDestination(label, Icons.Rounded.Bookmark, selected == index, { selected = index })
                    }
                }
                MobileGlassButton({}, colors = ButtonDefaults.buttonColors(contentColor = Color.Black)) {
                    textColor = LocalContentColor.current
                    Text("Sign In / Create Account")
                }
            }
        } }
        rule.onNodeWithText("Watchlist").assertIsDisplayed()
        rule.onNodeWithText("Favorites").assertDoesNotExist()
        rule.onNodeWithContentDescription("Favorites").performClick()
        rule.onNodeWithText("Favorites").assertIsDisplayed()
        rule.onNodeWithText("Watchlist").assertDoesNotExist()
        rule.runOnIdle { assertNotEquals(Color.Black, textColor) }
        save("polish-library-and-contrast")
    }

    @Test fun commentCarouselSwipesOpensAndKeepsRatingAtTopRight() {
        var opened: Long? = null
        val comments = (1L..5).map { id -> ContentComment(id, "A thoughtful review of this show. ".repeat(8),
            createdAt = "2026-10-27", userRating = if (id == 1L) 9 else 3,
            user = ContentCommentUser(username = "Reviewer $id")) }
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                SectionHeader("Comments", contentPadding = PaddingValues(0.dp))
                MobileCommentCarousel(comments) { opened = it.id }
                Spacer(Modifier.height(24.dp))
                SectionHeader("Details", contentPadding = PaddingValues(0.dp))
            }
        } }
        val author = rule.onNodeWithTag("comment_author_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rating = rule.onNodeWithTag("comment_rating_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(rating.left > author.left)
        assertEquals(author.top, rating.top, 1f)
        rule.onNodeWithTag("comment_1").performClick()
        rule.runOnIdle { assertEquals(1L, opened) }
        rule.onNodeWithTag("comment_carousel").performTouchInput { swipeLeft(durationMillis = 500) }
        rule.onNodeWithTag("comment_2").assertIsDisplayed()
        rule.onNodeWithTag("comment_carousel").performTouchInput { swipeRight(durationMillis = 500) }
        rule.onNodeWithTag("comment_1").assertIsDisplayed()
        assertTrue(commentRatingColor(9, true).green > commentRatingColor(9, true).red)
        assertTrue(commentRatingColor(2, true).red > commentRatingColor(2, true).green)
        assertNotEquals(commentRatingColor(5, true), commentRatingColor(9, true))
        save("polish-comments")
    }

    @Test fun fieldsAndCompactFiltersRemainReadableAndTappableInBothThemes() {
        var light by mutableStateOf(false)
        var query by mutableStateOf("")
        var tab by mutableStateOf(ChannelsSubTab.LIVE)
        rule.setContent { TorveTheme(if (light) ThemeMode.LIGHT else ThemeMode.DARK) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Search", contentPadding = PaddingValues(0.dp))
                MobileGlassTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("field"), placeholder = { Text("Search movies and shows") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MobileFilterPill("All", true, {})
                    MobileFilterPill("Movies", false, {})
                    MobileFilterPill("TV shows", false, {})
                }
                ChannelsSubTabBar(tab, { tab = it })
                MobileGlassButton({}, Modifier.height(48.dp).testTag("play"), accented = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Text("Watch now · S01E01", maxLines = 1)
                }
            }
        } }
        rule.onNodeWithTag("field").performTextInput("Office")
        rule.onNodeWithTag("field").assertTextContains("Office")
        rule.onNodeWithText("GUIDE").performClick().assertIsSelected()
        rule.onNodeWithTag("play").assertHeightIsEqualTo(48.dp)
        save("polish-fields-dark")
        rule.runOnIdle { light = true }
        rule.onNodeWithText("Office").assertIsDisplayed()
        save("polish-fields-light")
    }

    @Test fun sourceFormKeepsTheEyeBareAndConfirmationWorks() {
        var confirmed = false
        rule.setContent { TorveTheme(ThemeMode.DARK) {
            com.torve.android.ui.channels.AddPlaylistDialog("Example", "", "", "xtream",
                "https://example.test", "test-user", "fixture-secret", false, false, null, null,
                {}, {}, {}, {}, {}, {}, {}, {}, { confirmed = true }, {})
        } }
        rule.onNodeWithContentDescription("Show").performClick()
        rule.onNodeWithContentDescription("Hide").assertIsDisplayed()
        rule.onNodeWithText("Add", ignoreCase = true).performClick()
        rule.runOnIdle { assertTrue(confirmed) }
        save("polish-source-form")
    }

    private fun save(name: String) {
        val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        java.io.File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
