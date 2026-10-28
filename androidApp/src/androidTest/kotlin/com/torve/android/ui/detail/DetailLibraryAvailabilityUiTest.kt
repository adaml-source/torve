package com.torve.android.ui.detail

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTheme
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.domain.model.MediaType
import com.torve.presentation.detail.DetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DetailLibraryAvailabilityUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun status(value: MediaLifecycleState) = MediaLifecycleStatus(42, MediaType.SERIES, value)
    private fun forbiddenAction(): Nothing = error("No library mutation expected")
    private fun showScreen(
        state: State<DetailUiState>, isSeries: Boolean = false,
        onRequest: (Int?) -> Unit = { forbiddenAction() }, onRefresh: () -> Unit = { forbiddenAction() },
    ) {
        rule.setContent { TorveTheme {
            DetailLibraryActions(state.value, isSeries, onRequest,
                { forbiddenAction() }, { forbiddenAction() }, onRefresh) { actions ->
                DetailSecondaryActions("Example title", actions, null, libraryStatus = state.value)
            }
        } }
    }
    private fun openMore() = rule.onNodeWithTag("detail_more").performClick()

    @Test fun libraryControlsLiveOnlyInMoreAndIconHasNoVisibleStatusText() {
        showScreen(mutableStateOf(DetailUiState(mediaLifecycleStatus = status(MediaLifecycleState.NOT_REQUESTED))))
        rule.onNodeWithTag("detail_library_badge").assertIsDisplayed()
        rule.onNodeWithContentDescription("Library: Not in library").assertIsDisplayed()
        rule.onNodeWithText("No library copy reported by Seerr").assertDoesNotExist()
        rule.onNodeWithText("Not in library").assertDoesNotExist()
        rule.onNodeWithTag("detail_library_availability").assertDoesNotExist()
        rule.onNodeWithTag("detail_library_request").assertDoesNotExist()
        openMore()
        rule.onNodeWithTag("detail_library_request").assertIsDisplayed()
    }

    @Test fun unverifiedConfiguredRouteSurvivesMoreClosingBeforeCheckCompletes() {
        val state = mutableStateOf(DetailUiState(mediaLifecycleStatus = status(MediaLifecycleState.UNKNOWN)))
        var checks = 0
        var requests = 0
        showScreen(state, onRequest = { requests++ }, onRefresh = {
            checks++
            state.value = state.value.copy(isLoadingMediaLifecycle = true)
        })
        openMore()
        rule.onNodeWithTag("detail_library_request").performClick()
        rule.onNodeWithTag("detail_more_close").assertDoesNotExist()
        rule.onNodeWithTag("detail_library_confirm_request").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(1, checks)
            assertEquals(0, requests)
            state.value = state.value.copy(isLoadingMediaLifecycle = false,
                mediaLifecycleStatus = status(MediaLifecycleState.NOT_REQUESTED))
        }
        rule.onNodeWithTag("detail_library_confirm_request").performClick()
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun preflightFindingExistingCopyNeverRequestsAnotherCopy() {
        val state = mutableStateOf(DetailUiState(mediaLifecycleStatus = status(MediaLifecycleState.UNKNOWN)))
        showScreen(state, onRefresh = {
            state.value = state.value.copy(mediaLifecycleStatus = status(MediaLifecycleState.AVAILABLE))
        })
        openMore()
        rule.onNodeWithTag("detail_library_request").performClick()
        rule.onNodeWithTag("detail_library_confirm_request").assertDoesNotExist()
        openMore()
        rule.onNodeWithTag("detail_library_request").assertDoesNotExist()
        rule.onNodeWithText("Available in your library").assertIsDisplayed()
    }

    @Test fun failedLookupOffersReadOnlyRetryInMore() {
        var reads = 0
        showScreen(mutableStateOf(DetailUiState(mediaLifecycleStatus = status(MediaLifecycleState.NOT_REQUESTED),
            mediaLifecycleError = "Connection failed")), onRefresh = { reads++ })
        rule.onNodeWithContentDescription("Library: Library availability unknown").assertIsDisplayed()
        openMore()
        rule.onNodeWithTag("detail_library_request").assertDoesNotExist()
        rule.onNodeWithTag("detail_library_check_again").performClick()
        rule.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun seasonRequestOnlySendsAfterConfirmationAndKeepsItsOriginalSeason() {
        val state = mutableStateOf(DetailUiState(selectedSeason = 2,
            mediaLifecycleStatus = status(MediaLifecycleState.NOT_REQUESTED)))
        val requests = mutableListOf<Int?>()
        showScreen(state, isSeries = true, onRequest = { requests += it })
        openMore()
        rule.onNodeWithTag("detail_library_request").performClick()
        rule.runOnIdle {
            assertEquals(emptyList<Int?>(), requests)
            state.value = state.value.copy(selectedSeason = 3)
        }
        rule.onNodeWithText("Request season 2?").assertIsDisplayed()
        rule.onNodeWithTag("detail_library_confirm_request").performClick()
        rule.runOnIdle { assertEquals(listOf(2), requests) }
    }

    @Test fun cancellingTheConfirmationDoesNotSendAnyRequest() {
        showScreen(mutableStateOf(DetailUiState(mediaLifecycleStatus = status(MediaLifecycleState.NOT_REQUESTED))))
        openMore()
        rule.onNodeWithTag("detail_library_request").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("detail_library_confirm_request").assertDoesNotExist()
    }

    @Test fun knownJellyfinMovieDoesNotOfferDuplicateRequest() {
        showScreen(mutableStateOf(DetailUiState(isInLibrary = true,
            mediaLifecycleStatus = status(MediaLifecycleState.NOT_REQUESTED))))
        rule.onNodeWithContentDescription("Library: In Library").performClick()
        rule.onNodeWithTag("detail_library_request").assertDoesNotExist()
        rule.onNodeWithText("Available in your library").assertIsDisplayed()
    }

    @Test fun unconfiguredStackHasNoLibraryActionOrMissingCopyIndicator() {
        showScreen(mutableStateOf(DetailUiState(mediaLifecycleStatus = status(MediaLifecycleState.UNCONFIGURED))))
        rule.onNodeWithTag("detail_library_badge").assertDoesNotExist()
        openMore()
        rule.onNodeWithTag("detail_library_request").assertDoesNotExist()
    }
}
