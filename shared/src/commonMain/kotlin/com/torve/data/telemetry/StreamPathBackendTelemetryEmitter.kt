package com.torve.data.telemetry

import com.torve.domain.telemetry.StreamPathTelemetryEvents
import com.torve.domain.telemetry.StreamPathTelemetryKeys
import com.torve.domain.telemetry.TelemetryEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class StreamPathBackendTelemetryEmitter(
    private val reportPath: suspend (StreamPathTelemetryRequest) -> Boolean,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TelemetryEmitter {

    constructor(
        api: StreamPathTelemetryApi,
        nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        reportPath = { request ->
            api.reportPathSelected(
                pathType = request.pathType,
                contentType = request.contentType,
                providerCategory = request.providerCategory,
                generatedAtEpochMillis = request.generatedAtEpochMillis,
            )
        },
        nowMillis = nowMillis,
        scope = scope,
    )

    override fun emit(event: String, attributes: Map<String, String>) {
        if (event != StreamPathTelemetryEvents.PATH_SELECTED) return
        val pathType = attributes[StreamPathTelemetryKeys.PATH_TYPE]?.takeIf { it.isNotBlank() } ?: return
        val contentType = attributes[StreamPathTelemetryKeys.CONTENT_TYPE]?.takeIf { it.isNotBlank() } ?: "unknown"
        val providerCategory = attributes[StreamPathTelemetryKeys.PROVIDER_CATEGORY]?.takeIf { it.isNotBlank() } ?: "unknown"
        val request = StreamPathTelemetryRequest(
            pathType = pathType,
            platform = "unknown",
            appVersion = "unknown",
            distributionChannel = "unknown",
            contentType = contentType,
            providerCategory = providerCategory,
            generatedAtEpochMillis = nowMillis(),
        )
        scope.launch {
            runCatching { reportPath(request) }
        }
    }
}
