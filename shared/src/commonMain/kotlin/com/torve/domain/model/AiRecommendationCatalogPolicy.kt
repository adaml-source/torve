package com.torve.domain.model

/** AI recommendation catalogues are retired. Other resources from the same
 * extension, ordinary recommendations and explicit AI Search remain available. */
object AiRecommendationCatalogPolicy {
    private val camelCaseBoundary = Regex("([a-z])([A-Z])")
    private val wordSeparator = Regex("[^a-z0-9]+")
    fun isRemoved(id: String, name: String): Boolean = listOf(id, name).any { value ->
        val words = value.replace(camelCaseBoundary, "$1 $2")
            .lowercase().split(wordSeparator).filter { it.isNotEmpty() }
        val recommendation = words.any { it.startsWith("recommend") || it == "recs" || it == "rec" }
        (recommendation && ("ai" in words || ("artificial" in words && "intelligence" in words))) ||
            words.any { it.startsWith("airecommend") || it == "airecs" }
    }
}

fun AddonManifest.withoutAiRecommendations(): AddonManifest = copy(
    catalogs = catalogs.filterNot { AiRecommendationCatalogPolicy.isRemoved(it.id, it.name.orEmpty()) },
)

fun List<CatalogShelf>.withoutAiRecommendations(): List<CatalogShelf> =
    filterNot { AiRecommendationCatalogPolicy.isRemoved(it.id, it.title) }
