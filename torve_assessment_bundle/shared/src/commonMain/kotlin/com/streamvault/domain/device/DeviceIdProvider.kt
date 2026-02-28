package com.streamvault.domain.device

interface DeviceIdProvider {
    fun getDeviceId(): String
}
