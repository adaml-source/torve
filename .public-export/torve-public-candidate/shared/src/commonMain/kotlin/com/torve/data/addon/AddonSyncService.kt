package com.torve.data.addon

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AddonDto
import com.torve.data.account.AddonInstallRequest
import com.torve.data.account.AddonUpdateRequest
import com.torve.data.contentpolicy.AddonPolicyRepository
import com.torve.domain.model.InstalledAddon
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.platform.torveVerboseLog
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AddonSyncService(
    private val accessTokenProvider: suspend () -> String?,
    private val addonRepo: AddonRepository,
    private val accountSettingsApi: AccountSettingsApi,
    private val prefsRepo: PreferencesRepository,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
    private val json: Json,
    private val clock: Clock = Clock.System,
    private val addonPolicyRepository: AddonPolicyRepository? = null,
) {
    companion object {
        private const val KEY_LAST_SYNC_AT = "addon_sync_last_at"
        private const val KEY_PENDING_REMOVALS = "addon_sync_pending_removals"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val syncMutex = Mutex()

    suspend fun syncAfterSignIn() {
        sync(reason = "sign_in", force = true)
    }

    suspend fun syncIfStale(
        reason: String = "foreground",
        force: Boolean = false,
    ) {
        sync(reason = reason, force = force)
    }

    suspend fun onAddonInstalled(addon: InstalledAddon) {
        val token = accessTokenProvider() ?: return
        val success = runCatching {
            pushAddonToServer(token, addon, remoteId = addon.serverId)
        }.getOrElse { false }
        torveVerboseLog {
            "ADDON_SERVER_SAVE addon_id=${safeAddonRef(addon)} result=${if (success) "success" else "failure"}"
        }
        // If this was Panda, purge any stale Panda rows on the server so the next
        // sync cycle can't restore them locally.
        if (isPandaAddon(addon)) {
            runCatching { collapsePandaDuplicates(token, keepManifestUrl = addon.manifestUrl) }
        }
    }

    suspend fun onAddonRemoved(addon: InstalledAddon?) {
        if (addon == null) return
        val token = accessTokenProvider() ?: return
        rememberPendingRemoval(addon)
        val success = runCatching {
            deleteAddonFromServer(token, addon)
        }.getOrElse { false }
        torveVerboseLog {
            "ADDON_SERVER_REMOVE addon_id=${safeAddonRef(addon)} result=${if (success) "success" else "failure"}"
        }
    }

    suspend fun onAddonStateChanged(manifestUrl: String) {
        val token = accessTokenProvider() ?: return
        val addon = addonRepo.getAddon(normalizeManifestUrl(manifestUrl)) ?: return
        val success = runCatching {
            pushAddonToServer(token, addon, remoteId = addon.serverId)
        }.getOrElse { false }
        torveVerboseLog {
            "ADDON_SERVER_SAVE addon_id=${safeAddonRef(addon)} result=${if (success) "success" else "failure"}"
        }
    }

    suspend fun onAddonOrderChanged(orderedUrls: List<String>) {
        val token = accessTokenProvider() ?: return
        var allSucceeded = true
        orderedUrls.forEach { url ->
            val addon = addonRepo.getAddon(normalizeManifestUrl(url)) ?: return@forEach
            val success = runCatching {
                pushAddonToServer(token, addon, remoteId = addon.serverId)
            }.getOrElse { false }
            allSucceeded = allSucceeded && success
        }
        torveVerboseLog {
            "ADDON_SERVER_SAVE addon_id=reorder result=${if (allSucceeded) "success" else "failure"}"
        }
    }

    suspend fun clearSyncStateOnSignOut() {
        addonRepo.clearSyncMetadata()
        prefsRepo.remove(KEY_LAST_SYNC_AT)
        prefsRepo.remove(KEY_PENDING_REMOVALS)
        addonPolicyRepository?.clear()
        settingsRefreshNotifier.notifyRefresh(now())
    }

    private suspend fun sync(
        reason: String,
        force: Boolean,
    ) {
        val token = accessTokenProvider() ?: return
        val startedAt = now()
        if (!force && !isStale(startedAt)) return

        syncMutex.withLock {
            val nowMs = now()
            if (!force && !isStale(nowMs)) return

            try {
                val fetchedServerAddons = accountSettingsApi.getAddons(token)
                // Collapse Panda duplicates on the already-fetched server snapshot BEFORE
                // computing the merge plan. This avoids an extra unconditional getAddons()
                // call on every sync while still preventing stale Panda rows from being
                // re-installed locally.
                val serverAddons = collapsePandaDuplicates(
                    token = token,
                    serverAddons = fetchedServerAddons,
                    keepManifestUrl = null,
                )
                addonPolicyRepository?.updateFromServer(serverAddons)
                torveVerboseLog { "ADDON_SYNC_STARTED count=${serverAddons.size} reason=$reason" }

                val pendingRemovals = loadPendingRemovals().toMutableList()
                val serverByUrl = serverAddons.associateBy { normalizeManifestUrl(it.manifestUrl) }.toMutableMap()
                reconcilePendingRemovals(token, pendingRemovals, serverByUrl)

                val localBefore = addonRepo.getInstalledAddons()
                val localByUrl = localBefore.associateBy { normalizeManifestUrl(it.manifestUrl) }.toMutableMap()

                var localChanged = false
                var installedFromServer = 0
                var pushedToServer = 0

                serverByUrl.forEach { (manifestUrl, remote) ->
                    val local = localByUrl.remove(manifestUrl)
                    if (local == null) {
                        runCatching {
                            addonRepo.installAddon(
                                url = manifestUrl,
                                enabled = remote.isEnabled,
                                priority = remote.sortOrder,
                                serverId = remote.id,
                                syncedAt = nowMs,
                                installedFrom = normalizeInstallSource(remote.installedFrom),
                            )
                        }.onSuccess {
                            localChanged = true
                            installedFromServer++
                            torveVerboseLog {
                                "ADDON_SYNC_INSTALLED_FROM_SERVER addon_id=${safeAddonRef(remote)}"
                            }
                        }.onFailure { error ->
                            torveVerboseLog {
                                "ADDON_SYNC_FAILED reason=install_${safeAddonRef(remote)}_${error.message ?: "unknown"}"
                            }
                        }
                        return@forEach
                    }

                    if (local.syncedAt == null) {
                        val pushed = runCatching {
                            pushAddonToServer(token, local, remoteId = remote.id)
                        }.getOrElse { false }
                        if (pushed) {
                            pushedToServer++
                            torveVerboseLog {
                                "ADDON_SYNC_PUSHED_TO_SERVER addon_id=${safeAddonRef(local)}"
                            }
                        }
                        return@forEach
                    }

                    val installSource = normalizeInstallSource(remote.installedFrom)
                    if (
                        local.serverId != remote.id ||
                        local.isEnabled != remote.isEnabled ||
                        local.priority != remote.sortOrder ||
                        local.installedFrom != installSource
                    ) {
                        addonRepo.syncRemoteState(
                            manifestUrl = manifestUrl,
                            serverId = remote.id,
                            enabled = remote.isEnabled,
                            priority = remote.sortOrder,
                            syncedAt = nowMs,
                            installedFrom = installSource,
                        )
                        localChanged = true
                    }
                }

                localByUrl.values.forEach { local ->
                    if (local.serverId != null) {
                        addonRepo.removeAddon(local.manifestUrl)
                        localChanged = true
                        return@forEach
                    }

                    val pushed = runCatching {
                        pushAddonToServer(token, local, remoteId = null)
                    }.getOrElse { false }
                    if (pushed) {
                        pushedToServer++
                        torveVerboseLog {
                            "ADDON_SYNC_PUSHED_TO_SERVER addon_id=${safeAddonRef(local)}"
                        }
                    }
                }

                prefsRepo.setString(KEY_LAST_SYNC_AT, nowMs.toString())
                persistPendingRemovals(pendingRemovals)
                if (localChanged) {
                    settingsRefreshNotifier.notifyRefresh(nowMs)
                }
                torveVerboseLog {
                    "ADDON_SYNC_COMPLETED local=${localBefore.size} server=${serverAddons.size} new_from_server=$installedFromServer pushed=$pushedToServer"
                }
            } catch (error: Exception) {
                torveVerboseLog {
                    "ADDON_SYNC_FAILED reason=${error.message ?: error::class.simpleName ?: "unknown"}"
                }
            }
        }
    }

    private suspend fun reconcilePendingRemovals(
        token: String,
        pendingRemovals: MutableList<PendingAddonRemoval>,
        serverByUrl: MutableMap<String, AddonDto>,
    ) {
        val iterator = pendingRemovals.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val manifestUrl = normalizeManifestUrl(pending.manifestUrl)
            val remote = serverByUrl[manifestUrl]
            if (remote == null) {
                iterator.remove()
                continue
            }
            val removed = runCatching {
                accountSettingsApi.removeAddon(token, remote.id)
                true
            }.getOrElse { false }
            if (removed) {
                serverByUrl.remove(manifestUrl)
                iterator.remove()
            }
        }
    }

    private suspend fun deleteAddonFromServer(
        token: String,
        addon: InstalledAddon,
    ): Boolean {
        val remoteId = addon.serverId ?: accountSettingsApi.getAddons(token)
            .firstOrNull { normalizeManifestUrl(it.manifestUrl) == normalizeManifestUrl(addon.manifestUrl) }
            ?.id
            ?: return false
        return try {
            accountSettingsApi.removeAddon(token, remoteId)
            forgetPendingRemoval(addon.manifestUrl)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun pushAddonToServer(
        token: String,
        addon: InstalledAddon,
        remoteId: String?,
    ): Boolean {
        return try {
            if (!remoteId.isNullOrBlank()) {
                val updated = accountSettingsApi.updateAddon(token, remoteId, addon.toUpdateRequest())
                addonRepo.syncRemoteState(
                    manifestUrl = addon.manifestUrl,
                    serverId = updated.id,
                    enabled = updated.isEnabled,
                    priority = updated.sortOrder,
                    syncedAt = now(),
                    installedFrom = normalizeInstallSource(updated.installedFrom),
                )
                return true
            }

            val saved = accountSettingsApi.installAddon(token, addon.toInstallRequest())
            val updated = runCatching {
                accountSettingsApi.updateAddon(token, saved.id, addon.toUpdateRequest())
            }.getOrNull()

            if (updated == null) {
                addonRepo.markAddonSynced(
                    manifestUrl = addon.manifestUrl,
                    serverId = saved.id,
                    syncedAt = null,
                    installedFrom = normalizeInstallSource(saved.installedFrom),
                )
            } else {
                addonRepo.syncRemoteState(
                    manifestUrl = addon.manifestUrl,
                    serverId = updated.id,
                    enabled = updated.isEnabled,
                    priority = updated.sortOrder,
                    syncedAt = now(),
                    installedFrom = normalizeInstallSource(updated.installedFrom),
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun rememberPendingRemoval(addon: InstalledAddon) {
        val removals = loadPendingRemovals().toMutableList()
        val normalizedUrl = normalizeManifestUrl(addon.manifestUrl)
        removals.removeAll { normalizeManifestUrl(it.manifestUrl) == normalizedUrl }
        removals += PendingAddonRemoval(
            manifestUrl = normalizedUrl,
            serverId = addon.serverId,
            addonId = addon.manifest.id,
        )
        persistPendingRemovals(removals)
    }

    private suspend fun forgetPendingRemoval(manifestUrl: String) {
        val normalizedUrl = normalizeManifestUrl(manifestUrl)
        val removals = loadPendingRemovals()
            .filterNot { normalizeManifestUrl(it.manifestUrl) == normalizedUrl }
        persistPendingRemovals(removals)
    }

    private suspend fun loadPendingRemovals(): List<PendingAddonRemoval> {
        val raw = prefsRepo.getString(KEY_PENDING_REMOVALS) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingAddonRemoval>>(raw)
        }.getOrElse { emptyList() }
    }

    private suspend fun persistPendingRemovals(removals: List<PendingAddonRemoval>) {
        if (removals.isEmpty()) {
            prefsRepo.remove(KEY_PENDING_REMOVALS)
        } else {
            prefsRepo.setString(KEY_PENDING_REMOVALS, json.encodeToString(removals))
        }
    }

    private suspend fun isStale(nowMs: Long): Boolean {
        val lastSyncAt = prefsRepo.getString(KEY_LAST_SYNC_AT)?.toLongOrNull() ?: return true
        return nowMs - lastSyncAt >= SYNC_INTERVAL_MS
    }

    /**
     * Local-first Panda dedupe. Scans the installed-addons table and, if more than
     * one Panda row exists, removes the older ones locally. Picks the winner as the
     * row with the highest priority (sort_order) or falls back to the most recently
     * installed (latest manifest URL alphabetically as a proxy for newer config id).
     *
     * Call this when the user opens the addon catalog to guarantee a clean view
     * regardless of server sync state.
     */
    suspend fun collapseLocalPandaDuplicates() {
        val locals = runCatching { addonRepo.getInstalledAddons() }.getOrNull().orEmpty()
        val pandas = locals.filter { isPandaAddon(it) }
        if (pandas.size <= 1) return
        // Prefer a row that has a configured URL (contains '/{config_id}/manifest.json')
        // over the base manifest, then fall back to most-recently-synced.
        val keep = pandas
            .sortedWith(
                compareByDescending<com.torve.domain.model.InstalledAddon> {
                    // Configured URLs have an extra path segment after the host; the
                    // base "panda.torve.app/manifest.json" does not. Prefer configured.
                    it.manifestUrl.removePrefix("https://").removePrefix("http://")
                        .removeSuffix("/manifest.json")
                        .count { ch -> ch == '/' } > 0
                }.thenByDescending { it.syncedAt ?: 0L }
            )
            .first()
        val keepUrl = normalizeManifestUrl(keep.manifestUrl)
        val token = accessTokenProvider()
        pandas.filter { normalizeManifestUrl(it.manifestUrl) != keepUrl }.forEach { stale ->
            runCatching { addonRepo.removeAddon(stale.manifestUrl) }
            // Best-effort backend cleanup too.
            if (token != null && stale.serverId != null) {
                runCatching { accountSettingsApi.removeAddon(token, stale.serverId!!) }
            }
        }
    }

    /**
     * Remove all-but-one Panda addon rows from the server.
     *
     * When [keepManifestUrl] is non-null, that URL is preserved and every other
     * Panda row is deleted. When null, the most recently updated Panda row wins.
     *
     * Panda identity is detected by addon id ("com.torve.panda") or by host
     * (anything on panda.torve.app). Best-effort — network failures are ignored
     * so sync keeps running.
     */
    private suspend fun collapsePandaDuplicates(token: String, keepManifestUrl: String?) {
        val serverAddons = runCatching { accountSettingsApi.getAddons(token) }.getOrNull().orEmpty()
        collapsePandaDuplicates(
            token = token,
            serverAddons = serverAddons,
            keepManifestUrl = keepManifestUrl,
        )
    }

    private suspend fun collapsePandaDuplicates(
        token: String,
        serverAddons: List<AddonDto>,
        keepManifestUrl: String?,
    ): List<AddonDto> {
        val pandaAddons = serverAddons.filter { isPandaServerAddon(it) }
        if (pandaAddons.size <= 1) return serverAddons

        val normalizedKeep = keepManifestUrl?.let { normalizeManifestUrl(it) }
        val keep = when {
            normalizedKeep != null -> pandaAddons.firstOrNull {
                normalizeManifestUrl(it.manifestUrl) == normalizedKeep
            } ?: pandaAddons.maxByOrNull { it.updatedAt }
            else -> pandaAddons.maxByOrNull { it.updatedAt }
        } ?: return serverAddons

        val removedIds = mutableSetOf<String>()
        pandaAddons.filter { it.id != keep.id }.forEach { stale ->
            val removed = runCatching {
                accountSettingsApi.removeAddon(token, stale.id)
                // Also drop any local row pointing at the stale URL so the next merge
                // pass doesn't try to push it back.
                addonRepo.removeAddon(stale.manifestUrl)
            }.isSuccess
            if (removed) {
                removedIds += stale.id
            }
            torveVerboseLog {
                "ADDON_PANDA_DEDUP removed_id=${stale.id} kept_id=${keep.id}"
            }
        }
        return serverAddons.filterNot { it.id in removedIds }
    }

    private fun isPandaAddon(addon: InstalledAddon): Boolean {
        return addon.manifest.id == "com.torve.panda" ||
            addon.manifestUrl.contains("panda.torve.app")
    }

    private fun isPandaServerAddon(addon: AddonDto): Boolean {
        return addon.addonId == "com.torve.panda" ||
            addon.manifestUrl.contains("panda.torve.app")
    }

    private fun normalizeManifestUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val base = trimmed.removeSuffix("/manifest.json")
        return "$base/manifest.json"
    }

    private fun normalizeInstallSource(source: String?): String {
        return when (source?.lowercase()) {
            "app" -> "app"
            "web" -> "web"
            "sync" -> "sync"
            else -> "sync"
        }
    }

    private fun safeAddonRef(addon: InstalledAddon): String = safeAddonRef(addon.manifest.id, addon.manifestUrl)

    private fun safeAddonRef(addon: AddonDto): String = safeAddonRef(addon.addonId, addon.manifestUrl)

    private fun safeAddonRef(addonId: String?, manifestUrl: String): String {
        val safeId = addonId?.trim().orEmpty()
        if (safeId.isNotEmpty()) return safeId
        val host = manifestUrl.substringAfter("://", manifestUrl).substringBefore("/").trim()
        return if (host.isNotEmpty()) host else "unknown"
    }

    private fun InstalledAddon.toInstallRequest(): AddonInstallRequest {
        val resources = manifest.resources.toSet()
        return AddonInstallRequest(
            manifestUrl = normalizeManifestUrl(manifestUrl),
            addonId = manifest.id,
            name = manifest.name,
            description = manifest.description.ifBlank { null },
            version = manifest.version,
            hasCatalog = "catalog" in resources,
            hasStreams = "stream" in resources || resources.isEmpty(),
            installedFrom = when (installedFrom) {
                "web" -> "web"
                "app" -> "app"
                else -> "app"
            },
        )
    }

    private fun InstalledAddon.toUpdateRequest(): AddonUpdateRequest {
        val resources = manifest.resources.toSet()
        return AddonUpdateRequest(
            addonId = manifest.id,
            name = manifest.name,
            description = manifest.description.ifBlank { null },
            version = manifest.version,
            hasCatalog = "catalog" in resources,
            hasStreams = "stream" in resources || resources.isEmpty(),
            isEnabled = isEnabled,
            sortOrder = priority,
            installedFrom = when (installedFrom) {
                "web" -> "web"
                "app" -> "app"
                else -> "sync"
            },
        )
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()
}

@Serializable
private data class PendingAddonRemoval(
    val manifestUrl: String,
    val serverId: String? = null,
    val addonId: String? = null,
)
