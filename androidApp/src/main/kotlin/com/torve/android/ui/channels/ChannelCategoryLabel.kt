package com.torve.android.ui.channels

import com.torve.presentation.channels.CategoryNameCleaner
import java.util.Locale

/** Display only: retain the full group name as its loading/favourite/expansion identity. */
internal fun channelCategoryLabel(name: String, countryCode: String?): String {
    if (countryCode.isNullOrBlank()) return name
    val code = CategoryNameCleaner.normalizeCountryCode(countryCode)
    val region = Locale.forLanguageTag("und-$code")
    val aliases = when (code) {
        "DE" -> listOf("Deutschland", "GER", "DEU")
        "GB" -> listOf("UK", "GBR")
        "US" -> listOf("USA")
        "TR" -> listOf("Turkey", "Türkiye")
        "CZ" -> listOf("Czech", "Czech Republic")
        "BA" -> listOf("Bosnia")
        else -> emptyList()
    }
    val prefixes = (aliases + code + "[$code]" + region.getDisplayCountry(Locale.ENGLISH) +
        region.getDisplayCountry(Locale.getDefault())).filter(String::isNotBlank).distinct().sortedByDescending(String::length)
    val trimmed = name.trim()
    for (prefix in prefixes) {
        if (!trimmed.startsWith(prefix, ignoreCase = true)) continue
        val rest = trimmed.substring(prefix.length)
        if (rest.firstOrNull()?.let { it.isWhitespace() || it in "|:–—-" } != true) continue
        return rest.trimStart { it.isWhitespace() || it in "|:–—-" }.ifBlank { name }
    }
    return name
}
