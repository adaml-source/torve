package com.torve.domain.device

import com.torve.data.auth.DeviceRegistrationDto

interface DeviceIdProvider {
    fun getDeviceId(): String
    fun getDeviceName(): String = "Unknown Device"
    fun getDeviceType(): String = "phone"
    fun getPlatform(): String = "android"
    fun getAppVersion(): String? = null

    fun getDeviceRegistration(): DeviceRegistrationDto = DeviceRegistrationDto(
        installation_id = getDeviceId(),
        device_name = getDeviceName(),
        device_type = getDeviceType(),
        platform = getPlatform(),
        app_version = getAppVersion(),
    )
}
