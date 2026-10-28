package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.remote.TorveRemoteDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.torve.android.tv.screens.TvChannelCenterBringIntoViewSpec
import com.torve.android.ui.theme.*
import com.torve.android.tv.focus.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

private class WorkflowFocus(val controller: TvModalFocusRestoreController, val screen: String,
    val listState: LazyListState, val scope: CoroutineScope, val lastTarget: () -> Int,
    val rememberTarget: (Int) -> Unit) {
    var entry: FocusRequester? = null
    var pageActive = false
    var modalDepth = 0
    var focusedRequester: FocusRequester? = null
}
private val LocalWorkflowFocus = staticCompositionLocalOf<WorkflowFocus?> { null }
private val LocalWorkflowToolbar = staticCompositionLocalOf { false }

enum class TvWorkflowActionAppearance { STANDARD, COMPACT, MEDIA, ICON }

@Composable
fun TvWorkflowSurface(id: String, content: @Composable () -> Unit) {
    val controller = remember(id) { TvModalFocusRestoreController() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastTarget by rememberSaveable(id) { mutableIntStateOf(0) }
    val focus = remember(id) { WorkflowFocus(controller, id, listState, scope, { lastTarget }) { lastTarget = it } }
    CompositionLocalProvider(LocalWorkflowFocus provides focus, content = content)
}

@Composable
fun TvWorkflowAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, selected: Boolean = false, destructive: Boolean = false,
    requester: FocusRequester? = null, onFocused: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    appearance: TvWorkflowActionAppearance = TvWorkflowActionAppearance.STANDARD,
    content: (@Composable RowScope.() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val workflow = LocalWorkflowFocus.current
    val toolbar = LocalWorkflowToolbar.current
    val stableId = currentCompositeKeyHash
    val target = workflow?.let { TvFocusTargetId(it.screen, stableId.toString(), "action", targetType = if (toolbar) "toolbar" else "content") }
    val actionRequester = requester ?: remember(workflow, stableId) { workflow?.controller?.requesterFor(target!!) ?: FocusRequester() }
    if (workflow != null && target != null) DisposableEffect(workflow, target, actionRequester) {
        workflow.controller.registerTarget(target, actionRequester)
        onDispose {
            val removedFocused = workflow.controller.focusedTarget == target
            workflow.controller.unregisterTarget(target)
            if (removedFocused) workflow.scope.launch {
                withFrameNanos { }
                if (workflow.pageActive && workflow.modalDepth == 0 && workflow.controller.focusedTarget == null)
                    workflow.entry?.let { runCatching { it.requestFocus() } }
            }
        }
    }
    val compact = appearance != TvWorkflowActionAppearance.STANDARD
    val actionPadding = when (appearance) {
        TvWorkflowActionAppearance.STANDARD -> PaddingValues(horizontal = 18.dp, vertical = 13.dp)
        TvWorkflowActionAppearance.COMPACT -> PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        else -> PaddingValues(8.dp)
    }
    Row(modifier.focusRequester(actionRequester)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) {
            workflow?.focusedRequester = actionRequester
            if (target != null) { workflow?.controller?.markFocused(target); workflow?.rememberTarget?.invoke(stableId) }
            onFocused?.invoke()
        } else workflow?.let { owner -> if (owner.focusedRequester === actionRequester) owner.focusedRequester = null } }
        .tvGlassControl(focused, selected, cornerRadius = if (appearance == TvWorkflowActionAppearance.ICON) 24.dp else 18.dp)
        .clickable(onClick = onClick).padding(actionPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (content != null) content() else {
        leading?.invoke()
        Column(if (leading != null && !compact) Modifier.weight(1f) else Modifier) {
        Text(localizedUiText(label), color = if (destructive) MaterialTheme.colorScheme.error else if (focused || selected) AmberLight else Snow,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) Text(localizedUiText(subtitle), color = Silver, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvWorkflowPage(title: String, subtitle: String, onBack: () -> Unit,
    entry: FocusRequester, onContentFocused: (FocusRequester) -> Unit,
    toolbar: @Composable RowScope.() -> Unit = {}, compactHeader: Boolean = false,
    content: LazyListScope.() -> Unit) {
    val workflow = LocalWorkflowFocus.current
    if (workflow == null) {
        TvWorkflowSurface(title) { TvWorkflowPage(title, subtitle, onBack, entry, onContentFocused, toolbar, compactHeader, content) }
        return
    }
    val controller = workflow.controller
    val settings = LocalTvSettingsStyle.current
    val listState = workflow.listState
    DisposableEffect(workflow) { workflow.pageActive = true; onDispose { workflow.pageActive = false } }
    SideEffect { workflow.entry = entry }
    BackHandler {
        if (!settings && controller.focusedTarget?.targetType == "content") entry.requestFocus() else onBack()
    }
    LaunchedEffect(entry) {
        withFrameNanos { }
        if (workflow.lastTarget() != 0) {
            val target = TvFocusTargetId(workflow.screen, workflow.lastTarget().toString(), "action")
            controller.captureOrigin(target, listState, reason = "page_return")
            if (controller.restorePendingFocus(workflow.screen, listState, maxAttempts = 8)) return@LaunchedEffect
        }
        entry.requestFocus()
    }
    CompositionLocalProvider(LocalWorkflowFocus provides workflow) {
    Column(Modifier.fillMaxSize().then(if (settings) Modifier.focusRequester(entry).focusGroup() else Modifier)
        .background(if (settings) Color.Transparent else Charcoal).padding(horizontal = if (compactHeader) 32.dp else 44.dp,
        vertical = if (compactHeader) 24.dp else 28.dp)) {
        CompositionLocalProvider(LocalWorkflowToolbar provides true) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (compactHeader) 16.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!settings) TvWorkflowAction(stringResource(R.string.common_back_cd), onBack, modifier = Modifier.size(48.dp),
                requester = entry, onFocused = { onContentFocused(entry) },
                appearance = TvWorkflowActionAppearance.ICON,
                content = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back_cd), tint = Snow, modifier = Modifier.size(24.dp))
                } })
            Column(Modifier.weight(1f)) {
                Text(localizedUiText(title), color = Snow, style = if (compactHeader) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(localizedUiText(subtitle), color = Silver, style = if (compactHeader) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, maxLines = if (compactHeader) 1 else 2,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = if (compactHeader) 16.dp else 22.dp, bottom = if (compactHeader) 14.dp else 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = toolbar)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember { TvChannelCenterBringIntoViewSpec() }) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(if (compactHeader) 10.dp else 14.dp), content = content)
        }
    }
    }
}

