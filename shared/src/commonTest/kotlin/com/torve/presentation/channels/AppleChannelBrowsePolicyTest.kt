package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleChannelBrowsePolicyTest {
    private fun group(name: String, country: String? = null, count: Int = 12) =
        ChannelCategory(name = name, channelCount = count, countryCode = country)

    @Test fun lightweightCountriesFilterGroupsWithoutLoadingTheirChannels() {
        val values = listOf(group("Germany News", "DE"), group("Albania Sport", "AL"), group("24/7"))
        val state = ChannelsUiState(categories = values, selectedCountries = setOf("DE"))
        assertEquals(listOf("Germany News"), AppleChannelBrowsePolicy.visibleCategories(state).map { it.name })
        assertTrue(state.channels.isEmpty())
        assertTrue(state.categoryChannels.isEmpty())
    }

    @Test fun theGroupsPageRetainsEveryGroupAndItsOrderAfterOpeningOneGroup() {
        val values = listOf(group("Germany Sport", "DE"), group("Germany News", "DE"))
        val state = ChannelsUiState(categories = values, selectedGroup = "Germany News")
        assertEquals(values, AppleChannelBrowsePolicy.visibleCategories(state))
    }

    @Test fun hiddenAndEmptyGroupsDoNotCreateDeadEnds() {
        val state = ChannelsUiState(
            categories = listOf(group("Germany News", "DE"), group("Germany Sport", "DE"), group("Empty", count = 0)),
            hiddenCategories = setOf("GERMANY NEWS"),
        )
        assertEquals(listOf("Germany Sport"), AppleChannelBrowsePolicy.visibleCategories(state).map { it.name })
    }

    @Test fun legacyCountryAliasesMatchTheCurrentSelection() {
        val state = ChannelsUiState(categories = listOf(group("UK Sport", "UK"), group("USA News", "USA")), selectedCountries = setOf("GBR"))
        assertEquals(listOf("UK Sport"), AppleChannelBrowsePolicy.visibleCategories(state).map { it.name })
    }

    @Test fun countriesCanBeRecoveredFromAnOlderCountsOnlyCache() {
        assertEquals("DE", AppleChannelBrowsePolicy.countryCode(group("Germany Documentary")))
        val state = ChannelsUiState(categories = listOf(group("Germany Documentary"), group("24/7")), selectedCountries = setOf("DEU"))
        assertEquals(1, AppleChannelBrowsePolicy.visibleCategories(state).size)
    }

    @Test fun unknownCountriesRemainAvailableWhenAllCountriesAreSelected() {
        val state = ChannelsUiState(categories = listOf(group("24/7")))
        assertEquals(state.categories, AppleChannelBrowsePolicy.visibleCategories(state))
    }

    @Test fun adultGroupVisibilityRespectsTheExistingPreference() {
        val state = ChannelsUiState(categories = listOf(group("Germany News", "DE"), group("Germany Adult", "DE")))
        assertEquals(1, AppleChannelBrowsePolicy.visibleCategories(state).size)
        assertEquals(2, AppleChannelBrowsePolicy.visibleCategories(state.copy(xxxEnabled = true)).size)
    }

    @Test fun providerHeadingsAreExcludedButOrdinaryNamesStayPlayable() {
        assertFalse(AppleChannelBrowsePolicy.isDisplayable(Channel("#### APPLE+ ####", "http://local/heading")))
        assertFalse(AppleChannelBrowsePolicy.isDisplayable(Channel("   ", "http://local/empty")))
        assertTrue(AppleChannelBrowsePolicy.isDisplayable(Channel("Apple TV+ 4K", "http://local/apple")))
        assertTrue(AppleChannelBrowsePolicy.isDisplayable(Channel("News_24", "http://local/news")))
    }

    @Test fun aGuideScopeUsesMergedCategoryIdentityAndDoesNotLeakAnotherCountry() {
        val selected = CategoryNameCleaner.clean("DE| SPORT HD").name
        assertTrue(AppleChannelBrowsePolicy.belongsToCategory(Channel("Sport FHD", "http://local/sport", groupTitle = "DE| SPORT FHD"), selected))
        assertFalse(AppleChannelBrowsePolicy.belongsToCategory(Channel("Sport HD", "http://local/al", groupTitle = "AL| SPORT HD"), selected))
    }
}
