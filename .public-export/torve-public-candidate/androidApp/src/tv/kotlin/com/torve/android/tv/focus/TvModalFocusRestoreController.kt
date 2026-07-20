package com.torve.android.tv.focus

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlin.math.abs

internal data class TvFocusTargetId(
    val screenId: String,
    val rowKey: String,
    val itemKey: String,
    val rowIndex: Int = -1,
    val itemIndex: Int = -1,
    val targetType: String = "item",
)

internal data class TvFocusListSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

internal data class TvFocusOrigin(
    val screenId: String,
    val rowKey: String,
    val itemKey: String,
    val rowIndex: Int,
    val itemIndex: Int,
    val focusTargetType: String,
    val outerListSnapshot: TvFocusListSnapshot?,
    val innerListSnapshot: TvFocusListSnapshot?,
    val requestedAtMillis: Long,
    val restoreToken: Long,
    val reason: String,
)

internal data class TvScreenFocusHandle(
    val captureFocusedOrigin: () -> TvFocusOrigin?,
    val requestRestore: (TvFocusOrigin, String) -> Unit,
)

internal class TvModalFocusRestoreController {
    private val logTag = "TvSettingsFocus"
    private val requesterByTarget = mutableMapOf<TvFocusTargetId, FocusRequester>()
    private val activeTargets = mutableStateMapOf<TvFocusTargetId, Int>()
    private var nextRestoreToken by mutableLongStateOf(0L)

    var focusedTarget: TvFocusTargetId? by mutableStateOf(null)
        private set

    var pendingRestore: TvFocusOrigin? by mutableStateOf(null)
        private set

    fun requesterFor(target: TvFocusTargetId): FocusRequester {
        return requesterByTarget.getOrPut(target) { FocusRequester() }
    }

    fun registerTarget(
        target: TvFocusTargetId,
        requester: FocusRequester = requesterFor(target),
    ): FocusRequester {
        requesterByTarget[target] = requester
        activeTargets[target] = (activeTargets[target] ?: 0) + 1
        return requester
    }

    fun unregisterTarget(target: TvFocusTargetId) {
        val registrations = activeTargets[target] ?: return
        if (registrations <= 1) {
            activeTargets.remove(target)
            if (focusedTarget == target) {
                focusedTarget = null
            }
        } else {
            activeTargets[target] = registrations - 1
        }
    }

    fun markFocused(target: TvFocusTargetId) {
        focusedTarget = target
        debugLog(
            "focus_gained screen=${target.screenId} row=${target.rowKey} rowIndex=${target.rowIndex} " +
                "item=${target.itemKey} itemIndex=${target.itemIndex} type=${target.targetType} " +
                "pendingToken=${pendingRestore?.restoreToken ?: -1L}",
        )
    }

