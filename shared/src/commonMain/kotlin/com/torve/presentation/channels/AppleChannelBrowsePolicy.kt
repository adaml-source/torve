package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory

/** Lightweight Apple group browsing uses the same category identities and filters as Android. */
object AppleChannelBrowsePolicy {
    private val adultKeywords = listOf("xxx", "adult", "18+", "porn", "erotic")

    fun countryCode(category: ChannelCategory): String? =
        (category.countryCode ?: CategoryNameCleaner.clean(category.name).countryCode)
            ?.let(CategoryNameCleaner::normalizeCountryCode)
            ?.takeIf(String::isNotBlank)

    /** Counts-only categories keep a large playlist out of memory until a group is opened. */
    fun visibleCategories(state: ChannelsUiState): List<ChannelCategory> {
        val countries = state.selectedCountries.map(CategoryNameCleaner::normalizeCountryCode).toSet()
        val hidden = state.hiddenCategories.map(String::lowercase).toSet()
        return state.categories.filter { category ->
            category.channelCount > 0 && category.name.lowercase() !in hidden &&
                (countries.isEmpty() || countryCode(category) in countries) &&
                (state.xxxEnabled || adultKeywords.none { category.name.contains(it, ignoreCase = true) })
        }
    }

    /** Provider separator rows are headings, not playable channels; Android applies this same rule. */
    fun isDisplayable(channel: Channel): Boolean =
        channel.name.isNotBlank() && channel.name.count { it == '#' || it == '=' || it == '_' } < 4

    fun belongsToCategory(channel: Channel, categoryName: String): Boolean =
        CategoryNameCleaner.clean(channel.groupTitle.orEmpty()).name.equals(categoryName, ignoreCase = true)
}
