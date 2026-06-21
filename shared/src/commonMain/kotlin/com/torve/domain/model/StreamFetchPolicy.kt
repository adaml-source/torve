package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class StreamFetchPolicy(
    val label: String,
    val addonTimeoutMs: Long,
    val retryDelayMs: Long,
    val retryCount: Int,
) {
    FULL(
        label = "Full",
        addonTimeoutMs = 10_000,
        retryDelayMs = 3_000,
        retryCount = 1,
    ),
    PLAYBACK_STARTUP(
        label = "Playback Startup",
        addonTimeoutMs = 1_800,
        retryDelayMs = 0,
        retryCount = 0,
    ),
}
