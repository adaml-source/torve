package com.torve.android.ui.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel
import com.torve.presentation.channels.ChannelsUiState
import org.junit.Assert.*
import org.junit.Test

class MobileChannelCountryFilterTest {
    @Test fun countriesExistBeforeAnyCategoryIsLoadedAndAliasesAreDeduplicated() {
        val state = ChannelsUiState(availableCountries = listOf("UK", "GB;DE"),
            allCategories = listOf(ChannelCategory("FR | News", 4)), selectedCountries = setOf("US"))
        assertEquals(listOf("DE", "FR", "GB", "US"), MobileChannelCountryFilter.available(state))
    }

    @Test fun countsOnlyCategoriesAreFilteredWithoutLoadingChannels() {
        val categories = listOf(ChannelCategory("DE | News", 100), ChannelCategory("GB | News", 80))
        assertEquals(listOf(categories.first()), MobileChannelCountryFilter.categories(categories, setOf("de")))
        assertEquals(categories, MobileChannelCountryFilter.categories(categories, emptySet()))
    }

    @Test fun multiCountryChannelsAndGroupFallbackRespectSelection() {
        assertTrue(MobileChannelCountryFilter.matches(Channel("News", "test", tvgCountry = "US;UK"), setOf("GB")))
        assertTrue(MobileChannelCountryFilter.matches(Channel("News", "test", groupTitle = "DE | News"), setOf("DE")))
        assertFalse(MobileChannelCountryFilter.matches(Channel("News", "test"), setOf("DE")))
        assertTrue(MobileChannelCountryFilter.matches(Channel("News", "test"), emptySet()))
    }

    @Test fun selectingOneCountryDoesNotRemoveOtherCountriesFromPicker() {
        val state = ChannelsUiState(selectedCountries = setOf("DE"),
            categories = listOf(ChannelCategory("DE | News", 10)),
            groupedChannels = mapOf("DE | News" to emptyList(), "FR | News" to emptyList()))
        assertEquals(listOf("DE", "FR"), MobileChannelCountryFilter.available(state))
    }

    @Test fun mixedLoadedCategoryKeepsOnlyMatchingChannels() {
        val de = EnrichedChannel(Channel("German news", "test", tvgCountry = "DE"))
        val fr = EnrichedChannel(Channel("French news", "test", tvgCountry = "FR"))
        val category = ChannelCategory("News", 2, channels = listOf(de, fr))
        assertEquals(listOf(de), MobileChannelCountryFilter.categories(listOf(category), setOf("DE")).single().channels)
    }
}
