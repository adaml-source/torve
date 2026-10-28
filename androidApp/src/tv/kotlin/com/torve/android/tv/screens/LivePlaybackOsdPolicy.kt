package com.torve.android.tv.screens

internal enum class LivePlaybackOsdDirection {
    PREVIOUS,
    NEXT,
}

/** Pure focus/visibility rules shared by the live OSD and its regression tests. */
internal object LivePlaybackOsdPolicy {
    const val AUTO_HIDE_DELAY_MS: Long = 5_000L
    const val RAIL_SCROLL_ANIMATION_MS: Int = 160
    const val CONTEXT_OPTION_FOCUSED_SCALE: Float = 1f
    const val DEFAULT_CONTROL_ID: String = "play_pause"
    const val RAIL_TOP_PADDING_DP: Float = 10f
    const val RAIL_BOTTOM_PADDING_DP: Float = 12f
    const val TIMELINE_REGION_HEIGHT_DP: Float = 18f
    const val TIMELINE_CONTROL_GAP_DP: Float = 8f

    fun retainedControlId(
        previousControlId: String?,
        controlIds: List<String>,
    ): String {
        if (controlIds.isEmpty()) return DEFAULT_CONTROL_ID
        return previousControlId
            ?.takeIf(controlIds::contains)
            ?: DEFAULT_CONTROL_ID.takeIf(controlIds::contains)
            ?: controlIds.first()
    }

    fun nextControlId(
        controlIds: List<String>,
        currentControlId: String?,
        direction: LivePlaybackOsdDirection,
    ): String {
        if (controlIds.isEmpty()) return DEFAULT_CONTROL_ID
        val currentIndex = controlIds.indexOf(currentControlId).takeIf { it >= 0 } ?: 0
        val nextIndex = when (direction) {
            LivePlaybackOsdDirection.PREVIOUS -> (currentIndex - 1).coerceAtLeast(0)
            LivePlaybackOsdDirection.NEXT -> (currentIndex + 1).coerceAtMost(controlIds.lastIndex)
        }
        return controlIds[nextIndex]
    }

    fun shouldAutoHide(
        elapsedSinceInteractionMs: Long,
        contextualPanelOpen: Boolean,
    ): Boolean = !contextualPanelOpen && elapsedSinceInteractionMs >= AUTO_HIDE_DELAY_MS

    fun railHeightDp(viewportHeightDp: Float): Float =
        (viewportHeightDp * 0.15f).coerceIn(104f, 260f)

    fun controlWidthDp(viewportWidthDp: Float): Float = when {
        viewportWidthDp < 900f -> 82f
        viewportWidthDp < 1_300f -> 92f
        else -> 102f
    }

    /** Explicit allocations keep focus drawing in the controls off the timeline. */
    fun verticalRegions(
        viewportHeightDp: Float,
        timelineVisible: Boolean,
    ): LivePlaybackOsdVerticalRegions {
        val railHeight = railHeightDp(viewportHeightDp)
        val timelineTop = RAIL_TOP_PADDING_DP
        val timelineBottom = if (timelineVisible) {
            timelineTop + TIMELINE_REGION_HEIGHT_DP
        } else {
            timelineTop
        }
        val controlsTop = if (timelineVisible) {
            timelineBottom + TIMELINE_CONTROL_GAP_DP
        } else {
            RAIL_TOP_PADDING_DP
        }
        return LivePlaybackOsdVerticalRegions(
            timelineTopDp = timelineTop,
            timelineBottomDp = timelineBottom,
            controlsTopDp = controlsTop,
            controlsBottomDp = railHeight - RAIL_BOTTOM_PADDING_DP,
        )
    }

    /**
     * Returns the smallest scroll required to reveal an item. A fully visible item
     * deliberately returns zero so focus changes do not recenter the whole rail.
     */
    fun revealScrollDeltaPx(
        itemStartPx: Int,
        itemEndPx: Int,
        viewportStartPx: Int,
        viewportEndPx: Int,
    ): Int = when {
        itemStartPx < viewportStartPx -> itemStartPx - viewportStartPx
        itemEndPx > viewportEndPx -> itemEndPx - viewportEndPx
        else -> 0
    }
}

internal data class LivePlaybackOsdVerticalRegions(
    val timelineTopDp: Float,
    val timelineBottomDp: Float,
    val controlsTopDp: Float,
    val controlsBottomDp: Float,
) {
    val timelineControlGapDp: Float
        get() = controlsTopDp - timelineBottomDp

    val timelineIntersectsControls: Boolean
        get() = timelineBottomDp > controlsTopDp
}

internal data class LivePlaybackOsdState(
    val visible: Boolean = false,
    val focusedControlId: String? = null,
    val contextualPanelOwnerId: String? = null,
    val lastInteractionMs: Long = 0L,
) {
    fun show(controlIds: List<String>, nowMs: Long): LivePlaybackOsdState = copy(
        visible = true,
        focusedControlId = LivePlaybackOsdPolicy.retainedControlId(focusedControlId, controlIds),
        contextualPanelOwnerId = null,
        lastInteractionMs = nowMs,
    )

    fun move(
        controlIds: List<String>,
        direction: LivePlaybackOsdDirection,
        nowMs: Long,
    ): LivePlaybackOsdState = copy(
        focusedControlId = LivePlaybackOsdPolicy.nextControlId(controlIds, focusedControlId, direction),
        lastInteractionMs = nowMs,
    )

    fun openPanel(ownerControlId: String, nowMs: Long): LivePlaybackOsdState = copy(
        focusedControlId = ownerControlId,
        contextualPanelOwnerId = ownerControlId,
        lastInteractionMs = nowMs,
    )

    fun closePanel(controlIds: List<String>, nowMs: Long): LivePlaybackOsdState = copy(
        focusedControlId = LivePlaybackOsdPolicy.retainedControlId(contextualPanelOwnerId, controlIds),
        contextualPanelOwnerId = null,
        lastInteractionMs = nowMs,
    )

    fun updateControls(controlIds: List<String>): LivePlaybackOsdState = copy(
        focusedControlId = LivePlaybackOsdPolicy.retainedControlId(focusedControlId, controlIds),
        contextualPanelOwnerId = contextualPanelOwnerId?.takeIf(controlIds::contains),
    )

    fun shouldAutoHide(nowMs: Long): Boolean = visible && LivePlaybackOsdPolicy.shouldAutoHide(
        elapsedSinceInteractionMs = (nowMs - lastInteractionMs).coerceAtLeast(0L),
        contextualPanelOpen = contextualPanelOwnerId != null,
    )
}
