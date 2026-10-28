package com.torve.android.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.channels.MobileChannelsGuide
import com.torve.domain.model.*
import com.torve.presentation.channels.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class MobileGuideDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val channel = Channel(name = "Guide news", url = "https://example.test/live/news", playlistId = "p1", tvgId = "news", groupTitle = "Germany News")
    private val group = ChannelCategory("Germany News", 1, channels = listOf(EnrichedChannel(channel)), countryCode = "DE")
    private val base = System.currentTimeMillis().let { it - it % 3_600_000L }
    private fun fixture() = ChannelsUiState(selectedPlaylistId = "p1", selectedGroup = group.name,
        categories = listOf(group), guideChannels = listOf(EnrichedChannel(channel)),
        guideProgrammes = mapOf("p1::news" to listOf(EpgProgramme("p1::news", base, base + 3_600_000, "Current news"),
            EpgProgramme("p1::news", base + 3_600_000, base + 7_200_000, "Next news"))),
        epgState = EpgState.Loaded("https://example.test/epg", 1, 2, 1, 0))

    @Test fun categoryGuideRendersWhenFullPlaylistIsEmptyAndTimelineIsAligned() {
        val state = fixture()
        assertTrue(state.channels.isEmpty())
        rule.setContent { MaterialTheme { MobileChannelsGuide(state, listOf(group), {}, {}, {}) } }
        rule.onNodeWithText("Guide news").assertIsDisplayed()
        rule.onNodeWithText("No EPG Data").assertDoesNotExist()
        val first = rule.onNodeWithTag("guide_programme_$base").getUnclippedBoundsInRoot()
        val next = rule.onNodeWithTag("guide_programme_${base + 3_600_000}").getUnclippedBoundsInRoot()
        assertEquals(180f, next.left.value - first.left.value, 1f)
    }
    @Test fun loadingAndRetryKeepExistingChannelRowsVisible() {
        var state by mutableStateOf(fixture().copy(isLoadingGuide = true))
        var retried = false
        rule.setContent { MaterialTheme { MobileChannelsGuide(state, listOf(group), {}, { retried = true }, {}) } }
        rule.onNodeWithTag("guide_loading").assertIsDisplayed()
        rule.onNodeWithText("Guide news").assertIsDisplayed()
        rule.runOnIdle { state = state.copy(isLoadingGuide = false, epgState = EpgState.Error("offline"), guideError = "offline") }
        rule.onNodeWithTag("guide_retry").performClick()
        rule.runOnIdle { assertTrue(retried) }
        rule.onNodeWithText("Guide news").assertIsDisplayed()
    }
    @Test fun groupPickerSelectsByFullIdentityWhileDisplayingShortLabels() {
        val sports = group.copy(name = "Germany Sports")
        var chosen: String? = null
        rule.setContent { MaterialTheme { MobileChannelsGuide(fixture(), listOf(group, sports), { chosen = it }, {}, {}) } }
        rule.onNodeWithTag("guide_group_picker").performClick()
        rule.onNodeWithText("Sports", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals("Germany Sports", chosen) }
    }
}
