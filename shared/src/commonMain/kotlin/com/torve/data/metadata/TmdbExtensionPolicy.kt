package com.torve.data.metadata

import com.torve.data.addon.ExtensionRuntimePolicy
import com.torve.domain.repository.PreferencesRepository

const val KEY_TMDB_CATALOGUE_ENABLED = "metadata_tmdb_catalogue_enabled"
const val KEY_TMDB_ENRICHMENT_ENABLED = "metadata_tmdb_enrichment_enabled"

/** Data providers remain optional; an explicit disabled selection permits no requests. */
data class TmdbExtensionSelection(
    val catalogue: Boolean = false,
    val enrichment: Boolean = false,
) {
    fun permits(endpoint: String): Boolean = catalogue || (enrichment && (
        endpoint.matches(Regex("^/(movie|tv|person|collection|list)/[1-9][0-9]*(/.*)?$")) ||
            endpoint.matches(Regex("^/find/tt[0-9]+$"))
        ))
}

suspend fun PreferencesRepository.tmdbExtensionSelection(): TmdbExtensionSelection =
    if (!ExtensionRuntimePolicy.optionalMetadataExtensions) TmdbExtensionSelection(true, true)
    else TmdbExtensionSelection(
        // Only an explicit user choice enables TMDB, including on older installations
        // which browsed under the previous implicit default without saving a choice.
        catalogue = getString(KEY_TMDB_CATALOGUE_ENABLED) == "true",
        enrichment = getString(KEY_TMDB_ENRICHMENT_ENABLED) == "true",
    )

class TmdbExtensionDisabledException : IllegalStateException(
    "Choose the TMDB catalogue or enable TMDB metadata enrichment in Extensions."
)
