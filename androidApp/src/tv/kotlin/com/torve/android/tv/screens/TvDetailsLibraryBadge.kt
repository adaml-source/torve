package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
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
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Ruby
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.presentation.detail.DetailUiState

/** Compact status beside More, without adding a D-pad stop. Unknown is never shown as missing. */
@Composable
internal fun TvDetailsLibraryBadge(state: DetailUiState, modifier: Modifier = Modifier) {
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
        else -> Silver
    }
    val label = stringResource(when {
        partial -> R.string.detail_library_request_partial
        available -> R.string.detail_in_library
        missing -> R.string.detail_library_not_in_library
        pending -> R.string.detail_library_request_processing
        else -> R.string.detail_library_unknown
    })
    val description = "${stringResource(R.string.nav_library)}: $label"
    Box(modifier = modifier.size(32.dp).testTag("tv_detail_library_badge")
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp)) {
            Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, Modifier.size(24.dp), tint = Silver)
            Icon(mark, null, Modifier.align(Alignment.BottomEnd).size(14.dp)
                .background(Obsidian, CircleShape), tint = tint)
        }
    }
}
