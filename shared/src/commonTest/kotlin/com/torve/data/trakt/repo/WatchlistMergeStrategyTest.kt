package com.torve.data.trakt.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistMergeStrategyTest {

    @Test
    fun deriveWatchlistMerge_preservesLocalOnlyItems() {
        val local = setOf("1", "2", "3")
        val remote = setOf("2", "3", "4")

        val plan = deriveWatchlistMerge(local, remote)

        assertEquals(setOf("4"), plan.toAdd)
        assertEquals(emptySet(), plan.toRemove)
    }
}
