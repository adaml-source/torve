package com.torve.domain.telemetry

import com.torve.platform.torveVerboseLog

enum class StreamPlaybackPath(val wireValue: String) {
    USENET_HANDOFF("usenet_handoff"),
    GENERIC_HANDOFF_MEMORY_ID("generic_handoff_memory_id"),
    LEGACY_DIRECT_NO_MEMORY_ID("legacy_direct_no_memory_id"),
    IPTV_DIRECT("iptv_direct"),
    DIRECT_FREE("direct_free"),
}

data class StreamPathTelemetryContext(
    val contentType: String = "unknown",
    val providerCategory: String = "unknown",
)

object StreamPathTelemetryEvents {
    const val PATH_SELECTED: String = "stream_path_selected"
}

object StreamPathTelemetryKeys {
    const val PATH_TYPE: String = "path_type"
    const val CONTENT_TYPE: String = "content_type"
    const val PROVIDER_CATEGORY: String = "provider_category"
}

/**
 * Counts and emits only coarse playback-route decisions. The contract is
 * intentionally identifier-free: no URLs, memory IDs, source keys, provider
 * tokens, channel names, or credentials are accepted by this API.
 */
object StreamPathDiagnostics {
    private val counts = mutableMapOf<StreamPlaybackPath, Int>()

    fun record(
        path: StreamPlaybackPath,
        telemetry: TelemetryEmitter? = null,
        context: StreamPathTelemetryContext = StreamPathTelemetryContext(),
    ) {
        val count = (counts[path] ?: 0) + 1
        counts[path] = count
        runCatching {
            telemetry?.emit(
                event = StreamPathTelemetryEvents.PATH_SELECTED,
                attributes = mapOf(
                    StreamPathTelemetryKeys.PATH_TYPE to path.wireValue,
                    StreamPathTelemetryKeys.CONTENT_TYPE to context.contentType.safeTelemetryLabel(),
                    StreamPathTelemetryKeys.PROVIDER_CATEGORY to context.providerCategory.safeTelemetryLabel(),
                ),
            )
        }
        torveVerboseLog { "STREAM_PATH_SELECTED path=${path.wireValue} count=$count" }
    }

    fun snapshot(): Map<String, Int> =
        StreamPlaybackPath.entries.associate { path -> path.wireValue to (counts[path] ?: 0) }

    fun resetForTests() {
        counts.clear()
    }

    private fun String.safeTelemetryLabel(): String {
        val raw = trim()
        if (raw.containsSensitiveTelemetryNeedle()) return "unknown"
        val normalized = trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_:-]"), "_")
            .trim('_')
            .take(64)
        return normalized.ifBlank { "unknown" }
    }

    private fun String.containsSensitiveTelemetryNeedle(): Boolean {
        val lower = lowercase()
        return listOf(
            "http://",
            "https://",
            "token",
            "password",
            "credential",
            "source_key",
            "memory_id",
            "bearer",
            "authorization",
            "api_key",
            "apikey",
        ).any { it in lower }
    }
}
