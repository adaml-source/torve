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

    override suspend fun installAddon(
        url: String,
        enabled: Boolean,
        priority: Int?,
        serverId: String?,
        syncedAt: Long?,
        installedFrom: String,
    ): InstalledAddon {
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
        val storedPriority = priority ?: (maxPriority + 1).toInt()
        val storedInstalledFrom = normalizeInstallSource(installedFrom)

        // Preserve any config_id already on disk. Install-flow paths (e.g. the
        // addon catalog "Install" button) don't know the Panda config_id; the
        // Panda onboarding VM persists it via setAddonConfigId immediately
        // after install. Re-installing must not wipe that pointer.
        val existingConfigId = database.torveQueries.getAddonByUrl(manifestUrl)
            .executeAsOneOrNull()?.config_id

        database.torveQueries.insertAddon(
            manifest_url = manifestUrl,
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            logo = manifest.logo,
            manifest_json = manifestJson,
            is_enabled = if (enabled) 1 else 0,
            priority = storedPriority.toLong(),
            installed_at = now,
            server_id = serverId,
            synced_at = syncedAt,
            installed_from = storedInstalledFrom,
            config_id = existingConfigId,
        )

        return InstalledAddon(
            manifestUrl = manifestUrl,
            manifest = manifest,
            isEnabled = enabled,
            priority = storedPriority,
            installedAt = now,
            serverId = serverId,
            syncedAt = syncedAt,
            installedFrom = storedInstalledFrom,
            configId = existingConfigId,
        )
    }

    override suspend fun setAddonConfigId(manifestUrl: String, configId: String?) {
        database.torveQueries.updateAddonConfigId(
            config_id = configId,
            manifest_url = manifestUrl,
        )
    }

    override suspend fun removeAddon(manifestUrl: String) {
        database.torveQueries.deleteAddon(manifestUrl)
    }

    override suspend fun getInstalledAddons(): List<InstalledAddon> {
        return database.torveQueries.getAllAddons().executeAsList().map(::mapRow)
    }

    override suspend fun getEnabledAddons(): List<InstalledAddon> {
        return database.torveQueries.getEnabledAddons().executeAsList().map(::mapRow)
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

    override suspend fun getAddon(manifestUrl: String): InstalledAddon? {
        return database.torveQueries.getAddonByUrl(manifestUrl).executeAsOneOrNull()?.let(::mapRow)
    }

    override suspend fun markAddonSynced(
        manifestUrl: String,
        serverId: String,
        syncedAt: Long?,
        installedFrom: String,
    ) {
        database.torveQueries.markAddonSynced(
            server_id = serverId,
            synced_at = syncedAt,
            installed_from = normalizeInstallSource(installedFrom),
            manifest_url = manifestUrl,
        )
    }

    override suspend fun syncRemoteState(
        manifestUrl: String,
        serverId: String,
        enabled: Boolean,
        priority: Int,
        syncedAt: Long,
        installedFrom: String,
    ) {
        database.torveQueries.syncAddonWithServer(
            server_id = serverId,
            synced_at = syncedAt,
            installed_from = normalizeInstallSource(installedFrom),
            is_enabled = if (enabled) 1 else 0,
            priority = priority.toLong(),
            manifest_url = manifestUrl,
        )
    }

    override suspend fun clearSyncMetadata() {
        database.torveQueries.clearAddonSyncMetadata()
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

    private fun mapRow(row: com.torve.db.Addon): InstalledAddon {
        val manifest = try {
            json.decodeFromString(AddonManifest.serializer(), row.manifest_json)
        } catch (_: Exception) {
            AddonManifest(id = row.id, name = row.name, version = row.version)
        }
        return InstalledAddon(
            manifestUrl = row.manifest_url,
            manifest = manifest,
            isEnabled = row.is_enabled == 1L,
            priority = row.priority.toInt(),
            installedAt = row.installed_at,
            serverId = row.server_id,
            syncedAt = row.synced_at,
            installedFrom = normalizeInstallSource(row.installed_from),
            configId = row.config_id,
        )
    }

    private fun normalizeInstallSource(installedFrom: String?): String {
        return when (installedFrom?.lowercase()) {
            "app" -> "app"
            "web" -> "web"
            "sync" -> "sync"
            else -> "app"
        }
    }
}
