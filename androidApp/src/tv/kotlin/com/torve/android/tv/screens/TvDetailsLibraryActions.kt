package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.presentation.detail.MediaLifecycleOperation

internal data class TvDetailsLibraryMenu(
    val actions: List<TvDetailsMenuAction>,
    val statusText: String?,
    val supportingText: String?,
)

/** Background library lookup is information, never a temporary remote-focus target. */
@Composable
internal fun tvDetailsLibraryMenu(
    status: MediaLifecycleStatus?,
    isLoading: Boolean,
    error: String?,
    isSeries: Boolean,
    selectedSeason: Int,
    onSetup: () -> Unit,
    onRequest: (Int?) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    operation: MediaLifecycleOperation = MediaLifecycleOperation.CHECK_AVAILABILITY,
    isInLibrary: Boolean = false,
    contentKey: String = "",
): TvDetailsLibraryMenu {
    val alreadyAvailable = isInLibrary && !isSeries
    val lookupFailed = operation == MediaLifecycleOperation.CHECK_AVAILABILITY && !error.isNullOrBlank()
    val requestable = !isLoading && !lookupFailed && !alreadyAvailable && status?.canRequest == true
    val needsCheck = !isLoading && !lookupFailed && !alreadyAvailable && status?.state == MediaLifecycleState.UNKNOWN
    var awaitingCheck by remember(contentKey, selectedSeason) { mutableStateOf(false) }
    var requestSeason by remember(contentKey) { mutableStateOf<Int?>(null) }
    // This lives outside the popup: closing More must not discard the user's request check.
    LaunchedEffect(awaitingCheck, isLoading, status, error) {
        if (awaitingCheck && !isLoading && (status?.state != MediaLifecycleState.UNKNOWN || error != null)) {
            awaitingCheck = false
            if (requestable) onRequest(requestSeason)
        }
    }
    val requestLabel = if (isSeries) stringResource(R.string.detail_add_season_to_library, selectedSeason)
        else stringResource(R.string.detail_add_to_library)
    val retryLabel = stringResource(R.string.detail_library_request_retry)
    val refreshLabel = stringResource(R.string.detail_library_refresh)
    val actions = buildList {
        when {
            status?.state == MediaLifecycleState.UNCONFIGURED ->
                add(TvDetailsMenuAction("library_setup", "Set up library downloads", onSetup))
            !isLoading && !lookupFailed && !alreadyAvailable && status?.canRetry == true ->
                add(TvDetailsMenuAction("library_retry", retryLabel, onRetry))
            requestable || needsCheck -> add(TvDetailsMenuAction("library_request", requestLabel) {
                requestSeason = selectedSeason.takeIf { isSeries }
                if (needsCheck) {
                    awaitingCheck = true
                    onRefresh()
                } else onRequest(requestSeason)
            })
        }
        if (!isLoading && status?.canDeleteRequest == true) {
            add(TvDetailsMenuAction("library_remove",
                if (status.isInProgress) "Cancel library request" else "Remove library request", onDelete))
        }
        if (!isLoading && !error.isNullOrBlank()) add(TvDetailsMenuAction("library_refresh", refreshLabel, onRefresh))
    }
    val statusText = when {
        isLoading -> stringResource(when (operation) {
            MediaLifecycleOperation.CHECK_AVAILABILITY -> R.string.detail_library_checking
            MediaLifecycleOperation.REQUEST -> R.string.detail_library_submitting
            MediaLifecycleOperation.RETRY -> R.string.detail_library_restarting
            MediaLifecycleOperation.REMOVE -> R.string.detail_library_removing
        })
        alreadyAvailable -> stringResource(R.string.detail_library_request_available)
        !error.isNullOrBlank() -> "Library request status unavailable"
        else -> when (status?.state) {
            MediaLifecycleState.UNCONFIGURED -> "Library downloads are not connected"
            MediaLifecycleState.NOT_REQUESTED -> "No library request"
            MediaLifecycleState.PENDING_APPROVAL -> stringResource(R.string.detail_library_request_pending)
            MediaLifecycleState.APPROVED -> stringResource(R.string.detail_library_request_approved)
            MediaLifecycleState.PROCESSING -> stringResource(R.string.detail_library_request_processing)
            MediaLifecycleState.PARTIALLY_AVAILABLE -> stringResource(R.string.detail_library_request_partial)
            MediaLifecycleState.AVAILABLE -> stringResource(R.string.detail_library_request_available)
            MediaLifecycleState.DECLINED -> "Library request declined"
            MediaLifecycleState.FAILED -> "Library request failed"
            MediaLifecycleState.DELETED -> "Library request removed"
            MediaLifecycleState.UNKNOWN -> "Library status unavailable"
            null -> null
        }
    }
    val supportingText = when {
        !error.isNullOrBlank() -> error
        status?.state == MediaLifecycleState.UNCONFIGURED -> "Connect Seerr to request a library copy."
        status?.canDeleteRequest == true -> "Removing the request keeps downloaded files."
        status?.canRequest == true || status?.canRetry == true ->
            "Requests a library copy while Watch now stays available."
        else -> null
    }
    return TvDetailsLibraryMenu(actions, statusText, supportingText)
}
