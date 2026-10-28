package com.torve.data.addon

import com.torve.domain.model.*

data class HomeCatalogRequest(val addon: InstalledAddon, val catalog: AddonCatalog) {
    val shelfId: String get() = addon.manifest.catalogueShelfId(catalog)
}

/** Home is a preview, not an eager download of every list an extension advertises. */
fun homeCatalogPlan(
    addons: List<InstalledAddon>,
    config: HomeSectionConfig?,
    visibility: Map<String, Boolean>,
    layout: List<String> = emptyList(),
): List<HomeCatalogRequest> {
    if (!(config?.enabled ?: HomeSection.ADDON_SHELVES.defaultEnabled)) return emptyList()
    val queues = addons.filter { it.isEnabled && it.policyFlags?.shelfEligible != false }
        .sortedBy { it.priority }.flatMap { addon ->
            listOf("movie", "series").map { type ->
                addon.manifest.catalogs.filter { catalog ->
                    val id = addon.manifest.catalogueShelfId(catalog)
                    val legacy = id.legacyAddonShelfId()
                    catalog.type == type &&
                        !AiRecommendationCatalogPolicy.isRemoved(catalog.id, catalog.name.orEmpty()) &&
                        catalog.extra.none { it.isRequired && it.name == "search" } &&
                        (config?.catalogueRows?.get(id)?.enabled ?: config?.catalogueRows?.get(legacy)?.enabled
                            ?: visibility[id] ?: visibility[legacy] ?: true)
                }.map { HomeCatalogRequest(addon, it) }.toMutableList()
            }
        }.filter { it.isNotEmpty() }
    // Round-robin gives each installed source and media type a preview before a large source fills Home.
    val ordered = buildList {
        while (queues.any { it.isNotEmpty() }) queues.forEach { queue ->
            if (queue.isNotEmpty()) add(queue.removeAt(0))
        }
    }.distinctBy { it.shelfId }
    val positions = layout.withIndex().associate { it.value.removePrefix("addon:") to it.index }
    return ordered.sortedBy { positions[it.shelfId] ?: Int.MAX_VALUE }.take(8)
}
