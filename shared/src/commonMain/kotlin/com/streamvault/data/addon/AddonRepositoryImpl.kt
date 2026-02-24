package com.streamvault.data.addon

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.AddonCatalog
import com.streamvault.domain.model.AddonExtra
import com.streamvault.domain.model.AddonManifest
import com.streamvault.domain.model.InstalledAddon
import com.streamvault.domain.repository.AddonRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

class AddonRepositoryImpl(
    private val database: StreamVaultDatabase,
    private val addonClient: StremioAddonClient,
    private val json: Json,
) : AddonRepository {

    override suspend fun installAddon(url: String): InstalledAddon {
        val baseUrl = url.trimEnd('/')
        val stremioManifest = addonClient.getManifest(baseUrl)
        val manifest = stremioManifest.toDomain()
        val manifestUrl = "$baseUrl/manifest.json"
        val now = Clock.System.now().toEpochMilliseconds()
        val manifestJson = json.encodeToString(AddonManifest.serializer(), manifest)

        val maxPriority = database.streamVaultQueries.getAllAddons().executeAsList()
            .maxOfOrNull { it.priority } ?: -1

        database.streamVaultQueries.insertAddon(
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
        database.streamVaultQueries.deleteAddon(manifestUrl)
    }

    override suspend fun getInstalledAddons(): List<InstalledAddon> {
        return database.streamVaultQueries.getAllAddons().executeAsList().map { row ->
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
        return database.streamVaultQueries.getEnabledAddons().executeAsList().map { row ->
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
        database.streamVaultQueries.updateAddonEnabled(
            is_enabled = if (enabled) 1 else 0,
            manifest_url = manifestUrl,
        )
    }

    override suspend fun reorderAddons(orderedUrls: List<String>) {
        orderedUrls.forEachIndexed { index, url ->
            database.streamVaultQueries.updateAddonPriority(
                priority = index.toLong(),
                manifest_url = url,
            )
        }
    }

    override suspend fun getManifest(url: String): AddonManifest {
        return addonClient.getManifest(url).toDomain()
    }

    private fun StremioManifest.toDomain(): AddonManifest {
        return AddonManifest(
            id = id,
            name = name,
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
}
