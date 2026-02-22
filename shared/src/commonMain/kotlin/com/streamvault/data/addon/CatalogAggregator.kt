package com.streamvault.data.addon

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.InstalledAddon
import com.streamvault.domain.model.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Aggregates catalogs from multiple Stremio addons in parallel.
 */
class CatalogAggregator(
    private val addonClient: StremioAddonClient,
) {
    /**
     * Fetch catalogs from all enabled addons. Returns merged shelves.
     */
    suspend fun fetchCatalogs(
        addons: List<InstalledAddon>,
        type: String = "movie",
    ): List<CatalogShelf> = coroutineScope {
        val enabledAddons = addons.filter { it.isEnabled }
        if (enabledAddons.isEmpty()) return@coroutineScope emptyList()

        enabledAddons.map { addon ->
            async {
                try {
                    withTimeout(10_000) {
                        val baseUrl = addon.manifestUrl
                            .removeSuffix("/manifest.json")
                            .removeSuffix("/")
                        val manifest = addon.manifest
                        val catalogs = manifest.catalogs.filter { it.type == type }

                        catalogs.mapNotNull { catalog ->
                            try {
                                val items = fetchCatalogItems(baseUrl, type, catalog.id)
                                if (items.isNotEmpty()) {
                                    CatalogShelf(
                                        id = "${manifest.id}-${catalog.id}",
                                        title = catalog.name ?: "${manifest.name} - ${catalog.id}",
                                        items = items,
                                    )
                                } else null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetchCatalogItems(
        baseUrl: String,
        type: String,
        catalogId: String,
    ): List<MediaItem> {
        // TODO: Implement catalog item fetching from Stremio addons
        // GET {baseUrl}/catalog/{type}/{catalogId}.json
        return emptyList()
    }
}
