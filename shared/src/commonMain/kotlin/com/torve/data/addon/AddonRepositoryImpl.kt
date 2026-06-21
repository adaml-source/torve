package com.torve.data.addon

import com.torve.data.auth.UserIdProvider
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
    private val userIdProvider: UserIdProvider,
) : AddonRepository {

    private fun userId(): String = userIdProvider.currentUserId()
    private fun isSignedIn(): Boolean = userIdProvider.isSignedIn()

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

        val uid = userId()
        val maxPriority = database.torveQueries.getAllAddons(userId = uid).executeAsList()
            .maxOfOrNull { it.priority } ?: -1
        val storedPriority = priority ?: (maxPriority + 1).toInt()
        val storedInstalledFrom = normalizeInstallSource(installedFrom)

        // Preserve any config_id already on disk. Install-flow paths (e.g. the
        // addon catalog "Install" button) don't know the Panda config_id; the
        // Panda onboarding VM persists it via setAddonConfigId immediately
        // after install. Re-installing must not wipe that pointer.
        val existingConfigId = database.torveQueries.getAddonByUrl(
            userId = uid,
            manifestUrl = manifestUrl,
        ).executeAsOneOrNull()?.config_id

        database.torveQueries.insertAddon(
            user_id = uid,
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
        if (!isSignedIn()) return
        database.torveQueries.updateAddonConfigId(
            configId = configId,
            userId = userId(),
            manifestUrl = manifestUrl,
        )
    }

    override suspend fun removeAddon(manifestUrl: String) {
        if (!isSignedIn()) return
        database.torveQueries.deleteAddon(userId = userId(), manifestUrl = manifestUrl)
    }

    override suspend fun getInstalledAddons(): List<InstalledAddon> {
        if (!isSignedIn()) return emptyList()
        return database.torveQueries.getAllAddons(userId = userId()).executeAsList().map(::mapRow)
    }

    override suspend fun getEnabledAddons(): List<InstalledAddon> {
        if (!isSignedIn()) return emptyList()
        return database.torveQueries.getEnabledAddons(userId = userId()).executeAsList().map(::mapRow)
    }

    override suspend fun toggleAddon(manifestUrl: String, enabled: Boolean) {
        if (!isSignedIn()) return
        database.torveQueries.updateAddonEnabled(
            isEnabled = if (enabled) 1 else 0,
            userId = userId(),
            manifestUrl = manifestUrl,
        )
    }

    override suspend fun reorderAddons(orderedUrls: List<String>) {
        if (!isSignedIn()) return
        val uid = userId()
        orderedUrls.forEachIndexed { index, url ->
            database.torveQueries.updateAddonPriority(
                priority = index.toLong(),
                userId = uid,
                manifestUrl = url,
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
        if (!isSignedIn()) return null
        return database.torveQueries.getAddonByUrl(
            userId = userId(),
            manifestUrl = manifestUrl,
        ).executeAsOneOrNull()?.let(::mapRow)
    }

    override suspend fun markAddonSynced(
        manifestUrl: String,
        serverId: String,
        syncedAt: Long?,
        installedFrom: String,
    ) {
        if (!isSignedIn()) return
        database.torveQueries.markAddonSynced(
            serverId = serverId,
            syncedAt = syncedAt,
            installedFrom = normalizeInstallSource(installedFrom),
            userId = userId(),
            manifestUrl = manifestUrl,
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
        if (!isSignedIn()) return
        database.torveQueries.syncAddonWithServer(
            serverId = serverId,
            syncedAt = syncedAt,
            installedFrom = normalizeInstallSource(installedFrom),
            isEnabled = if (enabled) 1 else 0,
            priority = priority.toLong(),
            userId = userId(),
            manifestUrl = manifestUrl,
        )
    }

    override suspend fun clearSyncMetadata() {
        if (!isSignedIn()) return
        database.torveQueries.clearAddonSyncMetadata(userId = userId())
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
