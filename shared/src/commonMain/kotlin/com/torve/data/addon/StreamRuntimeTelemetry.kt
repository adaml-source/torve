package com.torve.data.addon

import kotlin.math.roundToInt

enum class PlaybackStartupSloStatus {
    INSUFFICIENT_DATA,
    MEETS_TARGET,
    BELOW_TARGET,
}

data class PlaybackStartupPerformanceSnapshot(
    val sampleCount: Int,
    val successfulStarts: Int,
    val failedStarts: Int,
    val successRate: Double?,
    val p50StartupMs: Long?,
    val p95StartupMs: Long?,
    val status: PlaybackStartupSloStatus,
)

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
        val successfulStartupMs: MutableList<Long> = mutableListOf(),
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
        val normalized = startupMs.coerceAtLeast(0L)
        stats.startupSuccess += 1
        stats.startupSamples += 1
        stats.startupTotalMs += normalized
        stats.successfulStartupMs += normalized
        if (stats.successfulStartupMs.size > MAX_LATENCY_SAMPLES_PER_HOST) {
            stats.successfulStartupMs.removeAt(0)
        }
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

        // Timeout and fatal callbacks can describe the same failed attempt.
        // Bound terminal outcomes by starts so one failure never damages a
        // provider twice in the reliability score.
        val successfulStarts = stats.startupSuccess.coerceAtMost(starts)
        val failedStarts = (stats.startupTimeouts + stats.fatalErrors)
            .coerceAtMost((starts - successfulStarts).coerceAtLeast(0))
        val fatalFailures = stats.fatalErrors.coerceAtMost(failedStarts)
        val timeoutFailures = (failedStarts - fatalFailures).coerceAtLeast(0)

        val startupRate = successfulStarts.toFloat() / starts
        val timeoutRate = timeoutFailures.toFloat() / starts
        val fatalRate = fatalFailures.toFloat() / starts
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

    fun performanceSnapshot(hostKey: String? = null): PlaybackStartupPerformanceSnapshot {
        val stats = if (hostKey == null) hostStats.values.toList() else listOfNotNull(hostStats[hostKey])
        val attempts = stats.sumOf { it.starts }
        val successful = stats.sumOf { it.startupSuccess }.coerceAtMost(attempts)
        val failed = stats.sumOf { it.startupTimeouts + it.fatalErrors }
            .coerceAtMost((attempts - successful).coerceAtLeast(0))
        val outcomes = successful + failed
        val latencies = stats.flatMap { it.successfulStartupMs }.sorted()
        val successRate = if (outcomes == 0) null else successful.toDouble() / outcomes.toDouble()
        val p50 = percentile(latencies, 0.50)
        val p95 = percentile(latencies, 0.95)
        val status = when {
            outcomes < MIN_SLO_SAMPLES || p50 == null || p95 == null || successRate == null ->
                PlaybackStartupSloStatus.INSUFFICIENT_DATA
            p50 <= TARGET_P50_MS && p95 <= TARGET_P95_MS && successRate >= TARGET_SUCCESS_RATE ->
                PlaybackStartupSloStatus.MEETS_TARGET
            else -> PlaybackStartupSloStatus.BELOW_TARGET
        }
        return PlaybackStartupPerformanceSnapshot(
            sampleCount = outcomes,
            successfulStarts = successful,
            failedStarts = failed,
            successRate = successRate,
            p50StartupMs = p50,
            p95StartupMs = p95,
            status = status,
        )
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long? {
        if (sorted.isEmpty()) return null
        val index = ((sorted.lastIndex * percentile).roundToInt()).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    internal fun clearForTest() {
        hostStats.clear()
    }

    private const val MAX_LATENCY_SAMPLES_PER_HOST = 100
    private const val MIN_SLO_SAMPLES = 20
    private const val TARGET_P50_MS = 4_000L
    private const val TARGET_P95_MS = 10_000L
    private const val TARGET_SUCCESS_RATE = 0.98
}
