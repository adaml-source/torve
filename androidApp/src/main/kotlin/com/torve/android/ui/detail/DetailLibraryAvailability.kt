package com.torve.android.ui.detail
import com.torve.android.ui.components.MobileGlassIconButton as IconButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Torve
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Ruby
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.detail.MediaLifecycleOperation

/** Small, accessible status affordance beside More; unknown is never shown as missing. */
@Composable
internal fun DetailLibraryBadge(state: DetailUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = state.mediaLifecycleStatus?.state
    if (!state.isInLibrary && (status == null || status == MediaLifecycleState.UNCONFIGURED) &&
        !state.isLoadingMediaLifecycle && state.mediaLifecycleError == null) return
    val available = state.isInLibrary || status == MediaLifecycleState.AVAILABLE
    val partial = status == MediaLifecycleState.PARTIALLY_AVAILABLE
    val missing = !available && !state.isLoadingMediaLifecycle && state.mediaLifecycleError == null &&
        status == MediaLifecycleState.NOT_REQUESTED
    val pending = partial || state.isLoadingMediaLifecycle || state.mediaLifecycleStatus?.isInProgress == true
    val mark = when {
        partial -> Icons.Rounded.Schedule
        available -> Icons.Rounded.Check
        missing -> Icons.Rounded.Close
        pending -> Icons.Rounded.Schedule
        else -> Icons.AutoMirrored.Rounded.HelpOutline
    }
    val tint = when {
        partial -> Amber
        available -> Emerald
        missing -> Ruby
        pending -> Amber
        else -> Torve.colors.textTertiary
    }
    val label = stringResource(when {
        partial -> R.string.detail_library_request_partial
        available -> R.string.detail_in_library
        missing -> R.string.detail_library_not_in_library
        pending -> R.string.detail_library_request_processing
        else -> R.string.detail_library_unknown
    })
    val description = "${stringResource(R.string.nav_library)}: $label"
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).testTag("detail_library_badge")
        .semantics { contentDescription = description }) {
        Box(Modifier.size(28.dp)) {
            Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, Modifier.size(24.dp), tint = Torve.colors.textSecondary)
            Icon(mark, null, Modifier.align(Alignment.BottomEnd).size(14.dp)
                .background(MaterialTheme.colorScheme.background, CircleShape), tint = tint)
        }
    }
}

