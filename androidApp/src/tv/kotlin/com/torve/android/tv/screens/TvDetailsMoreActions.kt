package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.remote.TorveRemotePopup as Popup
import androidx.compose.ui.window.PopupProperties
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.detail.DetailUiState

internal data class TvDetailsMenuAction(val id: String, val label: String, val onClick: () -> Unit)

/** Own the modal origin so both Back and action selection restore the More button. */
@Composable
internal fun TvDetailsMoreActions(
    screenId: String,
    title: String,
    actions: List<TvDetailsMenuAction>,
    listState: LazyListState,
    onContentFocused: (FocusRequester) -> Unit,
    libraryStatusText: String? = null,
    librarySupportingText: String? = null,
    libraryState: DetailUiState? = null,
) {
    val controller = rememberTvModalFocusRestoreController("$screenId:more")
    val target = remember(screenId) {
        TvFocusTargetId(screenId, "hero_actions", "more", rowIndex = 0, itemIndex = 3, targetType = "button")
    }
    val requester = rememberRegisteredTvFocusRequester(controller, target)
    var open by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    val dismiss = {
        open = false
        controller.requestRestore(reason = "more_dismiss")
        Unit
    }
    LaunchedEffect(open, controller.pendingRestore?.restoreToken, windowInfo.isWindowFocused) {
        if (!open && windowInfo.isWindowFocused) {
            controller.restorePendingFocus(screenId, outerListState = listState)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvActionButton(
            text = com.torve.android.tv.components.tvSettingsLabel("More"),
            modifier = Modifier.focusRequester(requester),
            onFocused = {
                controller.markFocused(target)
                onContentFocused(requester)
            },
            onClick = {
                controller.captureOrigin(target, outerListState = listState, reason = "more_open")
                open = true
            },
        )
        libraryState?.let { TvDetailsLibraryBadge(it) }
    }
    if (open) {
        TvDetailsMoreDialog(title, actions, libraryStatusText, librarySupportingText, onDismiss = dismiss)
    }
}

@Composable
private fun TvDetailsMoreDialog(
    title: String,
    actions: List<TvDetailsMenuAction>,
    libraryStatusText: String?,
    librarySupportingText: String?,
    onDismiss: () -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val closeRequester = remember { FocusRequester() }
    var focusedActionId by remember { mutableStateOf<String?>(null) }
    val firstRequester = actions.firstOrNull()?.let { requesters.getOrPut(it.id) { FocusRequester() } }
        ?: closeRequester
    BackHandler(onBack = onDismiss)
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
    ) {
        val windowInfo = LocalWindowInfo.current
        var contentHasFocus by remember { mutableStateOf(false) }
        var selectKeyStartedHere by remember { mutableStateOf(false) }
        LaunchedEffect(windowInfo.isWindowFocused, firstRequester) {
            if (!windowInfo.isWindowFocused) return@LaunchedEffect
            repeat(24) {
                withFrameNanos { }
                runCatching { firstRequester.requestFocus() }
                withFrameNanos { }
                if (contentHasFocus) return@LaunchedEffect
            }
        }
        LaunchedEffect(actions.map { it.id }, focusedActionId, windowInfo.isWindowFocused) {
            val focusedId = focusedActionId ?: return@LaunchedEffect
            if (windowInfo.isWindowFocused && focusedId != "close" && actions.none { it.id == focusedId }) {
                withFrameNanos { }
                runCatching { closeRequester.requestFocus() }
            }
        }
        Box(Modifier.fillMaxSize().background(Obsidian.copy(alpha = 0.88f))
            .onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup()
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            selectKeyStartedHere = true
                            false
                        } else {
                            val openingRelease = !selectKeyStartedHere
                            selectKeyStartedHere = false
                            openingRelease
                        }
                    }
                    Key.Back, Key.Escape -> {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        true
                    }
                    else -> false
                }
            }.padding(24.dp),
            contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    .background(Charcoal, RoundedCornerShape(16.dp))
                    .border(1.dp, Steel, RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(localizedUiText("More actions"), color = Snow, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text(title, color = Silver, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                if (!libraryStatusText.isNullOrBlank()) {
                    Text(localizedUiText("Library: $libraryStatusText"), color = Silver, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2)
                    librarySupportingText?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Silver, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { index, action ->
                        key(action.id) {
                            val requester = requesters.getOrPut(action.id) { FocusRequester() }
                            TvActionButton(
                                text = action.label,
                                modifier = Modifier.fillMaxWidth().focusRequester(requester).focusProperties {
                                    up = actions.getOrNull(index - 1)?.let { requesters.getOrPut(it.id) { FocusRequester() } }
                                        ?: requester
                                    down = actions.getOrNull(index + 1)?.let { requesters.getOrPut(it.id) { FocusRequester() } }
                                        ?: closeRequester
                                    left = requester
                                    right = requester
                                },
                                onFocused = { focusedActionId = action.id },
                                onClick = { onDismiss(); action.onClick() },
                            )
                        }
                    }
                    TvActionButton(com.torve.android.tv.components.tvSettingsLabel("Close"), modifier = Modifier.fillMaxWidth().focusRequester(closeRequester)
                        .focusProperties {
                            up = actions.lastOrNull()?.let { requesters.getOrPut(it.id) { FocusRequester() } } ?: closeRequester
                            down = closeRequester
                            left = closeRequester
                            right = closeRequester
                        }, onFocused = { focusedActionId = "close" }, onClick = onDismiss)
                }
            }
        }
    }
}
