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

    @Test
    fun categoryRetainsPerCategoryResultMemoryWithoutLockingFocus() {
        val state = TvSportsFocusStateMachine("FOOTBALL")
        state.markEventFocused("football-6", 5)
        state.markCategoryFocused("FOOTBALL")

        assertEquals(
            TvSportsRememberedEvent("football-6", 5),
            state.rememberedEventForSelectedCategory(),
        )
        assertEquals(TvSportsFocusRegion.CATEGORY_ROW, state.state.focusedRegion)

        state.selectCategory("BASKETBALL")
        state.markEventFocused("basketball-2", 1)
        state.selectCategory("FOOTBALL")

        assertEquals("football-6", state.state.focusedEventId)
        assertEquals(5, state.state.focusedEventIndex)
    }

    @Test
    fun categoryEntryUsesRememberedResultOnlyWhenItsNodeIsComposed() {
        val ids = listOf("event-1", "event-2", "event-6", "event-7")

        assertEquals(
            TvSportsResultEntry("event-6", 2),
            resolveSportsResultEntry(
                visibleEventIds = ids,
                rememberedEvent = TvSportsRememberedEvent("event-6", 2),
                composedEventIds = setOf("event-2", "event-6", "event-7"),
            ),
        )
        assertEquals(
            TvSportsResultEntry("event-2", 1),
            resolveSportsResultEntry(
                visibleEventIds = ids,
                rememberedEvent = TvSportsRememberedEvent("event-1", 0),
                composedEventIds = setOf("event-2", "event-6", "event-7"),
            ),
        )
    }

    @Test
    fun categoryEntryNeverTargetsLoadingOrEmptyResults() {
        assertNull(
            resolveSportsResultEntry(
                visibleEventIds = listOf("event-1"),
                rememberedEvent = TvSportsRememberedEvent("event-1", 0),
                composedEventIds = emptySet(),
            ),
        )
        assertNull(
            resolveSportsResultEntry(
                visibleEventIds = emptyList(),
                rememberedEvent = null,
                composedEventIds = emptySet(),
            ),
        )
    }

    @Test
    fun categoryRowEntryUsesSelectedChipWhenComposedOrNearestLiveChip() {
        val categories = listOf("all", "today", "recent", "football", "basketball")

        assertEquals(
            TvSportsCategoryEntry("football", 3),
            resolveSportsCategoryEntry(
                categoryIds = categories,
                selectedCategoryId = "football",
                composedCategoryIds = setOf("recent", "football", "basketball"),
            ),
        )
        assertEquals(
            TvSportsCategoryEntry("recent", 2),
            resolveSportsCategoryEntry(
                categoryIds = categories,
                selectedCategoryId = "football",
                composedCategoryIds = setOf("all", "today", "recent"),
            ),
        )
        assertNull(
            resolveSportsCategoryEntry(
                categoryIds = categories,
                selectedCategoryId = "football",
                composedCategoryIds = emptySet(),
            ),
        )
    }
}
