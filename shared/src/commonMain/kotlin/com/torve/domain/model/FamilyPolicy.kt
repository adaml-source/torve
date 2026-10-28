package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AgeCertification(val country: String, val rating: String)

@Serializable
data class KidsProfileRules(
    val maximumAge: Int = 12,
    val ratingCountry: String = "US",
    val hideUnrated: Boolean = true,
    val allowedSources: Set<String> = emptySet(),
    val blockedTitles: Set<String> = emptySet(),
)

@Serializable
data class FamilyConfiguration(
    val enabled: Boolean = false,
    val accountId: String = "",
    val kids: Map<String, KidsProfileRules> = emptyMap(),
    val version: Long = 0,
)

/** Certifications are age guidance; review scores and genres never establish an age limit. */
object FamilyPolicy {
    fun titleKeys(item: MediaItem): Set<String> = buildSet {
        add("${item.type.name}:${item.id}")
        item.tmdbId?.let { add("${item.type.name}:$it") }
        item.imdbId?.let { add("${item.type.name}:$it") }
    }

    fun minimumAge(certification: AgeCertification): Int? {
        val rating = certification.rating.trim().uppercase().removePrefix("FSK ")
        return when (certification.country.uppercase()) {
            "DE" -> rating.toIntOrNull()?.takeIf { it in setOf(0, 6, 12, 16, 18) }
            "US" -> when (rating) {
                "G", "TV-G", "TV-Y" -> 0
                "TV-Y7", "TV-Y7-FV" -> 7
                "PG" -> 8
                "TV-PG" -> 10
                "PG-13" -> 13
                "TV-14" -> 14
                "R" -> 17
                "NC-17", "TV-MA" -> 18
                else -> null
            }
            "GB" -> when (rating) { "U" -> 0; "PG" -> 8; "12", "12A" -> 12; "15" -> 15; "18", "R18" -> 18; else -> null }
            "FR" -> when (rating) { "U", "TOUS PUBLICS", "TP" -> 0; "10" -> 10; "12" -> 12; "16" -> 16; "18" -> 18; else -> null }
            "AU" -> when (rating) { "G" -> 0; "PG" -> 8; "M", "MA15+" -> 15; "R18+", "X18+" -> 18; else -> null }
            else -> null
        }
    }

    fun allows(item: MediaItem, rules: KidsProfileRules, certificates: List<AgeCertification> = item.ageCertifications): Boolean {
        if (item.adult == true || item.isContentPlaceholder || item.isStubDetail) return false
        if (titleKeys(item).any { it in rules.blockedTitles }) return false
        val ages = certificates.filter { it.country.equals(rules.ratingCountry, true) }.mapNotNull(::minimumAge)
        // When releases disagree, use the most restrictive certificate for this country.
        val age = ages.maxOrNull() ?: return !rules.hideUnrated && item.adult == false
        return age <= rules.maximumAge
    }
}
