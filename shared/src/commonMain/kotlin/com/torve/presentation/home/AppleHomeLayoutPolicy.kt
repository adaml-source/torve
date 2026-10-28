package com.torve.presentation.home

import com.torve.domain.model.*

/** A renderable row, with the same saved keys used by the Android Home editor. */
data class AppleHomeLayoutRow(
    val key: String,
    val title: String,
    val section: HomeSection? = null,
    val items: List<MediaItem> = emptyList(),
    val people: List<PersonSummary> = emptyList(),
    val presetId: String? = null,
    val seeAllSection: String? = null,
)

/** Includes hidden rows so the editor can enable them again. */
data class AppleHomeLayoutEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val section: HomeSection? = null,
    val presetId: String? = null,
)

object AppleHomeLayoutPolicy {
    fun catalogueTitle(shelf: CatalogShelf): String {
        val kind = shelf.items.map { it.type }.distinct().singleOrNull()
        val suffix = when (kind) {
            MediaType.MOVIE -> "Movies"
            MediaType.SERIES -> "TV shows"
            null -> return shelf.title
        }
        // Providers that already identify the media type keep their wording.
        val namedType = if (kind == MediaType.MOVIE) Regex("\\bmovies?\\b", RegexOption.IGNORE_CASE)
            else Regex("\\b(tv|shows?|series)\\b", RegexOption.IGNORE_CASE)
        return if (namedType.containsMatchIn(shelf.title)) shelf.title else "${shelf.title} · $suffix"
    }

    fun rows(
        state: HomeUiState,
        configs: List<HomeSectionConfig>,
        customSections: List<CustomSection>,
        layoutOrder: List<String>,
    ): List<AppleHomeLayoutRow> {
        val orderIndex = layoutOrder.withIndex().associate { it.value to it.index }
        data class OrderedRow(val row: AppleHomeLayoutRow, val order: Int, val insertion: Int)
        val rows = mutableListOf<OrderedRow>()
        val watchlist = state.watchlistItems.ifEmpty { state.watchlistShelf?.items.orEmpty() }
        fun add(row: AppleHomeLayoutRow, order: Int) {
            rows += OrderedRow(row, order, rows.size)
        }
        configs.distinctBy { it.section }.filter { it.enabled && it.section.configurable }.forEach { config ->
            val section = config.section
            val key = "section:${section.name}"
            val title = config.customTitle?.takeIf { it.isNotBlank() } ?: section.defaultTitle
            val catalog = section.shelfId?.let { id -> state.shelves.firstOrNull { it.id == id } }
            val items = when (section) {
                HomeSection.WATCHLIST -> watchlist
                HomeSection.WATCHLIST_MOVIES -> watchlist.filter { it.type == MediaType.MOVIE }
                HomeSection.WATCHLIST_TV -> watchlist.filter { it.type == MediaType.SERIES }
                HomeSection.RECOMMENDED -> state.recommendedItems.map { it.item }
                HomeSection.UPCOMING_SCHEDULE -> state.upcomingSchedule
                HomeSection.RECENTLY_WATCHED -> state.recentlyWatched
                HomeSection.HIDDEN_GEMS -> state.hiddenGemsShelf?.items.orEmpty()
                HomeSection.BECAUSE_YOU_WATCHED_MOVIES, HomeSection.BECAUSE_YOU_WATCHED_TV ->
                    state.becauseYouWatched.firstOrNull { it.id == section.shelfId }?.items.orEmpty()
                else -> catalog?.items.orEmpty()
            }
            val people = when (section) {
                HomeSection.ACTORS -> state.popularActors
                HomeSection.DIRECTORS -> state.popularDirectors
                else -> emptyList()
            }
            if (section == HomeSection.ADDON_SHELVES) {
                val visible = state.addonShelves.filter {
                    config.catalogueRows[it.id]?.enabled ?: (state.addonShelfVisibility[it.id] != false)
                }
                if (visible.isEmpty()) add(AppleHomeLayoutRow(key, title, section), config.order)
                visible.forEachIndexed { index, shelf ->
                    val options = config.catalogueRows[shelf.id]
                    add(AppleHomeLayoutRow("addon:${shelf.id}", options?.customTitle ?: catalogueTitle(shelf), items = shelf.items,
                        presetId = options?.presetId ?: config.presetId, seeAllSection = "shelf:${shelf.id}"), config.order + index)
                }
            } else if (section == HomeSection.MDBLIST_SHELVES && state.mdbListShelves.isNotEmpty()) {
                state.mdbListShelves.forEachIndexed { index, shelf ->
                    add(AppleHomeLayoutRow("mdblist:${shelf.id}", shelf.title, items = shelf.items,
                        presetId = config.presetId, seeAllSection = "shelf:${shelf.id}"), config.order + index)
                }
            } else {
                add(AppleHomeLayoutRow(key, title, section, items, people, config.presetId,
                    if (catalog != null) section.name else "shelf:$key"), config.order)
            }
        }
        customSections.distinctBy { it.id }.filter { it.enabled }.forEach { section ->
            add(AppleHomeLayoutRow("custom:${section.id}", section.title,
                items = state.customShelves[section.id].orEmpty(), presetId = section.presetId, seeAllSection = "custom:${section.id}"), section.order)
        }
        // No row cap: missing/new keys retain their configured order after saved keys.
        return rows.distinctBy { it.row.key }.sortedWith(
            compareBy<OrderedRow> { orderIndex[it.row.key]?.toLong() ?: (10_000L + it.order) }
                .thenBy { it.order }.thenBy { it.insertion },
        ).map { it.row }
    }

