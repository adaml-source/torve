package com.torve.data.addon

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.AddonCatalog
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaItem
import com.torve.domain.model.AiRecommendationCatalogPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Aggregates catalogs from multiple Stremio addons in parallel.
 */
class CatalogAggregator(
    private val addonClient: StremioAddonClient,
) {
    /** A single bounded Home batch; unrelated playback extensions are left untouched. */
    suspend fun fetchHomeCatalogs(plan: List<HomeCatalogRequest>): List<CatalogShelf> = withContext(Dispatchers.Default) {
        val requests = Semaphore(2)
        val ready = mutableMapOf<String, CatalogShelf>()
        val lock = kotlinx.coroutines.sync.Mutex()
        withTimeoutOrNull(4_000) {
            plan.take(8).map { request -> async {
                requests.withPermit {
                    val shelf = withTimeoutOrNull(2_000) {
                        val manifest = request.addon.manifest
                        val items = fetchCatalogItems(request.addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'),
                            request.catalog.type, request.catalog.id, limit = 20)
                            .map { it.copy(familySourceKey = "extension:${manifest.id}") }
                        if (items.isEmpty()) null else CatalogShelf(request.shelfId,
                            request.catalog.name?.takeIf { it.isNotBlank() } ?: "${manifest.name} - ${request.catalog.id}", items)
                    }
                    if (shelf != null) { lock.lock(); try { ready[shelf.id] = shelf } finally { lock.unlock() } }
                }
            } }.awaitAll()
        }
        plan.mapNotNull { ready[it.shelfId] }
    }
    /**
     * Fetch catalogs from all enabled addons. Returns merged shelves.
     */
    suspend fun fetchCatalogs(
        addons: List<InstalledAddon>,
        type: String = "movie",
    ): List<CatalogShelf> = withContext(Dispatchers.Default) {
        val requests = Semaphore(4)
        val enabledAddons = addons.filter { it.isEnabled }
        if (enabledAddons.isEmpty()) return@withContext emptyList()

        enabledAddons.flatMap { addon ->
            val baseUrl = addon.manifestUrl
                .removeSuffix("/manifest.json")
                .removeSuffix("/")
            val manifest = addon.manifest
            val catalogs = manifest.catalogs.filter { it.type == type }
                .filterNot { AiRecommendationCatalogPolicy.isRemoved(it.id, it.name.orEmpty()) }
                .filter { cat ->
                    // Skip search-only catalogs
                    val searchRequired = cat.extra.any { it.name == "search" && it.isRequired }
                    !searchRequired
                }

            catalogs.map { catalog ->
                async {
                    try {
                        requests.withPermit { withTimeout(10_000) {
                            val items = fetchCatalogItems(baseUrl, type, catalog.id).map { it.copy(familySourceKey = "extension:${manifest.id}") }
                            if (items.isNotEmpty()) {
                                CatalogShelf(
                                    id = manifest.catalogueShelfId(catalog),
                                    title = catalog.name?.ifEmpty { null }
                                        ?: "${manifest.name} - ${catalog.id}",
                                    items = items.take(20),
                                )
                            } else null
                        }
                        }
                    } catch (_: TimeoutCancellationException) { null
                    } catch (cancelled: CancellationException) { throw cancelled
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
        limit: Int = Int.MAX_VALUE,
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
            response.metas.take(limit).map { it.copy(type = it.type.ifBlank { type }).toMediaItem() }
        } catch (cancelled: CancellationException) { throw cancelled
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
    ): List<MediaItem> = withContext(Dispatchers.Default) {
        val requests = Semaphore(4)
        val enabledAddons = addons.filter { it.isEnabled }
        if (enabledAddons.isEmpty() || query.isBlank()) return@withContext emptyList()

        enabledAddons.flatMap { addon ->
            val baseUrl = addon.manifestUrl
                .removeSuffix("/manifest.json")
                .removeSuffix("/")
            val catalogs = addon.manifest.catalogs.filter { cat ->
                cat.type == type && cat.extra.any { it.name == "search" } &&
                    !AiRecommendationCatalogPolicy.isRemoved(cat.id, cat.name.orEmpty())
            }
            catalogs.map { catalog ->
                async {
                    try {
                        requests.withPermit { withTimeout(10_000) {
                            fetchCatalogItems(
                                baseUrl = baseUrl,
                                type = type,
                                catalogId = catalog.id,
                                search = query,
                            )
                        }
                        }
                    } catch (_: TimeoutCancellationException) { emptyList()
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }
}

/** Stremio catalogues are identified by (type, id), not id alone. Preserve old
 * keys for manifests whose IDs were already unique, so existing layouts stay put. */
fun AddonManifest.catalogueShelfId(catalog: AddonCatalog): String {
    val legacy = "$id-${catalog.id}"
    return if (catalogs.any { it.id == catalog.id && it.type != catalog.type }) {
        "$legacy::type=${catalog.type}"
    } else legacy
}

fun String.legacyAddonShelfId(): String = substringBeforeLast("::type=", this)
