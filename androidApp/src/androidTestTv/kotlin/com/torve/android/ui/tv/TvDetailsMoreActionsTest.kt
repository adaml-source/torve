package com.torve.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvDetailsMenuAction
import com.torve.android.tv.screens.TvDetailsMoreActions
import com.torve.android.tv.screens.tvDetailsLibraryMenu
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.model.MediaType
import com.torve.presentation.detail.DetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvDetailsMoreActionsTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun repeatedBackFromMoreReturnsFocusToItsButtonWithoutLeavingDetails() {
        var backCalls = 0
        composeRule.setContent {
            MaterialTheme {
                BackHandler { backCalls++ }
                val state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { TvDetailsMoreActions("details:test", "The Gentlemen", listOf(
                        TvDetailsMenuAction("favorite", "Add to Favorites") {},
                        TvDetailsMenuAction("watched", "Unmark series") {},
                        TvDetailsMenuAction("rating", "Rate") {},
                    ), state, onContentFocused = {}) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(3) {
            composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
            waitForFocus("Add to Favorites")
            composeRule.onNodeWithText("Add to Favorites").performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithText("Unmark series").assertIsFocused()
            composeRule.onNodeWithText("Unmark series").performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithText("Rate").assertIsFocused()
            composeRule.onNodeWithText("Rate").performKeyInput { pressKey(Key.Back) }
            waitForFocus("More")
        }
        composeRule.runOnIdle { assertEquals(0, backCalls) }
    }

    @Test
    fun selectingAnActionClosesMoreAndRestoresFocusAfterOneCallback() {
        var watchedCalls = 0
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { TvDetailsMoreActions("details:select", "The Gentlemen", listOf(
                        TvDetailsMenuAction("favorite", "Add to Favorites") {},
                        TvDetailsMenuAction("watched", "Mark series watched") { watchedCalls++ },
                    ), state, onContentFocused = {}) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add to Favorites")
        composeRule.onNodeWithText("Add to Favorites").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        waitForFocus("More")
        composeRule.runOnIdle { assertEquals(1, watchedCalls) }
    }

    @Test
    fun changingAnActionLabelKeepsItsFocusAndCloseRemainsReachable() {
        var favoriteLabel by mutableStateOf("Add to Favorites")
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { TvDetailsMoreActions("details:refresh", "A title with a longer name", listOf(
                        TvDetailsMenuAction("favorite", favoriteLabel) {},
                    ), state, onContentFocused = {}) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add to Favorites")
        composeRule.runOnIdle { favoriteLabel = "Remove from Favorites" }
        composeRule.onNodeWithText("Remove from Favorites").assertIsFocused().assertIsDisplayed()
        composeRule.onNodeWithText("Remove from Favorites").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Close").assertIsFocused()
        composeRule.onNodeWithText("Close").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("More")
    }

    private fun waitForFocus(label: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsFocused()
                label != "More" || composeRule.activity.hasWindowFocus()
            }.getOrDefault(false)
        }
    }

    @Test
    fun backgroundLibraryCompletionKeepsMoreFocusAndStatusOnlyAppearsInsideMenu() {
        var loading by mutableStateOf(true)
        var status by mutableStateOf<MediaLifecycleStatus?>(null)
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                val library = tvDetailsLibraryMenu(status, loading, null, false, 1, {}, {}, {}, {}, {})
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { TvDetailsMoreActions("details:library-background", "Test movie",
                        listOf(TvDetailsMenuAction("favorite", "Add to Favorites") {}) + library.actions,
                        state, onContentFocused = {}, libraryStatusText = library.statusText,
                        librarySupportingText = library.supportingText) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Library: Checking library availability…").assertDoesNotExist()
        composeRule.runOnIdle { loading = false; status = MediaLifecycleStatus(1, MediaType.MOVIE, MediaLifecycleState.AVAILABLE) }
        composeRule.onNodeWithText("More").assertIsFocused()
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add to Favorites")
        composeRule.onNodeWithText("Library: Available in your library").assertIsDisplayed()
    }

    @Test
    fun completingLibraryLookupInsideMoreKeepsFocusAndRetryReturnsToMore() {
        var loading by mutableStateOf(true)
        var status by mutableStateOf<MediaLifecycleStatus?>(null)
        var retries = 0
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                val library = tvDetailsLibraryMenu(status, loading, null, true, 2, {}, {}, {
                    retries++
                    status = status?.copy(state = MediaLifecycleState.PROCESSING)
                }, {}, {})
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { TvDetailsMoreActions("details:library-lookup", "The Gentlemen",
                        listOf(TvDetailsMenuAction("favorite", "Add to Favorites") {}) + library.actions,
                        state, onContentFocused = {}, libraryStatusText = library.statusText,
                        librarySupportingText = library.supportingText) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add to Favorites")
        composeRule.onNodeWithText("Library: Checking library availability…").assertIsDisplayed()
        composeRule.runOnIdle { loading = false; status = MediaLifecycleStatus(1, MediaType.SERIES, MediaLifecycleState.FAILED, requestId = 7) }
        composeRule.onNodeWithText("Add to Favorites").assertIsFocused()
        composeRule.onNodeWithText("Add to Favorites").performKeyInput { pressKey(Key.DirectionDown) }
        waitForFocus("Retry library request")
        composeRule.onNodeWithText("Retry library request").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("More")
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun externalLibraryUpdateRemovingFocusedActionMovesFocusToClose() {
        var actions by mutableStateOf(listOf(TvDetailsMenuAction("library_retry", "Retry library request") {}))
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item { TvDetailsMoreActions("details:library-removal", "Test movie", actions, state, onContentFocused = {}) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Retry library request")
        composeRule.runOnIdle { actions = emptyList() }
        waitForFocus("Close")
        composeRule.onNodeWithText("Close").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("More")
    }

    @Test
    fun compactBadgeUpdatesWithoutTakingRemoteFocusAndRequestIsOnlyInMore() {
        var status by mutableStateOf(MediaLifecycleStatus(1, MediaType.MOVIE, MediaLifecycleState.NOT_REQUESTED))
        composeRule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val library = tvDetailsLibraryMenu(status, false, null, false, 1, {}, {}, {}, {}, {})
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    item { TvDetailsMoreActions("details:badge", "Test movie", library.actions, list, {},
                        libraryState = DetailUiState(mediaLifecycleStatus = status)) }
                }
            }
        }
        composeRule.onNodeWithTag("tv_detail_library_badge").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Library: Not in library").assertIsDisplayed()
        composeRule.onNodeWithText("Add to library").assertDoesNotExist()
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.runOnIdle { status = status.copy(state = MediaLifecycleState.AVAILABLE) }
        composeRule.onNodeWithText("More").assertIsFocused()
        composeRule.onNodeWithContentDescription("Library: In Library").assertIsDisplayed()
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("More").assertIsFocused()
        composeRule.runOnIdle { status = status.copy(state = MediaLifecycleState.NOT_REQUESTED) }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add to library")
        composeRule.onNodeWithText("Add to library").performKeyInput { pressKey(Key.Back) }
        waitForFocus("More")
    }

    @Test
    fun unknownAvailabilityChecksBeforeRequestAndSurvivesMenuDismissal() {
        var status by mutableStateOf(MediaLifecycleStatus(1, MediaType.SERIES, MediaLifecycleState.UNKNOWN))
        var loading by mutableStateOf(false)
        var checks = 0
        val requests = mutableListOf<Int?>()
        composeRule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val library = tvDetailsLibraryMenu(status, loading, null, true, 2, {},
                    { requests.add(it) }, {}, {}, { checks++; loading = true })
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    item { TvDetailsMoreActions("details:preflight", "Test series", library.actions, list, {}) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add season 2 to library")
        composeRule.onNodeWithText("Add season 2 to library").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("More")
        composeRule.runOnIdle {
            assertEquals(1, checks)
            assertEquals(emptyList<Int?>(), requests)
            status = status.copy(state = MediaLifecycleState.NOT_REQUESTED)
            loading = false
        }
        composeRule.waitUntil { requests == listOf(2) }
        composeRule.onNodeWithText("More").assertIsFocused()
    }

    @Test
    fun preflightFindingExistingCopyDoesNotRequestDuplicate() {
        var status by mutableStateOf(MediaLifecycleStatus(1, MediaType.MOVIE, MediaLifecycleState.UNKNOWN))
        var loading by mutableStateOf(false)
        var requests = 0
        composeRule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val library = tvDetailsLibraryMenu(status, loading, null, false, 1, {},
                    { requests++ }, {}, {}, { loading = true })
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    item { TvDetailsMoreActions("details:existing", "Test movie", library.actions, list, {},
                        libraryState = DetailUiState(mediaLifecycleStatus = status, isLoadingMediaLifecycle = loading)) }
                }
            }
        }
        composeRule.onNodeWithText("More").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Add to library")
        composeRule.onNodeWithText("Add to library").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("More")
        composeRule.runOnIdle { status = status.copy(state = MediaLifecycleState.AVAILABLE); loading = false }
        composeRule.onNodeWithContentDescription("Library: In Library").assertIsDisplayed()
        composeRule.onNodeWithText("More").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForFocus("Close")
        composeRule.onNodeWithText("Add to library").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, requests) }
    }
}
