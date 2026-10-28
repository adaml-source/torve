package com.torve.data.profile

import com.torve.data.auth.*
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.*
import com.torve.domain.model.KidsProfileRules
import com.torve.util.ioDispatcher
import kotlinx.coroutines.withContext

/** An explicit parent Save shares selected connections, not another profile's personal library. */
class FamilySourceProvisioner(private val database: TorveDatabase, private val ids: UserIdProvider,
    private val secrets: IntegrationSecretStore, private val family: FamilyControls) {
    suspend fun provision(targetProfile: String, rules: KidsProfileRules) = ids.withPersonalDataScope {
        family.requireParent()
        if (targetProfile == ids.currentProfileId()) return@withPersonalDataScope
        val target = profileDataUserId(ids.currentUserId(), targetProfile)
        val source = ids.currentDataUserId()
        if (rules.allowedSources.any { it.startsWith("jellyfin:") }) {
            check(secrets is ProfileScopedSecretStore) { "This device cannot share a Jellyfin connection yet." }
            secrets.copyJellyfinToProfile(targetProfile)
        }
        withContext(ioDispatcher) {
            ids.checkPersonalScope(); family.requireParent()
            val q = database.torveQueries
            database.transaction {
                rules.allowedSources.filter { it.startsWith("extension:") }.forEach {
                    q.copyFamilyAddon(target = target, source = source, addonId = it.removePrefix("extension:"))
                }
                if (rules.allowedSources.any { it.startsWith("jellyfin:") }) {
                    listOf("jellyfin_server_url", "jellyfin_selected_user_id").forEach { key ->
                        q.getPreference(source, key).executeAsOneOrNull()?.let { q.setPreference(target, key, it) }
                    }
                    q.setPreference(target, "app_feature_jellyfin_enabled", "true")
                }
                rules.allowedSources.filter { it.startsWith("channels:") }.forEach { key ->
                    val playlist = key.removePrefix("channels:").substringBefore(':')
                    val group = key.removePrefix("channels:$playlist:")
                    val generation = q.getPreference(source, "channel_active_generation_$playlist").executeAsOneOrNull()?.toLongOrNull()
                    if (generation != null) {
                        q.copyFamilyPlaylist(target, source, playlist)
                        q.copyFamilyLiveGroup(target, source, playlist, generation, group)
                        q.setPreference(target, "channel_active_generation_$playlist", generation.toString())
                        q.updateFamilyChannelCount(target, playlist, generation)
                    }
                }
            }
        }
    }
}
