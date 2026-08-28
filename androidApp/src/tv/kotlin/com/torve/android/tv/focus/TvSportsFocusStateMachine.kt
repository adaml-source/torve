package com.torve.android.tv.focus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class TvSportsFocusRegion {
    CATEGORY_ROW,
    TOP_ACTIONS,
    EVENT_LIST,
    SOURCE_LIST,
}

internal enum class TvSportsTopAction(val actionId: String) {
    REFRESH("sports_refresh"),
    SEARCH("sports_search"),
    RETRY("sports_retry"),
}

internal data class TvSportsFocusState(
    val selectedCategoryId: String,
    val focusedRegion: TvSportsFocusRegion = TvSportsFocusRegion.CATEGORY_ROW,
    val focusedCategoryId: String = selectedCategoryId,
    val focusedTopAction: TvSportsTopAction? = null,
    val focusedEventId: String? = null,
    val focusedEventIndex: Int = 0,
    val focusedSourceId: String? = null,
    val rememberedEventsByCategory: Map<String, TvSportsRememberedEvent> = emptyMap(),
)

internal data class TvSportsRememberedEvent(
    val eventId: String,
    val index: Int,
)

internal data class TvSportsResultEntry(
    val eventId: String,
    val index: Int,
)

internal data class TvSportsCategoryEntry(
    val categoryId: String,
    val index: Int,
)

internal sealed interface TvSportsFocusTarget {
    data class Category(val categoryId: String) : TvSportsFocusTarget
    data class TopAction(val action: TvSportsTopAction) : TvSportsFocusTarget
    data class Event(val eventId: String, val index: Int) : TvSportsFocusTarget
}

/**
 * Logical Sports focus is deliberately independent from Compose focus nodes.
 * Lazy content may be removed and re-created while this state keeps the
 * category/event/source identity needed for deterministic repair.
 */
internal class TvSportsFocusStateMachine(initialCategoryId: String) {
    var state by mutableStateOf(TvSportsFocusState(selectedCategoryId = initialCategoryId))
        private set

    fun selectCategory(categoryId: String) {
        val remembered = state.rememberedEventsByCategory[categoryId]
        state = state.copy(
            selectedCategoryId = categoryId,
            focusedRegion = TvSportsFocusRegion.CATEGORY_ROW,
            focusedCategoryId = categoryId,
            focusedTopAction = null,
            focusedEventId = remembered?.eventId,
            focusedEventIndex = remembered?.index ?: 0,
            focusedSourceId = remembered?.eventId,
        )
    }

