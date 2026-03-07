package com.torve.platform

import com.torve.domain.model.StreamQuality

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    UNKNOWN,
    NONE,
}

expect class NetworkMonitor {
    fun currentNetworkType(): NetworkType
}

/**
 * Returns the recommended max quality for the current network.
 * WiFi/Ethernet: user's configured max quality (no restriction).
 * Cellular: cap at 720p to save data.
 * None: lowest quality.
 */
fun NetworkMonitor.recommendedMaxQuality(userMaxQuality: StreamQuality): StreamQuality {
    return when (currentNetworkType()) {
        NetworkType.WIFI, NetworkType.ETHERNET -> userMaxQuality
        NetworkType.CELLULAR -> minOf(userMaxQuality, StreamQuality.HD_720P)
        NetworkType.UNKNOWN -> minOf(userMaxQuality, StreamQuality.FHD_1080P)
        NetworkType.NONE -> StreamQuality.SD_480P
    }
}

private fun minOf(a: StreamQuality, b: StreamQuality): StreamQuality {
    return if (a.ordinal <= b.ordinal) a else b
}
