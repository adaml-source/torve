package com.torve.domain.telemetry

/**
 * Minimal analytics surface for the Torve app. Intentionally structure-
 * only: no real sink is shipped in this sprint — the default
 * [NoOpTelemetryEmitter] is wired through Koin so feature code can emit
 * events without blocking a later backend integration.
 *
 * Contract:
 *  - Emissions are fire-and-forget. Implementations MUST swallow any
 *    internal failure; a broken analytics sink cannot break a feature.
 *  - Attribute values are `String` so wire-format is unambiguous. Numeric
 *    timings are pre-bucketed at the call site (see [timeBucket]).
 *  - Attribute keys and values are application-neutral — no raw backend
 *    reason tokens, no URLs, no headers, no secrets. The
 *    [UsenetTelemetry] helpers and tests enforce this for the Usenet
 *    surface; other surfaces should mirror the pattern.
 */
interface TelemetryEmitter {
    fun emit(event: String, attributes: Map<String, String> = emptyMap())
}

class CompositeTelemetryEmitter(
    private val emitters: List<TelemetryEmitter>,
) : TelemetryEmitter {
    override fun emit(event: String, attributes: Map<String, String>) {
        emitters.forEach { emitter ->
            runCatching { emitter.emit(event, attributes) }
        }
    }
}

/** Default sink. Drops every emission. Wired in [com.torve.di.SharedModule]. */
class NoOpTelemetryEmitter : TelemetryEmitter {
    override fun emit(event: String, attributes: Map<String, String>) {
        // no-op
    }
}

/**
 * `println`-backed emitter intended only for development debugging. Not
 * wired by default; swap into [com.torve.di.SharedModule] by hand to
 * inspect emissions locally. Still safe to enable in release because
 * it never raises — but produces log noise.
 */
class PrintlnTelemetryEmitter(
    private val tag: String = "TorveTelemetry",
) : TelemetryEmitter {
    override fun emit(event: String, attributes: Map<String, String>) {
        val attrString = if (attributes.isEmpty()) "" else attributes.entries
            .joinToString(separator = ", ", prefix = " { ", postfix = " }") { (k, v) -> "$k=$v" }
        println("[$tag] $event$attrString")
    }
}

/**
 * Pre-bucket a duration in ms so per-event cardinality stays bounded.
 * Buckets match the existing Usenet wait budgets (resolve is usually
 * sub-second on cached, low-single-digit seconds on warmed, and the
 * poller's 5-minute cap sits inside the last bucket).
 */
fun timeBucket(ms: Long): String = when {
    ms < 0L -> "unknown"
    ms < 500L -> "lt_500ms"
    ms < 1_500L -> "500_1500ms"
    ms < 5_000L -> "1500_5000ms"
    ms < 15_000L -> "5000_15000ms"
    ms < 60_000L -> "15000_60000ms"
    else -> "gte_60000ms"
}