    fun editorRows(
        state: HomeUiState,
        configs: List<HomeSectionConfig>,
        customSections: List<CustomSection>,
        layoutOrder: List<String>,
    ): List<AppleHomeLayoutEntry> {
        val all = mutableListOf<Pair<AppleHomeLayoutEntry, Int>>()
        configs.filter { it.section.configurable }.distinctBy { it.section }.forEach { config ->
            all += AppleHomeLayoutEntry("section:${config.section.name}",
                config.customTitle ?: config.section.defaultTitle, "", config.enabled, config.section, config.presetId) to config.order
            if (config.section == HomeSection.ADDON_SHELVES) {
                state.addonShelves.distinctBy { it.id }.forEachIndexed { index, shelf ->
                    val options = config.catalogueRows[shelf.id]
                    val subtitle = when (shelf.items.firstOrNull()?.type) {
                        MediaType.MOVIE -> "Movies"
                        MediaType.SERIES -> "TV shows"
                        else -> "Catalogue"
                    }
                    all += AppleHomeLayoutEntry("addon:${shelf.id}", options?.customTitle ?: catalogueTitle(shelf), subtitle,
                        options?.enabled ?: (state.addonShelfVisibility[shelf.id] != false),
                        presetId = options?.presetId) to (config.order + index)
                }
            }
        }
        customSections.distinctBy { it.id }.forEach { custom ->
            all += AppleHomeLayoutEntry("custom:${custom.id}", custom.title, "Custom row", custom.enabled,
                presetId = custom.presetId) to custom.order
        }
        val orderIndex = layoutOrder.withIndex().associate { it.value to it.index }
        return all.sortedWith(compareBy<Pair<AppleHomeLayoutEntry, Int>> {
            orderIndex[it.first.key]?.toLong() ?: (10_000L + it.second)
        }.thenBy { it.second }).map { it.first }
    }

    /** Keep temporarily unavailable catalogues in the saved order while editing visible entries. */
    fun reorderedKeys(existingOrder: List<String>, editedKeys: List<String>): List<String> {
        val keys = editedKeys.distinct()
        val owned = keys.toSet()
        val next = keys.iterator()
        val result = existingOrder.distinct().map { if (it in owned) next.next() else it }.toMutableList()
        while (next.hasNext()) result += next.next()
        return result
    }
}