    fun markCategoryFocused(categoryId: String) {
        val remembered = state.rememberedEventsByCategory[state.selectedCategoryId]
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.CATEGORY_ROW,
            focusedCategoryId = categoryId,
            focusedTopAction = null,
            focusedEventId = remembered?.eventId,
            focusedEventIndex = remembered?.index ?: 0,
            focusedSourceId = remembered?.eventId,
        )
    }

    fun markTopActionFocused(action: TvSportsTopAction) {
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.TOP_ACTIONS,
            focusedTopAction = action,
        )
    }

    fun markEventFocused(eventId: String, index: Int, sourceId: String = eventId) {
        val safeIndex = index.coerceAtLeast(0)
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.EVENT_LIST,
            focusedTopAction = null,
            focusedEventId = eventId,
            focusedEventIndex = safeIndex,
            focusedSourceId = sourceId,
            rememberedEventsByCategory = state.rememberedEventsByCategory + (
                state.selectedCategoryId to TvSportsRememberedEvent(eventId, safeIndex)
            ),
        )
    }

    fun rememberedEventForSelectedCategory(): TvSportsRememberedEvent? =
        state.rememberedEventsByCategory[state.selectedCategoryId]

    /**
     * Returns a target only when the current event disappeared. Existing
     * focused events are intentionally left alone so data publication never
     * causes an unnecessary requestFocus() call.
     */
    fun repairAfterEventMutation(visibleEventIds: List<String>): TvSportsFocusTarget? {
        if (state.focusedRegion != TvSportsFocusRegion.EVENT_LIST) return null
        val focusedId = state.focusedEventId
        if (focusedId != null && focusedId in visibleEventIds) return null

        val replacementIndex = nearestSportsItemIndex(
            previousIndex = state.focusedEventIndex,
            itemCount = visibleEventIds.size,
        )
        if (replacementIndex == null) {
            state = state.copy(
                focusedRegion = TvSportsFocusRegion.CATEGORY_ROW,
                focusedCategoryId = state.selectedCategoryId,
                focusedEventId = null,
                focusedSourceId = null,
                rememberedEventsByCategory = state.rememberedEventsByCategory - state.selectedCategoryId,
            )
            return TvSportsFocusTarget.Category(state.selectedCategoryId)
        }

        val replacementId = visibleEventIds[replacementIndex]
        state = state.copy(
            focusedEventId = replacementId,
            focusedEventIndex = replacementIndex,
            focusedSourceId = replacementId,
            rememberedEventsByCategory = state.rememberedEventsByCategory + (
                state.selectedCategoryId to TvSportsRememberedEvent(replacementId, replacementIndex)
            ),
        )
        return TvSportsFocusTarget.Event(replacementId, replacementIndex)
    }

    fun repairAfterTopActionMutation(
        availableActions: Set<TvSportsTopAction>,
    ): TvSportsFocusTarget? {
        if (state.focusedRegion != TvSportsFocusRegion.TOP_ACTIONS) return null
        val action = state.focusedTopAction ?: return null
        if (action in availableActions) return null
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.CATEGORY_ROW,
            focusedCategoryId = state.selectedCategoryId,
            focusedTopAction = null,
        )
        return TvSportsFocusTarget.Category(state.selectedCategoryId)
    }

    fun targetForRestore(visibleEventIds: List<String>): TvSportsFocusTarget {
        return when (state.focusedRegion) {
            TvSportsFocusRegion.TOP_ACTIONS -> TvSportsFocusTarget.TopAction(
                state.focusedTopAction ?: TvSportsTopAction.REFRESH,
            )
            TvSportsFocusRegion.EVENT_LIST,
            TvSportsFocusRegion.SOURCE_LIST,
            -> {
                val exactIndex = state.focusedEventId?.let(visibleEventIds::indexOf) ?: -1
                val index = if (exactIndex >= 0) {
                    exactIndex
                } else {
                    nearestSportsItemIndex(state.focusedEventIndex, visibleEventIds.size)
                }
                if (index == null) {
                    TvSportsFocusTarget.Category(state.selectedCategoryId)
                } else {
                    TvSportsFocusTarget.Event(visibleEventIds[index], index)
                }
            }
            TvSportsFocusRegion.CATEGORY_ROW -> TvSportsFocusTarget.Category(
                state.focusedCategoryId.takeIf { it.isNotBlank() } ?: state.selectedCategoryId,
            )
        }
    }
}

internal fun nearestSportsItemIndex(previousIndex: Int, itemCount: Int): Int? {
    if (itemCount <= 0) return null
    return previousIndex.coerceIn(0, itemCount - 1)
}

/**
 * Resolves CATEGORY_ROW -> RESULTS_LIST using only result nodes that are
 * currently composed. A remembered off-screen item is not a valid direct
 * FocusRequester target; in that case the first composed row is the safe
 * deterministic entry point and normal list navigation can continue from it.
 */
internal fun resolveSportsResultEntry(
    visibleEventIds: List<String>,
    rememberedEvent: TvSportsRememberedEvent?,
    composedEventIds: Set<String>,
): TvSportsResultEntry? {
    if (visibleEventIds.isEmpty() || composedEventIds.isEmpty()) return null

    val rememberedIndex = rememberedEvent
        ?.eventId
        ?.let(visibleEventIds::indexOf)
        ?.takeIf { it >= 0 && visibleEventIds[it] in composedEventIds }
    if (rememberedIndex != null) {
        return TvSportsResultEntry(visibleEventIds[rememberedIndex], rememberedIndex)
    }

    val firstComposedIndex = visibleEventIds.indexOfFirst(composedEventIds::contains)
        .takeIf { it >= 0 }
        ?: return null
    return TvSportsResultEntry(visibleEventIds[firstComposedIndex], firstComposedIndex)
}

internal fun resolveSportsCategoryEntry(
    categoryIds: List<String>,
    selectedCategoryId: String,
    composedCategoryIds: Set<String>,
): TvSportsCategoryEntry? {
    if (categoryIds.isEmpty() || composedCategoryIds.isEmpty()) return null
    val selectedIndex = categoryIds.indexOf(selectedCategoryId).takeIf { it >= 0 } ?: 0
    if (categoryIds[selectedIndex] in composedCategoryIds) {
        return TvSportsCategoryEntry(categoryIds[selectedIndex], selectedIndex)
    }
    val nearestIndex = categoryIds.indices
        .filter { categoryIds[it] in composedCategoryIds }
        .minByOrNull { kotlin.math.abs(it - selectedIndex) }
        ?: return null
    return TvSportsCategoryEntry(categoryIds[nearestIndex], nearestIndex)
}
