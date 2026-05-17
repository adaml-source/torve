package com.torve.data.acceleration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class StartupAccelerationEnvelopeDto(
    val candidates: List<StartupAccelerationCandidateDto> = emptyList(),
)

@Serializable
data class StartupAccelerationCandidateDto(
    @SerialName("memory_id")
    val memoryId: String? = null,
    @SerialName("source_key")
    val sourceKey: String? = null,
    @SerialName("provider_type")
    val providerType: String? = null,
    val title: String? = null,
    val quality: String? = null,
    @SerialName("infohash")
    val infoHash: String? = null,
    @SerialName("file_idx")
    val fileIdx: Int? = null,
    @SerialName("direct_url")
    val directUrl: String? = null,
    @SerialName("is_cached")
    val isCached: Boolean? = null,
    val score: Double? = null,
    val reasons: List<String> = emptyList(),
    @SerialName("score_breakdown")
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    @SerialName("addon_name")
    val addonName: String? = null,
    @SerialName("source_label")
    val sourceLabel: String? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long? = null,
    val seeds: Int? = null,
    /**
     * Backend-declared provenance discriminator. When set to
     * `"usenet_nzbdav"` (case-insensitive), the mapper emits a
     * USENET_NZBDAV ParsedStream with a full [UsenetCandidatePayload].
     * Null for every other provenance — the existing heuristic
     * derivation from `reasons` continues to apply.
     */
    @SerialName("provenance_kind")
    val provenanceKind: String? = null,
    /**
     * Mandatory backend-issued hash used by `/resolver/usenet/warm` and
     * `/resolver/usenet/resolve`. Required when [provenanceKind] is
     * `"usenet_nzbdav"`; rows missing this do NOT become NzbDAV rows
     * (mapper falls through to the default provenance derivation so
     * non-NzbDAV behavior is untouched).
     */
    @SerialName("hash_key")
    val hashKey: String? = null,
    /**
     * Optional. When the originating backend-side indexer has it, the
     * app forwards it on warm/resolve so the backend can skip an extra
     * NZB fetch. Null-safe; backend re-fetches when omitted.
     */
    @SerialName("nzb_url")
    val nzbUrl: String? = null,
)

@Serializable
data class AccelerationOutcomeDto(
    @SerialName("content_id")
    val contentId: String,
    @SerialName("provider_type")
    val providerType: String,
    @SerialName("source_key")
    val sourceKey: String,
    val success: Boolean,
    val infohash: String? = null,
    val quality: String? = null,
)

@Serializable
data class StreamHandoffRequestDto(
    @SerialName("content_id")
    val contentId: String,
    @SerialName("memory_id")
    val memoryId: String,
)

@Serializable
data class StreamHandoffResponseDto(
    val url: String,
    @SerialName("is_direct")
    val isDirect: Boolean = false,
    @SerialName("supports_range")
    val supportsRange: Boolean = true,
    @SerialName("stream_id")
    val streamId: String? = null,
    @SerialName("expires_in_seconds")
    val expiresInSeconds: Int? = null,
)

@Serializable
data class AccelerationInventoryIngestDto(
    @SerialName("provider_type")
    val providerType: String,
    val items: List<JsonObject>,
)

@Serializable
data class HashAvailabilityObservationDto(
    val infohash: String,
    @SerialName("is_cached")
    val isCached: Boolean,
)

@Serializable
data class HashAvailabilityReportDto(
    @SerialName("provider_type")
    val providerType: String,
    val observations: List<HashAvailabilityObservationDto>,
)

internal fun parseStartupAccelerationResponse(
    json: kotlinx.serialization.json.Json,
    raw: String,
): StartupAccelerationEnvelopeDto {
    val parsed = json.parseToJsonElement(raw.ifBlank { """{"candidates":[]}""" })
    return when (parsed) {
        is kotlinx.serialization.json.JsonArray -> StartupAccelerationEnvelopeDto(
            candidates = parsed.mapNotNull {
                runCatching { json.decodeFromJsonElement(StartupAccelerationCandidateDto.serializer(), it) }.getOrNull()
            },
        )
        is JsonObject -> {
            val candidates = parsed["candidates"]
            if (candidates is kotlinx.serialization.json.JsonArray) {
                StartupAccelerationEnvelopeDto(
                    candidates = candidates.mapNotNull {
                        runCatching {
                            json.decodeFromJsonElement(StartupAccelerationCandidateDto.serializer(), it)
                        }.getOrNull()
                    },
                )
            } else {
                runCatching {
                    json.decodeFromJsonElement(StartupAccelerationEnvelopeDto.serializer(), parsed)
                }.getOrDefault(StartupAccelerationEnvelopeDto())
            }
        }
        else -> StartupAccelerationEnvelopeDto()
    }
}

internal fun extractInventoryItems(element: JsonElement?): List<JsonObject> {
    return when (element) {
        is kotlinx.serialization.json.JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val candidates = listOfNotNull(
                element["items"],
                element["torrents"],
                element["data"],
            )
            candidates.firstNotNullOfOrNull { candidate ->
                when (candidate) {
                    is kotlinx.serialization.json.JsonArray -> candidate.mapNotNull { it as? JsonObject }
                    is JsonObject -> extractInventoryItems(candidate["items"] ?: candidate["torrents"] ?: candidate["data"])
                    else -> null
                }
            }.orEmpty()
        }
        else -> emptyList()
    }
}
