package com.torve.data.addon

import kotlin.math.roundToInt

/**
 * In-session stream host telemetry used to bias source ranking toward stability.
 * Keeps lightweight host-level stats only; no personal data is persisted.
 */
object StreamRuntimeTelemetry {
    private data class HostStats(
        var starts: Int = 0,
        var startupSuccess: Int = 0,
        var startupTimeouts: Int = 0,
        var fatalErrors: Int = 0,
        var earlyRebuffers: Int = 0,
        var earlyRebufferMs: Long = 0L,
        var completionCount: Int = 0,
        var startupSamples: Int = 0,
        var startupTotalMs: Long = 0L,
    )

    private val hostStats = mutableMapOf<String, HostStats>()

    private fun statsFor(hostKey: String): HostStats {
        return hostStats.getOrPut(hostKey) { HostStats() }
    }

    fun keyForStream(stream: ParsedStream): String {
        val fromUrl = stream.directUrl
            ?.let(::hostFromUrl)
            ?.takeIf { it.isNotBlank() }
        if (fromUrl != null) return fromUrl

        val source = stream.source?.trim().orEmpty()
        if (source.isNotBlank()) return source.lowercase()

        val addon = stream.addonName.trim().ifBlank { "unknown" }
        return "addon:${addon.lowercase()}"
    }

    fun keyForUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return hostFromUrl(url)?.takeIf { it.isNotBlank() }
    }

    private fun hostFromUrl(url: String): String? {
        val normalized = url.substringBefore('?').trim()
        val host = normalized
            .substringAfter("://", missingDelimiterValue = normalized)
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        if (host.isBlank()) return null
        return host.lowercase()
    }

    fun recordPlayAttempt(hostKey: String) {
        statsFor(hostKey).starts += 1
    }

    fun recordStartupSuccess(hostKey: String, startupMs: Long) {
        val stats = statsFor(hostKey)
        stats.startupSuccess += 1
        stats.startupSamples += 1
        stats.startupTotalMs += startupMs.coerceAtLeast(0L)
    }

    fun recordStartupTimeout(hostKey: String, observedMs: Long) {
        val stats = statsFor(hostKey)
        stats.startupTimeouts += 1
        stats.startupSamples += 1
        stats.startupTotalMs += observedMs.coerceAtLeast(0L)
    }

    fun recordEarlyRebuffer(hostKey: String, rebufferMs: Long) {
        val stats = statsFor(hostKey)
        stats.earlyRebuffers += 1
        stats.earlyRebufferMs += rebufferMs.coerceAtLeast(0L)
    }

    fun recordFatalError(hostKey: String) {
        statsFor(hostKey).fatalErrors += 1
    }

    fun recordCompletion(hostKey: String) {
        statsFor(hostKey).completionCount += 1
    }

    /**
     * Host reliability adjustment used by stream ranking:
     * positive = more reliable, negative = unstable recently.
     */
    fun reliabilityAdjustment(hostKey: String): Int {
        val stats = hostStats[hostKey] ?: return 0
        val starts = stats.starts.coerceAtLeast(1)

        val startupRate = stats.startupSuccess.toFloat() / starts
        val timeoutRate = stats.startupTimeouts.toFloat() / starts
        val fatalRate = stats.fatalErrors.toFloat() / starts
        val rebufferPenalty = (stats.earlyRebuffers * 0.45f) + (stats.earlyRebufferMs / 8_000f)
        val completionBonus = (stats.completionCount.coerceAtMost(6) * 0.25f)
        val avgStartupMs = if (stats.startupSamples > 0) {
            stats.startupTotalMs.toFloat() / stats.startupSamples
        } else {
            0f
        }
        val startupLatencyPenalty = when {
            avgStartupMs <= 0f -> 0f
            avgStartupMs <= 3_000f -> 0f
            avgStartupMs <= 6_000f -> 0.6f
            avgStartupMs <= 10_000f -> 1.2f
            else -> 1.8f
        }

        val score = (startupRate * 2.1f) - (timeoutRate * 1.5f) - (fatalRate * 2.0f) -
            rebufferPenalty - startupLatencyPenalty + completionBonus

        return (score * 6f).roundToInt().coerceIn(-24, 20)
    }

    /**
     * True when the host has repeated very recent failures and should be
     * aggressively deprioritized if alternatives exist.
     */
    fun isHostUnstable(hostKey: String): Boolean {
        val stats = hostStats[hostKey] ?: return false
        return stats.fatalErrors >= 2 || stats.earlyRebuffers >= 4 || stats.startupTimeouts >= 2
    }
}

