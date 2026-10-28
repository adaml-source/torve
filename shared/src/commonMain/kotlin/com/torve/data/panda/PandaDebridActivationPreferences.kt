package com.torve.data.panda

import com.torve.domain.model.DebridServiceType
import com.torve.domain.repository.PreferencesRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable device projection of Panda's account-backed provider activation.
 *
 * [pendingProviderIds] contains explicit offline mutations that have not yet
 * reached Panda. A normal server refresh replaces cached values, but never
 * overwrites those newer pending actions. Once the patch succeeds the pending
 * set is cleared and the backend projection is authoritative again.
 */
@Serializable
data class PandaDebridActivationSnapshot(
    val enabledByProvider: Map<String, Boolean> = emptyMap(),
    val pendingProviderIds: Set<String> = emptySet(),
    /** Explicit disconnects awaiting a newer server-side reconnect. */
    val disconnectedProviderIds: Set<String> = emptySet(),
    val serverUpdatedAt: String? = null,
) {
    fun isEnabled(providerId: String): Boolean =
        providerId !in disconnectedProviderIds && (enabledByProvider[providerId] ?: true)

    fun mergeServerState(
        serverState: Map<String, Boolean>,
        updatedAt: String?,
    ): PandaDebridActivationSnapshot {
        // The backend owns every synchronized provider. Preserve local values
        // only for explicit offline mutations that have not reached it yet.
        val merged = enabledByProvider.filterKeys { it in pendingProviderIds }.toMutableMap()
        serverState.forEach { (provider, enabled) ->
            if (provider !in pendingProviderIds) merged[provider] = enabled
        }
        val serverIsNewer = isNewerPandaServerRevision(serverUpdatedAt, updatedAt)
        val removedByNewerServer = if (serverIsNewer) {
            enabledByProvider.keys - serverState.keys - pendingProviderIds
        } else {
            emptySet()
        }
        return copy(
            enabledByProvider = merged,
            disconnectedProviderIds = disconnectedProviderIds
                .filterNotTo(hashSetOf()) { provider -> serverIsNewer && provider in serverState }
                .plus(removedByNewerServer),
            serverUpdatedAt = updatedAt ?: serverUpdatedAt,
        )
    }

    fun withExplicitMutation(providerId: String, enabled: Boolean): PandaDebridActivationSnapshot =
        copy(
            enabledByProvider = enabledByProvider + (providerId to enabled),
            pendingProviderIds = pendingProviderIds + providerId,
            disconnectedProviderIds = disconnectedProviderIds - providerId,
        )

    fun markSynchronized(providerIds: Set<String>, updatedAt: String? = serverUpdatedAt): PandaDebridActivationSnapshot =
        copy(
            pendingProviderIds = pendingProviderIds - providerIds,
            serverUpdatedAt = updatedAt,
        )

    fun withExplicitDisconnect(providerId: String): PandaDebridActivationSnapshot = copy(
        enabledByProvider = enabledByProvider - providerId,
        pendingProviderIds = pendingProviderIds - providerId,
        disconnectedProviderIds = disconnectedProviderIds + providerId,
    )
}

internal fun isNewerPandaServerRevision(current: String?, candidate: String?): Boolean {
    if (current.isNullOrBlank() || candidate.isNullOrBlank()) return false
    return runCatching {
        kotlinx.datetime.Instant.parse(candidate) > kotlinx.datetime.Instant.parse(current)
    }.getOrElse { candidate > current }
}

const val PANDA_DEBRID_ACTIVATION_PREF_KEY = "panda_debrid_activation_v1"

private val activationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

suspend fun PreferencesRepository.readPandaDebridActivationSnapshot(): PandaDebridActivationSnapshot {
    val raw = getString(PANDA_DEBRID_ACTIVATION_PREF_KEY) ?: return PandaDebridActivationSnapshot()
    return runCatching { activationJson.decodeFromString<PandaDebridActivationSnapshot>(raw) }
        .getOrDefault(PandaDebridActivationSnapshot())
}

suspend fun PreferencesRepository.writePandaDebridActivationSnapshot(snapshot: PandaDebridActivationSnapshot) {
    setString(PANDA_DEBRID_ACTIVATION_PREF_KEY, activationJson.encodeToString(snapshot))
}

fun DebridServiceType.pandaProviderId(): String = when (this) {
    DebridServiceType.REAL_DEBRID -> "realdebrid"
    DebridServiceType.ALL_DEBRID -> "alldebrid"
    DebridServiceType.PREMIUMIZE -> "premiumize"
    DebridServiceType.TORBOX -> "torbox"
}

fun PandaDebridActivationSnapshot.enabledCredentials(
    credentials: Map<DebridServiceType, String>,
): Map<DebridServiceType, String> = credentials.filter { (provider, credential) ->
    credential.isNotBlank() && isEnabled(provider.pandaProviderId())
}

fun PandaConfigPayload.debridActivationState(): Map<String, Boolean> = buildMap {
    debridConnections
        .filter { it.provider.isNotBlank() && it.provider != "none" }
        .forEach { put(it.provider, it.enabled) }
    if (debridService.isNotBlank() && debridService != "none" && debridService !in this) {
        // Legacy records had only the scalar provider. Migrate them enabled
        // once, then persist an explicit value with the next config write.
        put(debridService, true)
    }
}
