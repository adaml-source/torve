package com.torve.data.addon

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaItem
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

        enabledAddons.flatMap { addon ->
            val baseUrl = addon.manifestUrl
                .removeSuffix("/manifest.json")
                .removeSuffix("/")
            val manifest = addon.manifest
            val catalogs = manifest.catalogs.filter { it.type == type }
                .filter { cat ->
                    // Skip search-only catalogs
                    val searchRequired = cat.extra.any { it.name == "search" && it.isRequired }
                    !searchRequired
                }

            catalogs.map { catalog ->
                async {
                    try {
                        withTimeout(10_000) {
                            val items = fetchCatalogItems(baseUrl, type, catalog.id)
                            if (items.isNotEmpty()) {
                                CatalogShelf(
                                    id = "${manifest.id}-${catalog.id}",
                                    title = catalog.name?.ifEmpty { null }
                                        ?: "${manifest.name} - ${catalog.id}",
                                    items = items.take(20),
                                )
                            } else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchCatalogItems(
        baseUrl: String,
        type: String,
        catalogId: String,
        genre: String? = null,
        search: String? = null,
        skip: Int? = null,
    ): List<MediaItem> {
        return try {
            val response = addonClient.fetchCatalog(
                baseUrl = baseUrl,
                type = type,
                catalogId = catalogId,
                genre = genre,
                search = search,
                skip = skip,
            )
            response.metas.map { it.toMediaItem() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Search across all enabled addon catalogs that support search.
     */
    suspend fun searchAll(
        addons: List<InstalledAddon>,
        query: String,
        type: String = "movie",
    ): List<MediaItem> = coroutineScope {
        val enabledAddons = addons.filter { it.isEnabled }
        if (enabledAddons.isEmpty() || query.isBlank()) return@coroutineScope emptyList()

        enabledAddons.flatMap { addon ->
            val baseUrl = addon.manifestUrl
                .removeSuffix("/manifest.json")
                .removeSuffix("/")
            val catalogs = addon.manifest.catalogs.filter { cat ->
                cat.type == type && cat.extra.any { it.name == "search" }
            }
            catalogs.map { catalog ->
                async {
                    try {
                        withTimeout(10_000) {
                            fetchCatalogItems(
                                baseUrl = baseUrl,
                                type = type,
                                catalogId = catalog.id,
                                search = query,
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }
}
