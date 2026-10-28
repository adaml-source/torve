package com.torve.presentation.apple

import com.torve.data.device.ManagedDeviceDto
import com.torve.data.pairing.PairedDeviceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplePairingPeersTest {
    private fun device(id: String, installation: String, platform: String) = ManagedDeviceDto(
        id, device_name = "Living room", device_type = if (platform.endsWith("tv")) "tv" else "phone",
        platform = platform, is_active = true, installation_id = installation,
    )

    @Test fun outgoing_and_incoming_pairings_use_the_peer_real_installation() {
        val devices = listOf(device("phone", "ios-install", "ios"), device("tv", "fire-install", "android_tv"))
        val pairing = PairedDeviceDto(id = "pair", controllerDeviceId = "phone", targetDeviceId = "tv", status = "active")
        val phone = resolveApplePairingPeers(listOf(pairing), devices, "ios-install", null).single()
        assertEquals("tv", phone.resolvedDeviceId())
        assertEquals("fire-install", phone.resolvedInstallationId())
        assertEquals("Living room", phone.resolvedDeviceName())
        val tv = resolveApplePairingPeers(listOf(pairing), devices, "fire-install", null).single()
        assertEquals("phone", tv.resolvedDeviceId())
        assertEquals("ios-install", tv.resolvedInstallationId())
        assertEquals("pair", tv.resolvedPairingId())
    }

    @Test fun unrelated_revoked_and_unidentified_devices_never_become_companion_targets() {
        val phone = device("phone", "ios-install", "ios")
        val tv = device("tv", "fire-install", "android_tv")
        val pairing = PairedDeviceDto(id = "pair", controllerDeviceId = "phone", targetDeviceId = "tv", status = "active")
        assertTrue(resolveApplePairingPeers(listOf(pairing.copy(controllerDeviceId = "other")), listOf(phone, tv), "ios-install", null).isEmpty())
        assertTrue(resolveApplePairingPeers(listOf(pairing.copy(status = "revoked")), listOf(phone, tv), "ios-install", null).isEmpty())
        assertTrue(resolveApplePairingPeers(listOf(pairing), listOf(phone, tv.copy(is_active = false)), "ios-install", null).isEmpty())
        assertTrue(resolveApplePairingPeers(listOf(pairing), listOf(phone, tv.copy(installation_id = null)), "ios-install", null).isEmpty())
    }
}
