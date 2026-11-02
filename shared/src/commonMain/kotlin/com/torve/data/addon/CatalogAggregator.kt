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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock

/**
 * Aggregates catalogs from multiple Stremio addons in parallel.
 */
class CatalogAggregator(
    private val addonClient: StremioAddonClient,
) {
    /** Continue the installed catalogue itself, never a global discovery feed. */
    suspend fun shelfPage(addons: List<InstalledAddon>, shelfId: String, offset: Int): AddonShelfPage {
        require(offset >= 0)
        val sources = addons.filter { it.isEnabled && it.policyFlags?.shelfEligible != false }.flatMap { addon ->
            addon.manifest.catalogs.filter { addon.catalogueShelfId(it) == shelfId }
                .map { addon to it }
        }
        require(sources.size == 1) { "Catalogue is no longer available" }
        val (addon, catalog) = sources.single()
        val paged = catalog.extra.any { it.name == "skip" }
        val response = addonClient.fetchCatalog(addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'),
            catalog.type, catalog.id, skip = offset.takeIf { paged && it > 0 }, failOnError = true)
        val rows = if (paged) response.metas else response.metas.drop(offset)
        val items = rows.map { it.copy(type = it.type.ifBlank { catalog.type }).toCatalogueMediaItem(addon.manifest.id)
            .copy(familySourceKey = "extension:${addon.manifest.id}", metadataSourceKey = com.torve.domain.player.MediaIdentityFactory.stableHash(addon.manifestUrl)) }
        return AddonShelfPage(items, offset + rows.size, paged && rows.isNotEmpty())
    }
    /** Publish ready rows without waiting for unrelated catalogues. Network concurrency stays bounded. */
    suspend fun fetchHomeCatalogs(plan: List<HomeCatalogRequest>,
        onUpdate: suspend (List<CatalogShelf>) -> Unit = {},
    ): List<CatalogShelf> = withContext(Dispatchers.Default) {
        val extensionOnly = ExtensionRuntimePolicy.userMetadataOnly
        val selected = if (extensionOnly) plan else plan.take(8)
        val requests = Semaphore(2)
        val ready = mutableMapOf<String, CatalogShelf>()
        val lock = kotlinx.coroutines.sync.Mutex()
        suspend fun loadSelected() = coroutineScope {
            selected.map { request -> async {
                requests.withPermit {
                    val shelf = withTimeoutOrNull(if (extensionOnly) 15_000 else 2_000) {
                        val manifest = request.addon.manifest
                        val items = fetchCatalogItems(request.addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'),
                            request.catalog.type, request.catalog.id, limit = 20, manifestId = manifest.id)
                            .map { it.copy(familySourceKey = "extension:${manifest.id}", metadataSourceKey = com.torve.domain.player.MediaIdentityFactory.stableHash(request.addon.manifestUrl)) }
                        if (items.isEmpty()) null else CatalogShelf(request.shelfId,
                            request.catalog.name?.takeIf { it.isNotBlank() } ?: "${manifest.name} - ${request.catalog.id}", items)
                    }
                    if (shelf != null) lock.withLock {
                        ready[shelf.id] = shelf
                        onUpdate(selected.mapNotNull { ready[it.shelfId] })
                    }
                }
            } }.awaitAll()
        }
        if (extensionOnly) loadSelected() else withTimeoutOrNull(4_000) { loadSelected() }
        selected.mapNotNull { ready[it.shelfId] }
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
                            val items = fetchCatalogItems(baseUrl, type, catalog.id, manifestId = manifest.id).map { it.copy(familySourceKey = "extension:${manifest.id}", metadataSourceKey = com.torve.domain.player.MediaIdentityFactory.stableHash(addon.manifestUrl)) }
                            if (items.isNotEmpty()) {
                                CatalogShelf(
                                    id = addon.catalogueShelfId(catalog),
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
        manifestId: String = "",
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
            response.metas.take(limit).map { it.copy(type = it.type.ifBlank { type }).toCatalogueMediaItem(manifestId) }
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
                                manifestId = addon.manifest.id,
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

data class AddonShelfPage(val items: List<MediaItem>, val nextOffset: Int, val hasMore: Boolean)

/** Stremio catalogues are identified by (type, id), not id alone. Preserve old
 * keys for manifests whose IDs were already unique, so existing layouts stay put. */
fun AddonManifest.catalogueShelfId(catalog: AddonCatalog): String {
    val legacy = "$id-${catalog.id}"
    return if (catalogs.any { it.id == catalog.id && it.type != catalog.type }) {
        "$legacy::type=${catalog.type}"
    } else legacy
}

/** Configured instances can share a manifest ID (for example German and English). */
fun InstalledAddon.catalogueShelfId(catalog: AddonCatalog): String {
    val legacy = manifest.catalogueShelfId(catalog)
    if (!ExtensionRuntimePolicy.userMetadataOnly) return legacy
    return legacy + "::source=" + com.torve.domain.player.MediaIdentityFactory.stableHash(manifestUrl)
}

fun String.legacyAddonShelfId(): String = substringBefore("::source=").substringBeforeLast("::type=", substringBefore("::source="))
