package com.torve.domain.model

data class StreamingProviderCandidate(
    val id: Int,
    val name: String,
    val regions: Set<String> = emptySet(),
)

/**
 * Resolves a branded streaming-service card to the provider IDs currently
 * published by TMDB for the selected content region. Provider IDs can differ
 * by country and service tier, so the card's configured ID is only a fallback.
 */
fun resolveStreamingProviderIds(
    requestedName: String,
    configuredProviderId: Int,
    region: String,
    availableProviders: List<StreamingProviderCandidate>,
): List<Int> {
    val requested = normalizeStreamingProviderName(requestedName)
    val aliases = when (requested) {
        "primevideo" -> setOf("primevideo", "amazonprimevideo")
        "hbomax" -> setOf("hbomax", "max")
        "criterion" -> setOf("criterion", "criterionchannel")
        else -> setOf(requested)
    }

    val resolved = availableProviders
        .asSequence()
        .filter { candidate ->
            val normalized = normalizeStreamingProviderName(candidate.name)
            aliases.any { alias -> normalized == alias || normalized.startsWith(alias) }
        }
        .filterNot { candidate ->
            val normalized = normalizeStreamingProviderName(candidate.name)
            val isDirectCriterion = requested == "criterion" && normalized == "criterionchannel"
            !isDirectCriterion && (
                normalized.contains("channel") ||
                    normalized.endsWith("pictures")
                )
        }
        .map { it.id }
        .distinct()
        .toList()

    if (resolved.isNotEmpty()) return resolved

    val normalizedRegion = region.trim().uppercase()
    return when (normalizedRegion to requested) {
        "US" to "paramountplus" -> listOf(2303, 2616)
        "DE" to "paramountplus" -> listOf(531)
        else -> listOf(configuredProviderId)
    }
}

private fun normalizeStreamingProviderName(value: String): String =
    value.lowercase()
        .replace("+", "plus")
        .replace(Regex("[^a-z0-9]"), "")