    fun captureOrigin(
        target: TvFocusTargetId,
        outerListState: LazyListState? = null,
        innerListState: LazyListState? = null,
        reason: String = "launch",
        requestedAtMillis: Long = System.currentTimeMillis(),
    ): TvFocusOrigin {
        val origin = TvFocusOrigin(
            screenId = target.screenId,
            rowKey = target.rowKey,
            itemKey = target.itemKey,
            rowIndex = target.rowIndex,
            itemIndex = target.itemIndex,
            focusTargetType = target.targetType,
            outerListSnapshot = outerListState?.toFocusSnapshot(),
            innerListSnapshot = innerListState?.toFocusSnapshot(),
            requestedAtMillis = requestedAtMillis,
            restoreToken = nextToken(),
            reason = reason,
        )
        pendingRestore = origin
        debugLog(
            "origin_captured screen=${origin.screenId} row=${origin.rowKey} rowIndex=${origin.rowIndex} " +
                "item=${origin.itemKey} itemIndex=${origin.itemIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )
        return origin
    }

    fun captureFocusedOrigin(
        screenId: String,
        outerListState: LazyListState? = null,
        innerListStateForRowKey: (String) -> LazyListState? = { null },
        requestedAtMillis: Long = System.currentTimeMillis(),
    ): TvFocusOrigin? {
        val target = focusedTarget?.takeIf { it.screenId == screenId } ?: return null
        return captureOrigin(
            target = target,
            outerListState = outerListState,
            innerListState = innerListStateForRowKey(target.rowKey),
            requestedAtMillis = requestedAtMillis,
        )
    }

    fun requestRestore(
        origin: TvFocusOrigin? = pendingRestore,
        reason: String? = null,
    ): TvFocusOrigin? {
        val source = origin ?: return null
        val updated = source.copy(
            requestedAtMillis = System.currentTimeMillis(),
            restoreToken = nextToken(),
            reason = reason ?: source.reason,
        )
        pendingRestore = updated
        debugLog(
            "restore_requested screen=${updated.screenId} row=${updated.rowKey} rowIndex=${updated.rowIndex} " +
                "item=${updated.itemKey} itemIndex=${updated.itemIndex} type=${updated.focusTargetType} " +
                "token=${updated.restoreToken} reason=${updated.reason}",
        )
        return updated
    }

    fun clearPendingRestore() {
        pendingRestore = null
    }

    fun resolveCandidates(origin: TvFocusOrigin): List<TvFocusTargetId> {
        val sameScreenTargets = activeTargets.keys
            .filter { it.screenId == origin.screenId }
            .distinct()
        if (sameScreenTargets.isEmpty()) return emptyList()

        val exactTarget = sameScreenTargets.firstOrNull {
            it.rowKey == origin.rowKey && it.itemKey == origin.itemKey
        }

        val sameRowTargets = sameScreenTargets
            .filter { it.rowKey == origin.rowKey }
            .sortedWith(
                compareBy<TvFocusTargetId> { abs(it.itemIndex - origin.itemIndex) }
                    .thenBy { abs(it.rowIndex - origin.rowIndex) }
                    .thenBy { it.itemIndex }
                    .thenBy { it.itemKey },
            )

        val sameRowIndexTargets = sameScreenTargets
            .filter { it.rowIndex == origin.rowIndex }
            .sortedWith(
                compareBy<TvFocusTargetId> { abs(it.itemIndex - origin.itemIndex) }
                    .thenBy { it.itemIndex }
                    .thenBy { it.itemKey },
            )

        val nearestRowTargets = sameScreenTargets
            .sortedWith(
                compareBy<TvFocusTargetId> { abs(it.rowIndex - origin.rowIndex) }
                    .thenBy { abs(it.itemIndex - origin.itemIndex) }
                    .thenBy { it.rowIndex }
                    .thenBy { it.itemIndex }
                    .thenBy { it.itemKey },
            )

        return buildList {
            exactTarget?.let { add(it) }
            sameRowTargets.forEach { candidate ->
                if (candidate !in this) add(candidate)
            }
            sameRowIndexTargets.forEach { candidate ->
                if (candidate !in this) add(candidate)
            }
            nearestRowTargets.forEach { candidate ->
                if (candidate !in this) add(candidate)
            }
        }
    }

    suspend fun restorePendingFocus(
        screenId: String,
        outerListState: LazyListState? = null,
        innerListStateForRowKey: (String) -> LazyListState? = { null },
        isScreenActive: () -> Boolean = { true },
        maxAttempts: Int = 8,
    ): Boolean {
        val origin = pendingRestore?.takeIf { it.screenId == screenId } ?: return false
        if (!isScreenActive()) return false
        debugLog(
            "restore_begin screen=${origin.screenId} row=${origin.rowKey} rowIndex=${origin.rowIndex} " +
                "item=${origin.itemKey} itemIndex=${origin.itemIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )

        origin.outerListSnapshot?.let { snapshot ->
            outerListState?.scrollToItem(snapshot.firstVisibleItemIndex, snapshot.firstVisibleItemScrollOffset)
        }
        origin.innerListSnapshot?.let { snapshot ->
            innerListStateForRowKey(origin.rowKey)?.scrollToItem(
                snapshot.firstVisibleItemIndex,
                snapshot.firstVisibleItemScrollOffset,
            )
        }

        repeat(maxAttempts) {
            withFrameNanos { }
            if (!isScreenActive()) return false

            val candidates = resolveCandidates(origin)
            for (candidate in candidates) {
                debugLog(
                    "restore_candidate screen=${candidate.screenId} row=${candidate.rowKey} rowIndex=${candidate.rowIndex} " +
                        "item=${candidate.itemKey} itemIndex=${candidate.itemIndex} type=${candidate.targetType} " +
                        "token=${origin.restoreToken} reason=${origin.reason}",
                )
                val requester = requesterByTarget[candidate] ?: continue
                runCatching {
                    requester.requestFocus()
                }.getOrDefault(Unit)
                withFrameNanos { }
                val focused = focusedTarget == candidate
                if (focused) {
                    debugLog(
                        "restore_success screen=${candidate.screenId} row=${candidate.rowKey} rowIndex=${candidate.rowIndex} " +
                            "item=${candidate.itemKey} itemIndex=${candidate.itemIndex} type=${candidate.targetType} " +
                            "token=${origin.restoreToken} reason=${origin.reason}",
                    )
                    if (pendingRestore?.restoreToken == origin.restoreToken) {
                        pendingRestore = null
                    }
                    return true
                }
            }
        }
        debugLog(
            "restore_failed screen=${origin.screenId} row=${origin.rowKey} rowIndex=${origin.rowIndex} " +
                "item=${origin.itemKey} itemIndex=${origin.itemIndex} type=${origin.focusTargetType} " +
                "token=${origin.restoreToken} reason=${origin.reason}",
        )
        return false
    }

    private fun debugLog(message: String) {
        runCatching { Log.d(logTag, message) }
    }

    private fun nextToken(): Long {
        nextRestoreToken += 1L
        return nextRestoreToken
    }
}

@Composable
internal fun rememberTvModalFocusRestoreController(
    key: String,
): TvModalFocusRestoreController {
    return remember(key) { TvModalFocusRestoreController() }
}

@Composable
internal fun rememberRegisteredTvFocusRequester(
    controller: TvModalFocusRestoreController,
    target: TvFocusTargetId,
    externalRequester: FocusRequester? = null,
): FocusRequester {
    val requester = externalRequester ?: remember(target) { controller.requesterFor(target) }
    DisposableEffect(controller, target, requester) {
        controller.registerTarget(target, requester)
        onDispose {
            controller.unregisterTarget(target)
        }
    }
    return requester
}

private fun LazyListState.toFocusSnapshot(): TvFocusListSnapshot {
    return TvFocusListSnapshot(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    )
}
