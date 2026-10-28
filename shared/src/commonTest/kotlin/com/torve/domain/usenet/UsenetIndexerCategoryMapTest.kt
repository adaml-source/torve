package com.torve.domain.usenet

import kotlin.test.Test
import kotlin.test.assertEquals

class UsenetIndexerCategoryMapTest {
    @Test fun treasureMapsSportsIncludesEverySupportedLanguage() {
        assertEquals(setOf("5060", "5160", "5260", "5360"),
            UsenetIndexerCategoryMap.sportsCategoriesFor("scenenzbs").split(',').toSet())
    }

    @Test fun otherIndexersKeepTheStandardSportsCategory() {
        assertEquals("5060", UsenetIndexerCategoryMap.sportsCategoriesFor("nzbgeek"))
        assertEquals("5060", UsenetIndexerCategoryMap.sportsCategoriesFor("custom"))
        assertEquals("5000", UsenetIndexerCategoryMap.sportsSearchCategoriesFor("custom"))
    }
}
