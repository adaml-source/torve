package com.torve.android.ui.tv

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.streamUiKey
import com.torve.android.tv.components.TvStreamResultRow
import com.torve.android.tv.components.TvStreamSortControls
import com.torve.android.tv.components.readTvStreamSortMode
import com.torve.android.tv.components.tvStreamPresentation
import com.torve.android.tv.components.writeTvStreamSortMode
import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamSortMode
import com.torve.data.addon.sortStreamResults
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidateUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvStreamSelectionUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun chosenSizeSortSurvivesLeavingAndReopeningSources() {
        val prefs = composeRule.activity.getSharedPreferences("roi_456_sort_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit().putString("tv_stream_sort_v1", "legacy-mode").commit()
        var visible by mutableStateOf(true)
        var sources by mutableStateOf(listOf(
            ParsedStream("Panda", "1080p", "small.mkv", size = "900 MB", languages = listOf("EN")),
            ParsedStream("Panda", "4K", "large.mkv", size = "20 GB", languages = listOf("DE"), isCached = true),
            ParsedStream("Panda", "1080p", "unknown.mkv"),
        ))
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    var mode by remember { mutableStateOf(prefs.readTvStreamSortMode()) }
                    Column(Modifier.fillMaxSize()) {
                        TvStreamSortControls(mode, { mode = it; prefs.writeTvStreamSortMode(it) })
                        LazyColumn {
                            items(sortStreamResults(sources, mode), key = { it.title }) { source ->
                                TvStreamResultRow(tvStreamPresentation(source), Modifier.testTag(source.title), onClick = {})
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Recommended").assertIsSelected()
        composeRule.onNodeWithText("Largest first").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Largest first").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("Largest first").assertIsFocused().assertIsSelected()
        composeRule.runOnIdle { sources = sources + ParsedStream("Panda", "4K", "new.mkv", size = "30 GB") }
        composeRule.onNodeWithText("Largest first").assertIsFocused()
        composeRule.runOnIdle { visible = false }
        composeRule.onNodeWithText("Largest first").assertDoesNotExist()
        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithText("Largest first").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(StreamSortMode.SIZE_DESC, prefs.readTvStreamSortMode())
            assertEquals(listOf("new.mkv", "large.mkv", "small.mkv", "unknown.mkv"),
                sortStreamResults(sources, prefs.readTvStreamSortMode()).map { it.title })
        }
        prefs.edit().clear().commit()
    }

    @Test
    fun readableSourceKeepsTechnicalDetailsSecondaryAndRemoteSelectionWorks() {
        val stream = ParsedStream("Panda", "1080p", "The.Gentlemen.S02E04.1080p.WEB-DL.mkv",
            size = "7.2 GB", languages = listOf("EN"), codec = "HEVC", isCached = true)
        var selections = 0
        composeRule.setContent {
            MaterialTheme {
                TvStreamResultRow(tvStreamPresentation(stream), Modifier.testTag("source"), onClick = { selections++ })
            }
        }
        composeRule.onNodeWithText("1080p · English · 7.2 GB").assertIsDisplayed()
        composeRule.onNodeWithText("Ready now").assertIsDisplayed()
        composeRule.onNodeWithText(stream.title).assertDoesNotExist()
        composeRule.onNodeWithTag("source").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText(stream.title).assertIsDisplayed()
        composeRule.onNodeWithText("Cached at your provider", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("source").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(1, selections) }
        composeRule.onNodeWithTag("source").assertIsFocused()
    }

    @Test
    fun newResultsAndUpdatedRankingKeepTheChosenSourceFocused() {
        val chosen = ParsedStream("Panda", "1080p", "chosen.mkv", infoHash = "chosen-source", score = 30)
        var rows by mutableStateOf(listOf(chosen))
        composeRule.setContent { MaterialTheme {
            LazyColumn {
                items(rows, key = { it.streamUiKey() }) { stream ->
                    TvStreamResultRow(tvStreamPresentation(stream), Modifier.testTag(stream.title), onClick = {})
                }
            }
        } }
        composeRule.onNodeWithTag("chosen.mkv").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.runOnIdle {
            rows = listOf(ParsedStream("Panda", "4K", "new.mkv", infoHash = "new-source"), chosen.copy(score = 100, isCached = true))
        }
        composeRule.onNodeWithTag("chosen.mkv").assertIsFocused()
        composeRule.onNodeWithText("Ready now").assertIsDisplayed()
    }

    @Test
    fun readinessRefreshDoesNotDestroyTheFocusedSource() {
        val stream = ParsedStream("Panda", "1080p", "source.mkv", isCached = true)
        var state by mutableStateOf(UsenetCandidateUiModel(candidateId = "candidate",
            availabilityState = UsenetAvailability.READY, lastStateChangeAt = 1))
        composeRule.setContent {
            MaterialTheme {
                TvStreamResultRow(tvStreamPresentation(stream, usenet = state), Modifier.testTag("source"), onClick = {})
            }
        }
        composeRule.onNodeWithTag("source").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.runOnIdle { state = state.copy(availabilityState = UsenetAvailability.PREPARING) }
        composeRule.onNodeWithText("Preparing").assertIsDisplayed()
        composeRule.onNodeWithTag("source").assertIsFocused()
        composeRule.runOnIdle { state = state.copy(availabilityState = UsenetAvailability.UNAVAILABLE) }
        composeRule.onNodeWithText("Unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("source").assertIsFocused()
    }
}
