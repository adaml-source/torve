package com.torve.presentation.apple

import com.torve.data.device.ManagedDeviceDto
import com.torve.data.pairing.PairedDeviceDto

/** Join account pairing IDs to real installations; device names are never identities. */
internal fun resolveApplePairingPeers(
    pairings: List<PairedDeviceDto>,
    devices: List<ManagedDeviceDto>,
    selfInstallationId: String,
    selfServerDeviceId: String?,
): List<PairedDeviceDto> {
    val selfId = devices.firstOrNull { it.installation_id == selfInstallationId }?.id
        ?: selfServerDeviceId
    if (selfId.isNullOrBlank()) return emptyList()
    val active = devices.filter { it.is_active && it.revoked_at == null && it.removed_at == null }.associateBy { it.id }
    return pairings.mapNotNull { pair ->
        if (pair.resolvedPairingState() != "paired" || pair.revokedAt != null) return@mapNotNull null
        val peerId = when {
            pair.controllerDeviceId == selfId -> pair.targetDeviceId
            pair.targetDeviceId == selfId -> pair.controllerDeviceId
            pair.controllerDeviceId.isBlank() && pair.targetDeviceId.isBlank() -> pair.deviceId
            else -> return@mapNotNull null
        }
        val peer = active[peerId] ?: return@mapNotNull null
        val installation = peer.installation_id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (installation == selfInstallationId) return@mapNotNull null
        pair.copy(targetDeviceId = peer.id, installationId = installation,
            targetDeviceName = peer.display_name?.takeIf { it.isNotBlank() } ?: peer.device_name,
            targetDeviceType = peer.device_type, targetPlatform = peer.platform,
            lastSeenAt = peer.last_seen_at)
    }.distinctBy { it.resolvedDeviceId() }
}
