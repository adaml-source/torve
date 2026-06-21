package com.torve.platform

import java.net.NetworkInterface

actual class NetworkMonitor {
    actual fun currentNetworkType(): NetworkType {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val activeInterfaces = interfaces.filter { network ->
            runCatching { network.isUp && !network.isLoopback && !network.isVirtual }.getOrDefault(false)
        }

        if (activeInterfaces.isEmpty()) {
            return NetworkType.NONE
        }

        activeInterfaces.forEach { network ->
            val name = buildString {
                append(network.name.orEmpty())
                append(' ')
                append(network.displayName.orEmpty())
            }.lowercase()

            when {
                "wlan" in name || "wifi" in name || "wi-fi" in name || "wireless" in name -> return NetworkType.WIFI
                "eth" in name || "ethernet" in name || "en0" in name || "lan" in name -> return NetworkType.ETHERNET
                "wwan" in name || "cell" in name || "mobile" in name || "rmnet" in name -> return NetworkType.CELLULAR
            }
        }

        return NetworkType.UNKNOWN
    }
}
