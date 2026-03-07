package com.torve.data.addon

import com.torve.db.TorveDatabase
import com.torve.domain.model.AddonCatalog
import com.torve.domain.model.AddonExtra
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import com.torve.domain.repository.AddonRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

class AddonRepositoryImpl(
    private val database: TorveDatabase,
    private val addonClient: StremioAddonClient,
    private val json: Json,
) : AddonRepository {

    override suspend fun installAddon(url: String): InstalledAddon {
        val baseUrl = url.trimEnd('/').removeSuffix("/manifest.json")
        val stremioManifest = addonClient.getManifest(baseUrl)
        val manifest = stremioManifest.toDomain(
            fallbackId = baseUrl,
            fallbackName = fallbackNameFromUrl(baseUrl),
        )
        val manifestUrl = "$baseUrl/manifest.json"
        val now = Clock.System.now().toEpochMilliseconds()
        val manifestJson = json.encodeToString(AddonManifest.serializer(), manifest)

        val maxPriority = database.torveQueries.getAllAddons().executeAsList()
            .maxOfOrNull { it.priority } ?: -1

        database.torveQueries.insertAddon(
            manifest_url = manifestUrl,
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            logo = manifest.logo,
            manifest_json = manifestJson,
            is_enabled = 1,
            priority = maxPriority + 1,
            installed_at = now,
        )

        return InstalledAddon(
            manifestUrl = manifestUrl,
            manifest = manifest,
            isEnabled = true,
            priority = (maxPriority + 1).toInt(),
            installedAt = now,
        )
    }

    override suspend fun removeAddon(manifestUrl: String) {
        database.torveQueries.deleteAddon(manifestUrl)
    }

    override suspend fun getInstalledAddons(): List<InstalledAddon> {
        return database.torveQueries.getAllAddons().executeAsList().map { row ->
            val manifest = try {
                json.decodeFromString(AddonManifest.serializer(), row.manifest_json)
            } catch (_: Exception) {
                AddonManifest(id = row.id, name = row.name, version = row.version)
            }
            InstalledAddon(
                manifestUrl = row.manifest_url,
                manifest = manifest,
                isEnabled = row.is_enabled == 1L,
                priority = row.priority.toInt(),
                installedAt = row.installed_at,
            )
        }
    }

    override suspend fun getEnabledAddons(): List<InstalledAddon> {
        return database.torveQueries.getEnabledAddons().executeAsList().map { row ->
            val manifest = try {
                json.decodeFromString(AddonManifest.serializer(), row.manifest_json)
            } catch (_: Exception) {
                AddonManifest(id = row.id, name = row.name, version = row.version)
            }
            InstalledAddon(
                manifestUrl = row.manifest_url,
                manifest = manifest,
                isEnabled = true,
                priority = row.priority.toInt(),
                installedAt = row.installed_at,
            )
        }
    }

    override suspend fun toggleAddon(manifestUrl: String, enabled: Boolean) {
        database.torveQueries.updateAddonEnabled(
            is_enabled = if (enabled) 1 else 0,
            manifest_url = manifestUrl,
        )
    }

    override suspend fun reorderAddons(orderedUrls: List<String>) {
        orderedUrls.forEachIndexed { index, url ->
            database.torveQueries.updateAddonPriority(
                priority = index.toLong(),
                manifest_url = url,
            )
        }
    }

    override suspend fun getManifest(url: String): AddonManifest {
        val baseUrl = url.trimEnd('/').removeSuffix("/manifest.json")
        return addonClient.getManifest(baseUrl).toDomain(
            fallbackId = baseUrl,
            fallbackName = fallbackNameFromUrl(baseUrl),
        )
    }

    private fun StremioManifest.toDomain(
        fallbackId: String,
        fallbackName: String,
    ): AddonManifest {
        val safeId = id.ifBlank { fallbackId }
        val safeName = name.ifBlank { fallbackName }
        return AddonManifest(
            id = safeId,
            name = safeName,
            version = version,
            description = description,
            logo = logo,
            resources = resources.map { it.name },
            types = types,
            catalogs = catalogs.map { cat ->
                AddonCatalog(
                    type = cat.type,
                    id = cat.id,
                    name = cat.name.ifEmpty { null },
                    extra = cat.extra.map { e ->
                        AddonExtra(name = e.name, isRequired = e.isRequired, options = e.options)
                    },
                    genres = cat.genres,
                )
            },
            idPrefixes = idPrefixes,
        )
    }

    private fun fallbackNameFromUrl(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").trim()
        return if (host.isNotBlank()) host else "Unknown Addon"
    }
}
