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
        state = state.copy(
            selectedCategoryId = categoryId,
            focusedRegion = TvSportsFocusRegion.CATEGORY_ROW,
            focusedCategoryId = categoryId,
            focusedTopAction = null,
            focusedEventId = null,
            focusedSourceId = null,
        )
    }

    fun markCategoryFocused(categoryId: String) {
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.CATEGORY_ROW,
            focusedCategoryId = categoryId,
            focusedTopAction = null,
            focusedEventId = null,
            focusedSourceId = null,
        )
    }

    fun markTopActionFocused(action: TvSportsTopAction) {
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.TOP_ACTIONS,
            focusedTopAction = action,
            focusedEventId = null,
            focusedSourceId = null,
        )
    }

    fun markEventFocused(eventId: String, index: Int, sourceId: String = eventId) {
        state = state.copy(
            focusedRegion = TvSportsFocusRegion.EVENT_LIST,
            focusedTopAction = null,
            focusedEventId = eventId,
            focusedEventIndex = index.coerceAtLeast(0),
            focusedSourceId = sourceId,
        )
    }

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
            )
            return TvSportsFocusTarget.Category(state.selectedCategoryId)
        }

        val replacementId = visibleEventIds[replacementIndex]
        state = state.copy(
            focusedEventId = replacementId,
            focusedEventIndex = replacementIndex,
            focusedSourceId = replacementId,
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
