package com.torve.android.tv.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackOsdPolicyTest {
    private val controls = listOf("play_pause", "channels", "audio", "subtitles", "aspect", "favorite")

    @Test
    fun `opening OSD establishes a visible valid default focus owner`() {
        val state = LivePlaybackOsdState().show(controls, nowMs = 100L)

        assertTrue(state.visible)
        assertEquals("play_pause", state.focusedControlId)
        assertFalse(state.shouldAutoHide(nowMs = 4_999L))
    }

    @Test
    fun `left and right traverse the complete rail deterministically`() {
        var state = LivePlaybackOsdState().show(controls, nowMs = 0L)

        controls.drop(1).forEachIndexed { index, expected ->
            state = state.move(controls, LivePlaybackOsdDirection.NEXT, nowMs = index + 1L)
            assertEquals(expected, state.focusedControlId)
        }
        state = state.move(controls, LivePlaybackOsdDirection.NEXT, nowMs = 10L)
        assertEquals(controls.last(), state.focusedControlId)

        controls.dropLast(1).reversed().forEachIndexed { index, expected ->
            state = state.move(controls, LivePlaybackOsdDirection.PREVIOUS, nowMs = 20L + index)
            assertEquals(expected, state.focusedControlId)
        }
    }

    @Test
    fun `closing a contextual panel restores its originating rail control`() {
        val restored = LivePlaybackOsdState()
            .show(controls, nowMs = 0L)
            .openPanel(ownerControlId = "subtitles", nowMs = 10L)
            .closePanel(controlIds = controls, nowMs = 20L)

        assertEquals("subtitles", restored.focusedControlId)
        assertEquals(null, restored.contextualPanelOwnerId)
    }

    @Test
    fun `label or value state updates retain stable logical control focus`() {
        val focused = LivePlaybackOsdState(
            visible = true,
            focusedControlId = "favorite",
            lastInteractionMs = 100L,
        )

        assertEquals("favorite", focused.updateControls(controls).focusedControlId)
        assertEquals("play_pause", focused.updateControls(controls - "favorite").focusedControlId)
    }

    @Test
    fun `auto hide occurs after five seconds only without contextual panel`() {
        val rail = LivePlaybackOsdState().show(controls, nowMs = 1_000L)
        assertFalse(rail.shouldAutoHide(nowMs = 5_999L))
        assertTrue(rail.shouldAutoHide(nowMs = 6_000L))

        val panel = rail.openPanel(ownerControlId = "audio", nowMs = 1_500L)
        assertFalse(panel.shouldAutoHide(nowMs = 30_000L))
    }

    @Test
    fun `rapid repeated navigation always leaves a valid focus owner`() {
        var state = LivePlaybackOsdState().show(controls, nowMs = 0L)
        repeat(100) { step ->
            state = state.move(
                controlIds = controls,
                direction = if (step % 2 == 0) LivePlaybackOsdDirection.NEXT else LivePlaybackOsdDirection.PREVIOUS,
                nowMs = step.toLong(),
            )
            assertTrue(state.focusedControlId in controls)
        }
    }

    @Test
    fun `fully visible rail control does not trigger scrolling or recentering`() {
        assertEquals(
            0,
            LivePlaybackOsdPolicy.revealScrollDeltaPx(
                itemStartPx = 220,
                itemEndPx = 320,
                viewportStartPx = 30,
                viewportEndPx = 970,
            ),
        )
    }

    @Test
    fun `rail scrolls only the clipped distance at either viewport edge`() {
        assertEquals(
            35,
            LivePlaybackOsdPolicy.revealScrollDeltaPx(
                itemStartPx = 905,
                itemEndPx = 1_005,
                viewportStartPx = 30,
                viewportEndPx = 970,
            ),
        )
        assertEquals(
            -25,
            LivePlaybackOsdPolicy.revealScrollDeltaPx(
                itemStartPx = 5,
                itemEndPx = 105,
                viewportStartPx = 30,
                viewportEndPx = 970,
            ),
        )
    }

    @Test
    fun `rail remains within compact TV OSD proportions at 720p 1080p 1440p and 4k`() {
        val height720 = LivePlaybackOsdPolicy.railHeightDp(720f)
        val height1080 = LivePlaybackOsdPolicy.railHeightDp(1080f)
        val height1440 = LivePlaybackOsdPolicy.railHeightDp(1440f)
        val height4k = LivePlaybackOsdPolicy.railHeightDp(2160f)

        assertEquals(108f, height720, 0.01f)
        assertEquals(162f, height1080, 0.01f)
        assertEquals(216f, height1440, 0.01f)
        assertEquals(260f, height4k, 0.01f)
        assertTrue(height720 / 720f in 0.12f..0.18f)
        assertTrue(height1080 / 1080f in 0.12f..0.18f)
        assertTrue(height4k / 2160f in 0.12f..0.18f)
        assertEquals(1f, LivePlaybackOsdPolicy.CONTEXT_OPTION_FOCUSED_SCALE, 0f)
    }

    @Test
    fun `timeline has reserved non-overlapping space at common TV viewport heights`() {
        listOf(720f, 1080f, 1440f, 2160f).forEach { viewportHeight ->
            val regions = LivePlaybackOsdPolicy.verticalRegions(
                viewportHeightDp = viewportHeight,
                timelineVisible = true,
            )

            assertFalse("timeline intersects controls at ${viewportHeight}p", regions.timelineIntersectsControls)
            assertEquals(
                LivePlaybackOsdPolicy.TIMELINE_CONTROL_GAP_DP,
                regions.timelineControlGapDp,
                0.01f,
            )
            assertTrue(regions.controlsBottomDp > regions.controlsTopDp)
        }
    }
}
