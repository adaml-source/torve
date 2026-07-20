package com.torve.domain.telemetry

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StreamPathTelemetryTest {

    private class CapturingEmitter : TelemetryEmitter {
        val events = mutableListOf<Pair<String, Map<String, String>>>()

        override fun emit(event: String, attributes: Map<String, String>) {
            events += event to attributes
        }
    }

    @AfterTest
    fun tearDown() {
        StreamPathDiagnostics.resetForTests()
    }

    @Test
    fun `stream path telemetry emits only coarse safe path`() {
        val sink = CapturingEmitter()

        StreamPathDiagnostics.record(StreamPlaybackPath.GENERIC_HANDOFF_MEMORY_ID, sink)

        val (event, attrs) = sink.events.single()
        assertEquals(StreamPathTelemetryEvents.PATH_SELECTED, event)
        assertEquals(
            mapOf(
                StreamPathTelemetryKeys.PATH_TYPE to "generic_handoff_memory_id",
                StreamPathTelemetryKeys.CONTENT_TYPE to "unknown",
                StreamPathTelemetryKeys.PROVIDER_CATEGORY to "unknown",
            ),
            attrs,
        )
        val rendered = event + attrs.entries.joinToString { "${it.key}=${it.value}" }
        assertFalse(rendered.contains("mem-", ignoreCase = true))
        assertFalse(rendered.contains("source_key", ignoreCase = true))
        assertFalse(rendered.contains("http://", ignoreCase = true))
        assertFalse(rendered.contains("https://", ignoreCase = true))
        assertFalse(rendered.contains("token", ignoreCase = true))
        assertFalse(rendered.contains("password", ignoreCase = true))
    }

    @Test
    fun `stream path telemetry includes only coarse context`() {
        val sink = CapturingEmitter()

        StreamPathDiagnostics.record(
            path = StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID,
            telemetry = sink,
            context = StreamPathTelemetryContext(
                contentType = "Movie https://provider.example/path?token=secret",
                providerCategory = "Panda Token",
            ),
        )

        val (_, attrs) = sink.events.single()
        assertEquals("legacy_direct_no_memory_id", attrs[StreamPathTelemetryKeys.PATH_TYPE])
        assertEquals("unknown", attrs[StreamPathTelemetryKeys.CONTENT_TYPE])
        assertEquals("unknown", attrs[StreamPathTelemetryKeys.PROVIDER_CATEGORY])
        val rendered = attrs.entries
            .joinToString { "${it.key}=${it.value}" }
            .replace(StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID.wireValue, "")
        assertFalse(rendered.contains("http://", ignoreCase = true))
        assertFalse(rendered.contains("https://", ignoreCase = true))
        assertFalse(rendered.contains("source_key", ignoreCase = true))
        assertFalse(rendered.contains("memory_id", ignoreCase = true))
        assertFalse(rendered.contains("token", ignoreCase = true))
    }

    @Test
    fun `debug counters show protected handoff versus fallback counts`() {
        StreamPathDiagnostics.record(StreamPlaybackPath.GENERIC_HANDOFF_MEMORY_ID)
        StreamPathDiagnostics.record(StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID)
        StreamPathDiagnostics.record(StreamPlaybackPath.LEGACY_DIRECT_NO_MEMORY_ID)
        StreamPathDiagnostics.record(StreamPlaybackPath.IPTV_DIRECT)

        assertEquals(
            mapOf(
                "usenet_handoff" to 0,
                "generic_handoff_memory_id" to 1,
                "legacy_direct_no_memory_id" to 2,
                "iptv_direct" to 1,
                "direct_free" to 0,
            ),
            StreamPathDiagnostics.snapshot(),
        )
    }
}