/** Own confirmation/preflight outside More so closing the menu cannot discard an active request check. */
@Composable
internal fun DetailLibraryActions(
    state: DetailUiState,
    isSeries: Boolean,
    onRequest: (Int?) -> Unit,
    onRetry: () -> Unit,
    onDeleteRequest: () -> Unit,
    onRefresh: () -> Unit,
    content: @Composable (List<DetailSecondaryActionSpec>) -> Unit,
) {
    val status = state.mediaLifecycleStatus
    val error = state.mediaLifecycleError
    val message = state.mediaLifecycleMessage
    if (status?.state == MediaLifecycleState.UNCONFIGURED ||
        (status == null && state.mediaLifecycleError == null && !state.isLoadingMediaLifecycle)
    ) {
        content(emptyList())
        return
    }

    val checking = state.mediaLifecycleOperation == MediaLifecycleOperation.CHECK_AVAILABILITY
    val lookupFailed = checking && state.mediaLifecycleError != null
    val alreadyInLibrary = state.isInLibrary && !isSeries
    val requestable = !state.isLoadingMediaLifecycle && !lookupFailed && !alreadyInLibrary && status?.canRequest == true
    val canCheckBeforeRequest = !state.isLoadingMediaLifecycle && !lookupFailed && !alreadyInLibrary &&
        status?.state == MediaLifecycleState.UNKNOWN
    var showHelp by remember(state.mediaItem?.id, state.mediaItem?.type) { mutableStateOf(false) }
    var requestConfirmation by remember(state.mediaItem?.id, state.mediaItem?.type) { mutableStateOf(false) }
    var awaitingRequestCheck by remember(state.mediaItem?.id, state.selectedSeason) { mutableStateOf(false) }
    // Keep the confirmed request tied to the season shown when the user chose it.
    var requestSeason by remember(state.mediaItem?.id, state.mediaItem?.type) { mutableStateOf<Int?>(null) }

    LaunchedEffect(awaitingRequestCheck, state.isLoadingMediaLifecycle, status, error) {
        if (awaitingRequestCheck && !state.isLoadingMediaLifecycle &&
            (status?.state != MediaLifecycleState.UNKNOWN || error != null)) {
            awaitingRequestCheck = false
            if (requestable) requestConfirmation = true
        }
    }

    val statusText = when {
        state.isLoadingMediaLifecycle -> stringResource(when (state.mediaLifecycleOperation) {
            MediaLifecycleOperation.CHECK_AVAILABILITY -> R.string.detail_library_checking
            MediaLifecycleOperation.REQUEST -> R.string.detail_library_submitting
            MediaLifecycleOperation.RETRY -> R.string.detail_library_restarting
            MediaLifecycleOperation.REMOVE -> R.string.detail_library_removing
        })
        lookupFailed -> stringResource(R.string.detail_library_check_failed)
        error != null -> error
        message != null -> message
        alreadyInLibrary -> stringResource(R.string.detail_library_request_available)
        else -> stringResource(when (status?.state) {
            MediaLifecycleState.NOT_REQUESTED -> R.string.detail_library_not_in_library
            MediaLifecycleState.PENDING_APPROVAL -> R.string.detail_library_request_pending
            MediaLifecycleState.APPROVED -> R.string.detail_library_request_approved
            MediaLifecycleState.PROCESSING -> R.string.detail_library_request_processing
            MediaLifecycleState.PARTIALLY_AVAILABLE -> R.string.detail_library_request_partial
            MediaLifecycleState.AVAILABLE -> R.string.detail_library_request_available
            MediaLifecycleState.DECLINED -> R.string.detail_library_declined
            MediaLifecycleState.FAILED -> R.string.detail_library_failed
            MediaLifecycleState.DELETED -> R.string.detail_library_removed
            else -> R.string.detail_library_unknown
        })
    }
    val menuBackground = Torve.colors.cardBackground
    val menuForeground = Torve.colors.textPrimary
    val entries = buildList {
        fun entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String,
                  enabled: Boolean = true, action: () -> Unit) = DetailSecondaryActionSpec(
            label, icon, menuBackground, menuForeground,
            enabled = enabled, testTag = tag, onClick = action)
        when {
            state.isLoadingMediaLifecycle -> add(entry(statusText, Icons.Rounded.Schedule, "detail_library_status", false) {})
            lookupFailed -> {
                add(entry(statusText, Icons.AutoMirrored.Rounded.HelpOutline, "detail_library_status", false) {})
                add(entry(stringResource(R.string.detail_library_check_again), Icons.Rounded.Refresh,
                    "detail_library_check_again", action = onRefresh))
            }
            status?.canRetry == true -> add(entry(stringResource(R.string.detail_library_request_retry),
                Icons.Rounded.Refresh, "detail_library_retry_request", action = onRetry))
            requestable || canCheckBeforeRequest -> add(entry(
                if (isSeries) stringResource(R.string.detail_add_season_to_library, state.selectedSeason)
                else stringResource(R.string.detail_add_to_library), Icons.Rounded.Add, "detail_library_request") {
                requestSeason = state.selectedSeason.takeIf { isSeries }
                if (canCheckBeforeRequest) {
                    awaitingRequestCheck = true
                    onRefresh()
                } else requestConfirmation = true
            })
            else -> {
                add(entry(statusText, if (alreadyInLibrary || status?.state == MediaLifecycleState.AVAILABLE)
                    Icons.Rounded.Check else Icons.Rounded.Schedule, "detail_library_status", false) {})
                if (status?.isInProgress == true || error != null) add(entry(
                    stringResource(R.string.detail_library_check_again), Icons.Rounded.Refresh,
                    "detail_library_check_again", action = onRefresh))
            }
        }
        if (!state.isLoadingMediaLifecycle && !lookupFailed && status?.canDeleteRequest == true) {
            add(entry(stringResource(if (status.isInProgress) R.string.detail_library_cancel_request
                else R.string.detail_library_remove_request), Icons.Rounded.Close,
                "detail_library_remove_request", action = onDeleteRequest))
        }
        add(entry(stringResource(R.string.detail_library_help), Icons.AutoMirrored.Rounded.HelpOutline,
            "detail_library_help") { showHelp = true })
    }
    content(entries)
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.detail_library_help)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.detail_library_request_explanation))
                    if (lookupFailed) Text(stringResource(R.string.detail_library_check_no_request))
                    if (status?.canDeleteRequest == true) Text(stringResource(R.string.detail_library_keep_files))
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    if (requestConfirmation) {
        AlertDialog(
            onDismissRequest = { requestConfirmation = false },
            title = { Text(if (isSeries) stringResource(R.string.detail_library_season_request_confirm, requestSeason ?: state.selectedSeason)
                else stringResource(R.string.detail_library_request_confirm)) },
            text = { Text(stringResource(R.string.detail_library_request_explanation)) },
            confirmButton = {
                TextButton(
                    enabled = requestable,
                    onClick = {
                        requestConfirmation = false
                        onRequest(requestSeason)
                    },
                    modifier = Modifier.testTag("detail_library_confirm_request"),
                ) { Text(stringResource(R.string.detail_library_request_submit)) }
            },
            dismissButton = {
                TextButton(onClick = { requestConfirmation = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
