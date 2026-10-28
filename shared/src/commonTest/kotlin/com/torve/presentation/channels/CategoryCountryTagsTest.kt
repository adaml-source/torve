package com.torve.presentation.channels

import kotlin.test.*

class CategoryCountryTagsTest {
    @Test fun countryCodesSurviveProviderPrefixesAndCountryNames() {
        assertEquals("AL", CategoryNameCleaner.clean("AL| ALBANIA SPORT GOLD RAW").countryCode)
        assertEquals("GB", CategoryNameCleaner.clean("UK: SPORTS HD").countryCode)
        assertEquals("DE", CategoryNameCleaner.clean("Germany Documentary").countryCode)
        assertEquals("AL", CategoryNameCleaner.clean("Albania Sport").countryCode)
        assertNull(CategoryNameCleaner.clean("24/7").countryCode)
        assertNull(CategoryNameCleaner.clean("4K| UHD").countryCode)
    }
    @Test fun genericGroupsInDifferentCountriesRemainIndependent() {
        val categories = CategoryNameCleaner.processCategoryCountsOnly(listOf("DE| SPORT HD" to 2L, "DE| SPORT FHD" to 1L, "AL| SPORT HD" to 4L, "NZ| SPORT HD" to 1L))
        assertEquals(3, categories.size)
        assertEquals(3, categories.single { it.countryCode == "DE" }.channelCount)
        assertEquals(3, categories.map { it.name }.distinct().size)
        assertEquals("Deutschland Sport", CategoryNameCleaner.clean("Deutschland Sport HD").name)
    }
}