@Composable
fun TvWorkflowDialog(title: String, onDismiss: () -> Unit, origin: FocusRequester?,
    width: androidx.compose.ui.unit.Dp = 660.dp, initialFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val currentOrigin by rememberUpdatedState(origin)
    val workflow = LocalWorkflowFocus.current
    val windowInfo = LocalWindowInfo.current
    val captured = remember { workflow?.controller?.captureFocusedOrigin(workflow.screen, workflow.listState) }
    DisposableEffect(Unit) {
        workflow?.let { it.modalDepth++ }
        onDispose {
        workflow?.let { it.modalDepth-- }
        workflow?.scope?.launch {
            val preferred = currentOrigin
            if (preferred != null) repeat(8) {
                snapshotFlow { windowInfo.isWindowFocused }.first { it }
                withFrameNanos { }
                if (!workflow.pageActive || workflow.modalDepth != 0) return@launch
                // Finish as soon as focus is accepted. Waiting another frame lets a
                // new remote press move focus, which a retry would then steal back.
                runCatching { preferred.requestFocus() }
                if (workflow.focusedRequester === preferred) return@launch
            }
            if (captured != null) {
                workflow.controller.requestRestore(captured, "dialog_return")
                if (!workflow.controller.restorePendingFocus(workflow.screen)) workflow.entry?.let { runCatching { it.requestFocus() } }
            }
        }
    } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalWorkflowFocus provides null) {
        val entry = remember { FocusRequester() }
        val dialogWindowInfo = LocalWindowInfo.current
        var selectKeyStartedHere by remember { mutableStateOf(false) }
        LaunchedEffect(dialogWindowInfo.isWindowFocused) {
            if (dialogWindowInfo.isWindowFocused) {
                withFrameNanos { }
                (initialFocusRequester ?: entry).requestFocus()
            }
        }
        Column(Modifier.width(width).heightIn(max = 470.dp).background(Charcoal, RoundedCornerShape(20.dp))
            .border(1.dp, Steel.copy(alpha = .4f), RoundedCornerShape(20.dp))
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Back, Key.Escape -> {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        true
                    }
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
                    else -> false
                }
            }.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(localizedUiText(title), Modifier.weight(1f), color = Snow, style = MaterialTheme.typography.headlineSmall)
                TvWorkflowAction(stringResource(R.string.common_cancel), onDismiss, requester = entry)
            }
            content()
        }
        }
    }
}
