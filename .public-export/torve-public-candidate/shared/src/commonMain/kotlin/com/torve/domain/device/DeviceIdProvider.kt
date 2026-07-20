package com.torve.domain.device

import com.torve.data.auth.DeviceRegistrationDto

interface DeviceIdProvider {
    fun getDeviceId(): String
    fun getDeviceName(): String = "Unknown Device"
    fun getDeviceType(): String = DeviceType.PHONE.wireValue
    fun getPlatform(): String = "android"
    fun getAppVersion(): String? = null

    /**
     * Hardware-stable device identifier that survives app reinstalls and data clears.
     *
     * - Android: raw `Settings.Secure.ANDROID_ID` (no prefix)
     * - iOS: `UIDevice.current.identifierForVendor?.uuidString`
     * - Desktop: persistent machine-specific UUID
     *
     * Returns `null` when the platform cannot provide a stable ID (e.g. emulator).
     * The backend uses this as the primary dedup key and falls back to
     * [getDeviceId] (installation_id) when absent.
     */
    fun getStableDeviceId(): String? = null

    fun getDeviceRegistration(): DeviceRegistrationDto = DeviceRegistrationDto(
        installation_id = getDeviceId(),
        device_name = getDeviceName(),
        device_type = getDeviceType(),
        platform = getPlatform(),
        app_version = getAppVersion(),
        stable_device_id = getStableDeviceId(),
    )
}
