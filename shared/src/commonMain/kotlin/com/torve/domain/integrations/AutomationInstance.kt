package com.torve.domain.integrations

import kotlinx.serialization.Serializable

@Serializable
enum class AutomationServiceType {
    SONARR,
    RADARR,
    PROWLARR,
    BAZARR,
    TDARR,
}

@Serializable
enum class AutomationInstanceRole {
    STANDARD,
    UHD,
}

enum class AutomationPermission {
    MANAGE_SERIES,
    MANAGE_MOVIES,
    MANAGE_INDEXERS,
    MANAGE_SUBTITLES,
    MANAGE_TRANSCODING,
}

@Serializable
data class AutomationInstance(
    val id: String,
    val serviceType: AutomationServiceType,
    val name: String,
    val serverUrl: String,
    val role: AutomationInstanceRole = AutomationInstanceRole.STANDARD,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    /** Controls whether this connection is restored with the signed-in Torve account. */
    val storageMode: IntegrationStorageMode = IntegrationStorageMode.DEVICE_ONLY,
) {
    val permissions: Set<AutomationPermission>
        get() = when (serviceType) {
            AutomationServiceType.SONARR -> setOf(AutomationPermission.MANAGE_SERIES)
            AutomationServiceType.RADARR -> setOf(AutomationPermission.MANAGE_MOVIES)
            AutomationServiceType.PROWLARR -> setOf(AutomationPermission.MANAGE_INDEXERS)
            AutomationServiceType.BAZARR -> setOf(AutomationPermission.MANAGE_SUBTITLES)
            AutomationServiceType.TDARR -> setOf(AutomationPermission.MANAGE_TRANSCODING)
        }
}

interface AutomationInstanceRepository {
    suspend fun list(): List<AutomationInstance>

    /**
     * Saves non-secret metadata and, when supplied, the encrypted API key.
     * Account-mode keys are also mirrored into Torve's encrypted account bundle;
     * device-only keys never leave the device.
     */
    suspend fun save(instance: AutomationInstance, apiKey: String? = null)
    suspend fun remove(instanceId: String)
    suspend fun apiKey(instance: AutomationInstance): String?
}

fun normalizeAutomationServerUrl(value: String): String? {
    val input = value.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
    if (input.any { it.isWhitespace() || it.isISOControl() } || '?' in input || '#' in input) return null
    val schemeSeparator = input.indexOf("://")
    if (schemeSeparator <= 0) return null
    val scheme = input.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val remainder = input.substring(schemeSeparator + 3)
    val authority = remainder.substringBefore('/')
    if (authority.isBlank() || '@' in authority || authority.none { it.isLetterOrDigit() }) return null
    return "$scheme://$remainder"
}
