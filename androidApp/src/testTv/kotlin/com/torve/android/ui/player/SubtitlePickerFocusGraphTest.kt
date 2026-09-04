package com.torve.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePickerFocusGraphTest {
    private val filter = { index: Int -> SubtitlePickerFocusTarget(SubtitlePickerFocusRow.FILTERS, index) }
    private val language = { index: Int -> SubtitlePickerFocusTarget(SubtitlePickerFocusRow.LANGUAGES, index) }
    private val result = { index: Int -> SubtitlePickerFocusTarget(SubtitlePickerFocusRow.RESULTS, index) }

    @Test
    fun allDpadDirectionsAreReversibleAcrossRows() {
        val graph = graph(resultExitTarget = language(2))

        assertEquals(filter(2), graph.move(filter(1), SubtitlePickerDirection.RIGHT))
        assertEquals(filter(1), graph.move(filter(2), SubtitlePickerDirection.LEFT))

        val lower = graph.move(filter(2), SubtitlePickerDirection.DOWN)
        assertEquals(SubtitlePickerFocusRow.LANGUAGES, lower.row)
        assertEquals(filter(2), graph.move(lower, SubtitlePickerDirection.UP))

        assertEquals(result(0), graph.move(language(2), SubtitlePickerDirection.DOWN))
        assertEquals(language(2), graph.move(result(0), SubtitlePickerDirection.UP))
    }

    @Test
    fun disabledLoadControlIsSkippedInBothDirections() {
        val graph = graph(enabledFilters = (0 until 10).toSet() - 1)

        assertEquals(filter(2), graph.move(filter(0), SubtitlePickerDirection.RIGHT))
        assertEquals(filter(0), graph.move(filter(2), SubtitlePickerDirection.LEFT))
    }

    @Test
    fun resultCardsCannotLeakHorizontalFocusAndListBoundariesAreStable() {
        val graph = graph(resultExitTarget = filter(4), resultCount = 3)

        assertEquals(result(1), graph.move(result(1), SubtitlePickerDirection.LEFT))
        assertEquals(result(1), graph.move(result(1), SubtitlePickerDirection.RIGHT))
        assertEquals(result(2), graph.move(result(1), SubtitlePickerDirection.DOWN))
        assertEquals(result(2), graph.move(result(2), SubtitlePickerDirection.DOWN))
        assertEquals(result(1), graph.move(result(2), SubtitlePickerDirection.UP))
        assertEquals(filter(4), graph.move(result(0), SubtitlePickerDirection.UP))
    }

    @Test
    fun missingFocusedFilterFallsBackToNearestEnabledSibling() {
        val graph = graph(
            enabledFilters = setOf(0, 2, 3),
            resultExitTarget = filter(1),
        )

        assertEquals(filter(0), graph.move(result(0), SubtitlePickerDirection.UP))
    }

    private fun graph(
        enabledFilters: Set<Int> = (0 until 10).toSet(),
        resultExitTarget: SubtitlePickerFocusTarget = filter(0),
        resultCount: Int = 4,
    ) = SubtitlePickerFocusGraph(
        filterCount = 10,
        enabledFilterIndexes = enabledFilters,
        languageCount = 4,
        resultCount = resultCount,
        resultExitTarget = resultExitTarget,
    )
}
