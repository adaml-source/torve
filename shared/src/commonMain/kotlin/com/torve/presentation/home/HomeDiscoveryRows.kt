package com.torve.presentation.home

import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException

// Ranked catalogues retain their source order even when a title also appears
// in personal or earlier rows. Personal sync must not change what is trending.
internal val independentDiscoveryRows = setOf("trending-movies", "trending-tv", "popular-movies", "popular-tv", "top-rated")
internal val pagedDiscoveryRows = setOf("trending-movies", "trending-tv", "popular-movies", "popular-tv",
    "now-playing", "upcoming", "top-rated", "airing-today")
internal data class HomeDiscoveryPage(val items: List<MediaItem>, val hasMore: Boolean, val nextPage: Int? = null)
internal val homeDirectorDepartments = setOf("Directing", "Production", "Writing")

/** A preliminary cold-start result must never overwrite a visible, completed rail. */
internal fun HomeUiState.withInitialDiscoveryShelves(incoming: List<CatalogShelf>): HomeUiState {
    val existing = shelves.map { it.id }.toSet()
    val additions = incoming.filter { it.id !in existing && it.items.isNotEmpty() }
    val grown = shelves.map { shelf ->
        val more = incoming.firstOrNull { it.id == shelf.id } ?: return@map shelf
        // A growing snapshot must include the visible prefix. Refresh rankings
        // belong to the completed refresh, never to preliminary backfill.
        if (shelf.items.size < 20 && more.items.size > shelf.items.size &&
            more.items.take(shelf.items.size).map { it.stableKey() } == shelf.items.map { it.stableKey() })
            shelf.copy(items = more.items.take(20)) else shelf
    }
    if (additions.isEmpty() && grown == shelves) return this
    return copy(
        shelves = grown + additions,
        heroItem = heroItem ?: additions.firstOrNull()?.items?.firstOrNull(),
        isLoading = false,
        error = null,
    )
}

internal suspend fun fillHomePeopleRows(
    firstPage: List<PersonSummary>, actors: Boolean, directors: Boolean,
    maxPages: Int = 20,
    fetch: suspend (Int) -> List<PersonSummary>,
): List<PersonSummary> {
    val people = firstPage.distinctBy { it.id }.toMutableList()
    var page = 2
    while (page <= maxPages && ((actors && people.count { it.knownForDepartment == "Acting" } < 20) ||
        (directors && people.count { it.knownForDepartment in homeDirectorDepartments } < 20))) {
        val more = try { fetch(page++) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { break }
        if (more.isEmpty()) break
        val seen = people.map { it.id }.toSet()
        people += more.filter { it.id !in seen }
    }
    return people
}

internal fun <T> keepingAvailableRowMinimum(selected: List<T>, available: List<T>, key: (T) -> String): List<T> {
    val items = LinkedHashMap<String, T>()
    selected.forEach { item -> if (key(item) !in items) items[key(item)] = item }
    if (items.size < 20) for (item in available) {
        if (key(item) !in items) items[key(item)] = item
        if (items.size >= 20) break
    }
    return items.values.toList()
}

internal fun deduplicateDiscoveryRows(shelves: List<CatalogShelf>, seen: MutableSet<String>): List<CatalogShelf> =
    shelves.map { shelf ->
        val unique = shelf.items.dedupeByStableKey()
        if (shelf.id in independentDiscoveryRows) shelf.copy(items = unique)
        else shelf.copy(items = unique.filter { seen.add(it.stableKey()) })
    }

/** Keep empty row identities so filtering cannot prevent their own source from refilling them. */
internal fun discoveryRowsWithMissingSources(shelves: List<CatalogShelf>, configs: List<HomeSectionConfig>): List<CatalogShelf> {
    val existing = shelves.map { it.id }.toSet()
    val missing = configs.filter { it.enabled }.mapNotNull { config ->
        config.section.shelfId?.takeIf { it in pagedDiscoveryRows && it !in existing }?.let {
            CatalogShelf(id = it, title = config.section.defaultTitle, items = emptyList())
        }
    }
    return shelves + missing.distinctBy { it.id }
}

/** Target twenty without inventing entries, bypassing policy or dropping smaller valid results. */
internal suspend fun fillHomeDiscoveryRows(
    shelves: List<CatalogShelf>,
    globalSeen: MutableSet<String> = mutableSetOf(),
    deduplicate: Boolean = true,
    target: Int = 20,
    maxPage: Int = 20,
    filter: (List<MediaItem>) -> List<MediaItem> = { it },
    fetch: suspend (String, Int) -> HomeDiscoveryPage,
): List<CatalogShelf> = shelves.map { shelf ->
    if (shelf.id !in pagedDiscoveryRows) return@map shelf
    val independent = !deduplicate || shelf.id in independentDiscoveryRows
    val available = LinkedHashMap<String, MediaItem>()
    val selected = LinkedHashMap<String, MediaItem>()
    fun add(items: List<MediaItem>) {
        for (item in filter(items)) {
            val key = item.stableKey()
            if (key !in available) available[key] = item
            if ((independent || key !in globalSeen) && key !in selected) selected[key] = item
        }
    }
    add(shelf.items)
    var page = if (shelf.items.isEmpty()) 1 else 2
    while (selected.size < target && page <= maxPage) {
        val result = try { fetch(shelf.id, page) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { break }
        add(result.items)
        if (!result.hasMore) break
        page = result.nextPage ?: (page + 1)
    }
    // Prefer fresh unique titles; repeat across rows only when otherwise short.
    // Trending, Popular and Top Rated retain their independently ranked collections.
    if (selected.size < target) for ((key, item) in available) {
        if (key !in selected) selected[key] = item
        if (selected.size >= target) break
    }
    if (!independent) globalSeen.addAll(selected.keys)
    shelf.copy(items = selected.values.toList())
}
