package com.torve.android.ui.player

/** Logical focus positions for the TV subtitle picker.
 *
 * Keeping navigation in this model makes every D-pad edge explicit and keeps
 * Compose focus search from choosing an unrelated/off-screen descendant.
 */
internal data class SubtitlePickerFocusTarget(
    val row: SubtitlePickerFocusRow,
    val index: Int,
)

internal enum class SubtitlePickerFocusRow {
    FILTERS,
    LANGUAGES,
    RESULTS,
}

internal enum class SubtitlePickerDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

internal class SubtitlePickerFocusGraph(
    private val filterCount: Int,
    private val enabledFilterIndexes: Set<Int>,
    private val languageCount: Int,
    private val resultCount: Int,
    private val resultExitTarget: SubtitlePickerFocusTarget,
) {
    fun move(
        current: SubtitlePickerFocusTarget,
        direction: SubtitlePickerDirection,
    ): SubtitlePickerFocusTarget = when (current.row) {
        SubtitlePickerFocusRow.FILTERS -> moveFromFilters(current, direction)
        SubtitlePickerFocusRow.LANGUAGES -> moveFromLanguages(current, direction)
        SubtitlePickerFocusRow.RESULTS -> moveFromResults(current, direction)
    }

    private fun moveFromFilters(
        current: SubtitlePickerFocusTarget,
        direction: SubtitlePickerDirection,
    ): SubtitlePickerFocusTarget = when (direction) {
        SubtitlePickerDirection.LEFT -> adjacentEnabledFilter(current, -1)
        SubtitlePickerDirection.RIGHT -> adjacentEnabledFilter(current, 1)
        SubtitlePickerDirection.UP -> current
        SubtitlePickerDirection.DOWN -> when {
            languageCount > 0 -> SubtitlePickerFocusTarget(
                SubtitlePickerFocusRow.LANGUAGES,
                proportionalIndex(current.index, filterCount, languageCount),
            )
            resultCount > 0 -> SubtitlePickerFocusTarget(SubtitlePickerFocusRow.RESULTS, 0)
            else -> current
        }
    }

    private fun moveFromLanguages(
        current: SubtitlePickerFocusTarget,
        direction: SubtitlePickerDirection,
    ): SubtitlePickerFocusTarget = when (direction) {
        SubtitlePickerDirection.LEFT -> current.copy(index = (current.index - 1).coerceAtLeast(0))
        SubtitlePickerDirection.RIGHT -> current.copy(index = (current.index + 1).coerceAtMost((languageCount - 1).coerceAtLeast(0)))
        SubtitlePickerDirection.UP -> nearestEnabledFilter(
            proportionalIndex(current.index, languageCount, filterCount),
        ) ?: current
        SubtitlePickerDirection.DOWN -> if (resultCount > 0) {
            SubtitlePickerFocusTarget(SubtitlePickerFocusRow.RESULTS, 0)
        } else {
            current
        }
    }

    private fun moveFromResults(
        current: SubtitlePickerFocusTarget,
        direction: SubtitlePickerDirection,
    ): SubtitlePickerFocusTarget = when (direction) {
        // Result cards have no horizontal action. Consuming both directions
        // prevents Compose from escaping the picker hierarchy.
        SubtitlePickerDirection.LEFT,
        SubtitlePickerDirection.RIGHT,
        -> current
        SubtitlePickerDirection.UP -> if (current.index > 0) {
            current.copy(index = current.index - 1)
        } else {
            validExitTarget()
        }
        SubtitlePickerDirection.DOWN -> if (current.index + 1 < resultCount) {
            current.copy(index = current.index + 1)
        } else {
            current
        }
    }

    private fun adjacentEnabledFilter(
        current: SubtitlePickerFocusTarget,
        step: Int,
    ): SubtitlePickerFocusTarget {
        var candidate = current.index + step
        while (candidate in 0 until filterCount) {
            if (candidate in enabledFilterIndexes) return current.copy(index = candidate)
            candidate += step
        }
        return current
    }

    private fun nearestEnabledFilter(preferredIndex: Int): SubtitlePickerFocusTarget? {
        val index = enabledFilterIndexes.minWithOrNull(
            compareBy<Int> { kotlin.math.abs(it - preferredIndex) }.thenBy { it },
        ) ?: return null
        return SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, index)
    }

    private fun validExitTarget(): SubtitlePickerFocusTarget = when (resultExitTarget.row) {
        SubtitlePickerFocusRow.LANGUAGES -> if (resultExitTarget.index in 0 until languageCount) {
            resultExitTarget
        } else {
            nearestEnabledFilter(0) ?: resultExitTarget
        }
        SubtitlePickerFocusRow.FILTERS -> if (resultExitTarget.index in enabledFilterIndexes) {
            resultExitTarget
        } else {
            nearestEnabledFilter(resultExitTarget.index) ?: resultExitTarget
        }
        SubtitlePickerFocusRow.RESULTS -> nearestEnabledFilter(0) ?: resultExitTarget
    }

    private fun proportionalIndex(index: Int, fromCount: Int, toCount: Int): Int {
        if (toCount <= 1 || fromCount <= 1) return 0
        // Rows are left-aligned and use the same chip spacing. Matching the
        // visible ordinal is both predictable and reversible while both rows
        // contain that ordinal; clamp only when the destination row is shorter.
        return index.coerceIn(0, toCount - 1)
    }
}
