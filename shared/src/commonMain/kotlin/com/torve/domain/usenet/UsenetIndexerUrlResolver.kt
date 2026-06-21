package com.torve.domain.usenet

/**
 * Maps Panda's `nzbIndexers[i].type` value (the registry key from
 * `/api/v1/schema`) to the actual public Newznab base URL. Panda
 * stores only the type for built-in indexers and uses the `url` field
 * only for `type=custom`, so consumers of the indexer credentials have
 * to reconstitute the URL from the type alone.
 *
 * Keep this in sync with the indexer list Panda's schema accepts —
 * currently `nzbgeek`, `scenenzbs`, `dognzb`, `nzbplanet`, `custom`.
 */
object UsenetIndexerUrlResolver {

    private val builtIns: Map<String, String> = mapOf(
        "scenenzbs" to "https://scenenzbs.com",
        "nzbgeek" to "https://api.nzbgeek.info",
        "dognzb" to "https://api.dognzb.cr",
        "nzbplanet" to "https://nzbplanet.net",
    )

    fun resolve(type: String, customUrl: String): String = when {
        type.equals("custom", ignoreCase = true) -> customUrl.trim()
        else -> builtIns[type.lowercase()].orEmpty()
    }
}
