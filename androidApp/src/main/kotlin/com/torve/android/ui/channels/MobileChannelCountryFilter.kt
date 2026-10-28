package com.torve.android.ui.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.presentation.channels.CategoryNameCleaner
import com.torve.presentation.channels.ChannelsUiState

internal object MobileChannelCountryFilter {
    private fun codes(value: String?): List<String> = value.orEmpty().split(',', ';')
        .map(CategoryNameCleaner::normalizeCountryCode)
        .filter { it.length in 2..3 && it.all(Char::isLetter) }

    fun available(state: ChannelsUiState): List<String> =
        (state.availableCountries + state.selectedCountries +
            state.allCategories.mapNotNull { it.countryCode ?: CategoryNameCleaner.clean(it.name).countryCode } +
            state.categories.mapNotNull { it.countryCode ?: CategoryNameCleaner.clean(it.name).countryCode } +
            state.groupedChannels.keys.mapNotNull { CategoryNameCleaner.clean(it).countryCode })
            .flatMap(::codes).distinct().sorted()

    fun matches(channel: Channel, selected: Set<String>, fallback: String? = null): Boolean {
        if (selected.isEmpty()) return true
        val countries = codes(channel.tvgCountry).ifEmpty {
            codes(CategoryNameCleaner.clean(channel.groupTitle.orEmpty()).countryCode ?: fallback)
        }
        return countries.any { it in selected.map(CategoryNameCleaner::normalizeCountryCode) }
    }

    fun categories(categories: List<ChannelCategory>, selected: Set<String>): List<ChannelCategory> {
        if (selected.isEmpty()) return categories
        val normalized = selected.map(CategoryNameCleaner::normalizeCountryCode).toSet()
        return categories.mapNotNull { category ->
            val country = category.countryCode ?: CategoryNameCleaner.clean(category.name).countryCode
            if (category.channels.isEmpty()) {
                category.takeIf { codes(country).any(normalized::contains) }
            } else {
                val channels = category.channels.filter { matches(it.channel, normalized, country) }
                if (channels.isEmpty()) null else category.copy(channels = channels)
            }
        }
    }
}
