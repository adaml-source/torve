package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogGroupPresentation { POSTERS, LANDSCAPE }

@Serializable
data class CatalogGroup(val id: String, val name: String, val catalogIds: List<String>,
    val pinned: Boolean = true, val presentation: CatalogGroupPresentation = CatalogGroupPresentation.POSTERS)

data class CatalogGroupSource(val id: String, val title: String, val items: List<MediaItem>)

fun mergeCatalogGroup(group: CatalogGroup, sources: List<CatalogGroupSource>): List<MediaItem> {
    val selected = group.catalogIds.mapNotNull { id -> sources.firstOrNull { it.id == id } }
    val seen = mutableSetOf<String>()
    // Interleave sources so a large first catalog does not bury all the others.
    return buildList {
        repeat(selected.maxOfOrNull { it.items.size } ?: 0) { index ->
            selected.forEach { source ->
                source.items.getOrNull(index)?.let { item ->
                    val identities = buildSet {
                        (item.tmdbId ?: item.id.extractTmdbIdOrNull())?.let { add("${item.type}:tmdb:$it") }
                        item.imdbId?.let { add("${item.type}:imdb:$it") }
                        add("${item.type}:raw:${item.id}")
                    }
                    val duplicate = identities.any { it in seen }
                    seen.addAll(identities)
                    if (!duplicate) add(item)
                }
            }
        }
    }
}
