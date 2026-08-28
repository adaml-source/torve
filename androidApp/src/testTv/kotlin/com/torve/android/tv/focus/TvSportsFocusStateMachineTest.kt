package com.torve.android.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvSportsFocusStateMachineTest {
    @Test
    fun exactEventIdentitySurvivesRefreshWithoutFocusRequest() {
        val state = TvSportsFocusStateMachine("FOOTBALL")
        state.markEventFocused("event-7", 1, sourceId = "source-7")

        assertNull(state.repairAfterEventMutation(listOf("event-1", "event-7", "event-9")))
        assertEquals("event-7", state.state.focusedEventId)
        assertEquals("source-7", state.state.focusedSourceId)
    }

    @Test
    fun removedEventRepairsToSamePositionThenPreviousPosition() {
        val state = TvSportsFocusStateMachine("FOOTBALL")
        state.markEventFocused("event-5", 2)

        assertEquals(
            TvSportsFocusTarget.Event("event-6", 2),
            state.repairAfterEventMutation(listOf("event-1", "event-4", "event-6")),
        )

        state.markEventFocused("event-6", 2)
        assertEquals(
            TvSportsFocusTarget.Event("event-4", 1),
            state.repairAfterEventMutation(listOf("event-1", "event-4")),
        )
    }

    @Test
    fun emptyEventMutationFallsBackToSelectedCategory() {
        val state = TvSportsFocusStateMachine("BASKETBALL")
        state.markEventFocused("event-2", 1)

        assertEquals(
            TvSportsFocusTarget.Category("BASKETBALL"),
            state.repairAfterEventMutation(emptyList()),
        )
        assertEquals(TvSportsFocusRegion.CATEGORY_ROW, state.state.focusedRegion)
    }

    @Test
    fun categorySwitchAndRefreshActionHaveStableLogicalIdentity() {
        val state = TvSportsFocusStateMachine("all")
        state.selectCategory("TENNIS")
        state.markTopActionFocused(TvSportsTopAction.REFRESH)

        assertEquals("TENNIS", state.state.selectedCategoryId)
        assertEquals(
            TvSportsFocusTarget.TopAction(TvSportsTopAction.REFRESH),
            state.targetForRestore(emptyList()),
        )
        assertEquals("sports_refresh", TvSportsTopAction.REFRESH.actionId)
    }

    @Test
    fun sourceIdentityRemainsAttachedToEventAcrossWorkingState() {
        val state = TvSportsFocusStateMachine("SOCCER")
        state.markEventFocused("event-123", 4, sourceId = "release-guid-123")

        assertEquals("event-123", state.state.focusedEventId)
        assertEquals("release-guid-123", state.state.focusedSourceId)
        assertEquals(
            TvSportsFocusTarget.Event("event-123", 0),
            state.targetForRestore(listOf("event-123")),
        )
    }

    @Test
    fun removedRetryActionFallsBackToSelectedCategory() {
        val state = TvSportsFocusStateMachine("TENNIS")
        state.markTopActionFocused(TvSportsTopAction.RETRY)

        assertEquals(
            TvSportsFocusTarget.Category("TENNIS"),
            state.repairAfterTopActionMutation(
                setOf(TvSportsTopAction.REFRESH, TvSportsTopAction.SEARCH),
            ),
        )
        assertEquals(TvSportsFocusRegion.CATEGORY_ROW, state.state.focusedRegion)
    }

    @Test
    fun rapidCategorySwitchingAndRepeatedSourceReentryKeepLogicalFocusValid() {
        val state = TvSportsFocusStateMachine("all")
        val categories = listOf(
            "F1", "MMA", "BOXING", "BASKETBALL", "TENNIS",
            "SOCCER", "HOCKEY", "RUGBY", "CRICKET", "BASKETBALL",
        )
        categories.forEach { category ->
            state.selectCategory(category)
            state.markCategoryFocused(category)
        }
        assertEquals("BASKETBALL", state.state.selectedCategoryId)
        assertEquals(TvSportsFocusRegion.CATEGORY_ROW, state.state.focusedRegion)

        repeat(10) {
            state.markEventFocused("event-1", 0, "source-1")
            state.markCategoryFocused("BASKETBALL")
            state.markEventFocused("event-1", 0, "source-1")
            assertEquals(
                TvSportsFocusTarget.Event("event-1", 0),
                state.targetForRestore(listOf("event-1", "event-2")),
            )
        }
    }
}
