package com.torve.platform

actual class NetworkMonitor {
    actual fun currentNetworkType(): NetworkType {
        // iOS: Always report WiFi as default — proper NWPathMonitor integration
        // would require platform-specific Swift bridge
        return NetworkType.WIFI
    }
}
