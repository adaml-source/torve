package com.torve.presentation.channels

import kotlin.test.*

class CategoryCountMergeTest {
    @Test fun countsKeepFirstDisplayNameAndMergeQualityVariants() {
        val result = CategoryNameCleaner.processCategoryCountsOnly(listOf("DE| SPORT HD" to 10L, "DE| SPORT FHD" to 5L, "AL| SPORT HD" to 3L))
        assertEquals(listOf("Albania Sport", "Germany Sport"), result.map { it.name })
        assertEquals(15, result.last().channelCount)
        assertEquals(setOf("HD", "FHD"), result.last().qualityTags)
    }
    @Test fun largeCategoryListKeepsEveryGroupAndCount() {
        val result = CategoryNameCleaner.processCategoryCountsOnly((1..500).map { "DE| Group $it HD" to it.toLong() })
        assertEquals(500, result.size)
        assertEquals(125250, result.sumOf { it.channelCount })
        assertTrue(result.all { it.countryCode == "DE" })
    }
}
