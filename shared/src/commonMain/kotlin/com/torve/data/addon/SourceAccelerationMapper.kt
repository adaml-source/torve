package com.torve.data.addon

import com.torve.data.acceleration.StartupAccelerationCandidateDto
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.CandidateProvenance
import com.torve.domain.model.InventoryMatch
import com.torve.domain.model.KnownHashAvailabilityObservation
import com.torve.domain.model.RecentSuccessCandidate
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode
import com.torve.domain.model.StreamQuality
import kotlin.math.roundToInt

object SourceAccelerationMapper {

    fun toStartupCandidate(model: StartupCandidateBackendModel): StartupCandidate {
        return StartupCandidate(
            streamKey = model.streamKey,
            title = model.title,
            qualityLabel = model.qualityLabel,
            quality = StreamQuality.fromString(model.qualityLabel),
            addonName = model.addonName,
            sourceLabel = model.sourceLabel,
            readinessState = model.readinessState,
            provenance = CandidateProvenance(
                kind = model.provenanceKind,
                providerLabel = model.provenanceProviderLabel,
                sourceLabel = model.sourceLabel,
            ),
            confidenceReasons = model.confidenceReasons.ifEmpty {
                listOf(com.torve.domain.model.StartupConfidenceReasonCode.NO_ACCELERATION_SIGNAL)
            },
            isDirectPlayback = model.isDirectPlayback,
            isKnownCached = model.isKnownCached,
            sizeBytes = model.sizeBytes,
            seeds = model.seeds,
            score = model.score,
            scoreBreakdown = model.scoreBreakdown,
            memoryId = model.memoryId,
        )
    }

    fun toInventoryMatch(model: InventoryMatchBackendModel): InventoryMatch {
        return InventoryMatch(
            matchKey = model.matchKey,
            providerLabel = model.providerLabel,
            displayTitle = model.displayTitle,
            matchType = model.matchType,
            readinessState = model.readinessState,
            confidenceReasons = model.confidenceReasons,
            qualityLabel = model.qualityLabel,
            sizeBytes = model.sizeBytes,
            lastSeenAt = model.lastSeenAt,
        )
    }

    fun toKnownHashAvailabilityObservation(
        model: KnownHashAvailabilityObservationBackendModel,
    ): KnownHashAvailabilityObservation {
        return KnownHashAvailabilityObservation(
            infoHash = model.infoHash,
            providerLabel = model.providerLabel,
            availabilityState = model.availabilityState,
            readinessState = model.readinessState,
            confidenceReasons = model.confidenceReasons,
            observedAt = model.observedAt,
            expiresAt = model.expiresAt,
        )
    }

    fun toRecentSuccessCandidate(model: RecentSuccessCandidateBackendModel): RecentSuccessCandidate {
        return RecentSuccessCandidate(
            streamKey = model.streamKey,
            title = model.title,
            providerLabel = model.providerLabel,
            addonName = model.addonName,
            qualityLabel = model.qualityLabel,
            quality = StreamQuality.fromString(model.qualityLabel),
            sourceLabel = model.sourceLabel,
            readinessState = model.readinessState,
            confidenceReasons = model.confidenceReasons.ifEmpty {
                listOf(com.torve.domain.model.StartupConfidenceReasonCode.RECENT_SUCCESS)
            },
            lastSuccessfulAt = model.lastSuccessfulAt,
            successCount = model.successCount,
        )
    }

    fun backendReasonCodes(reasons: List<String>): List<StartupConfidenceReasonCode> {
        return reasons.mapNotNull(::mapBackendReasonCode).distinct()
    }

    fun backendCandidateToParsedStream(model: StartupAccelerationCandidateDto): ParsedStream? {
        val sourceKey = model.sourceKey?.trim().takeIf { !it.isNullOrBlank() }
        val infoHash = model.infoHash?.normalizeBtihInfoHash()
        val directUrl = model.directUrl?.trim()?.takeIf { it.isNotBlank() }
        if (sourceKey == null && infoHash == null && directUrl == null) return null

        val reasons = backendReasonCodes(model.reasons)
        val providerLabel = model.providerType?.prettyProviderLabel()
        val sourceLabel = model.sourceLabel?.takeIf { it.isNotBlank() } ?: providerLabel
        val qualityLabel = model.quality?.takeIf { it.isNotBlank() }
            ?: StreamParser.extractQuality(model.title.orEmpty())
        val normalizedScore = normalizeBackendScore(model.score)

        // NzbDAV elevation: only when the backend explicitly flags the
        // provenance AND both required fields are present. Anything else
        // (missing flag, blank candidate id, blank hash key) falls through
        // to the existing provenance derivation — preserves non-NzbDAV
        // behavior verbatim and prevents a half-populated row from being
        // sent to the warm/resolve endpoints.
        val usenetPayload = toUsenetCandidatePayload(model, sourceKey)
        val provenance = if (usenetPayload != null) {
            CandidateProvenanceKind.USENET_NZBDAV
        } else {
            backendProvenanceFor(reasons)
        }

        return ParsedStream(
            addonName = model.addonName?.takeIf { it.isNotBlank() } ?: providerLabel ?: "Torve",
            quality = qualityLabel,
            title = model.title?.takeIf { it.isNotBlank() } ?: "Recommended source",
            infoHash = infoHash,
            fileIdx = model.fileIdx,
            directUrl = directUrl,
            size = model.sizeBytes?.let(::formatSizeLabel),
            seeds = model.seeds,
            source = sourceLabel,
            isCached = model.isCached == true || reasons.contains(StartupConfidenceReasonCode.HASH_CACHED),
            score = normalizedScore,
            recentSuccessCount = if (reasons.contains(StartupConfidenceReasonCode.RECENT_SUCCESS)) 1 else 0,
            accelerationMemoryId = model.memoryId?.trim()?.takeIf { it.isNotBlank() },
            accelerationSourceKey = sourceKey,
            accelerationProviderType = model.providerType?.trim()?.takeIf { it.isNotBlank() },
            accelerationProvenanceKind = provenance,
            accelerationConfidenceReasons = reasons,
            accelerationScore = model.score,
            accelerationScoreBreakdown = model.scoreBreakdown,
            usenetCandidate = usenetPayload,
        )
    }

    /**
     * Produces a [UsenetCandidatePayload] iff the DTO is unambiguously
     * a USENET_NZBDAV row. All three checks must pass; any failure
     * causes the row to fall through to the default provenance and
     * leaves `usenetCandidate` null. The coordinator's stricter
     * `usenetCandidatePayloadOrNull()` accessor will reject any row
     * that somehow makes it through with bad fields.
     */
    private fun toUsenetCandidatePayload(
        model: StartupAccelerationCandidateDto,
        sourceKey: String?,
    ): UsenetCandidatePayload? {
        val flag = model.provenanceKind?.trim()?.lowercase()
        if (flag != USENET_NZBDAV_PROVENANCE_FLAG) return null
        val candidateId = sourceKey ?: return null
        val hashKey = model.hashKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val nzbUrl = model.nzbUrl?.trim()?.takeIf { it.isNotBlank() }
        return UsenetCandidatePayload(candidateId = candidateId, hashKey = hashKey, nzbUrl = nzbUrl)
    }

    private const val USENET_NZBDAV_PROVENANCE_FLAG = "usenet_nzbdav"

    private fun backendProvenanceFor(
        reasons: List<StartupConfidenceReasonCode>,
    ): CandidateProvenanceKind {
        return when {
            reasons.contains(StartupConfidenceReasonCode.RECENT_SUCCESS) -> CandidateProvenanceKind.RECENT_SUCCESS
            reasons.contains(StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH) -> CandidateProvenanceKind.INVENTORY_MATCH
            reasons.contains(StartupConfidenceReasonCode.HASH_CACHED) ||
                reasons.contains(StartupConfidenceReasonCode.KNOWN_INFO_HASH) -> CandidateProvenanceKind.HASH_AVAILABILITY
            else -> CandidateProvenanceKind.STARTUP_FETCH
        }
    }

    private fun mapBackendReasonCode(raw: String): StartupConfidenceReasonCode? {
        return when (raw.trim().lowercase()) {
            "recent_success" -> StartupConfidenceReasonCode.RECENT_SUCCESS
            "direct", "direct_playable_url", "direct_playback" -> StartupConfidenceReasonCode.DIRECT_PLAYABLE_URL
            "cached", "hash_cached", "cached_hash" -> StartupConfidenceReasonCode.HASH_CACHED
            "known_hash", "known_info_hash", "infohash" -> StartupConfidenceReasonCode.KNOWN_INFO_HASH
            "connected_service_match", "inventory_match", "in_your_cloud", "cloud_match" ->
                StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH
            "exact_content_match", "exact_content" -> StartupConfidenceReasonCode.EXACT_CONTENT_MATCH
            "exact_episode_match", "exact_episode" -> StartupConfidenceReasonCode.EXACT_EPISODE_MATCH
            "provider_signal", "provider" -> StartupConfidenceReasonCode.PROVIDER_SIGNAL
            "no_acceleration_signal" -> StartupConfidenceReasonCode.NO_ACCELERATION_SIGNAL
            else -> null
        }
    }

    private fun String.prettyProviderLabel(): String {
        return split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    private fun normalizeBackendScore(score: Double?): Int {
        if (score == null) return 85
        val normalized = if (score in 0.0..1.0) score * 100.0 else score
        return normalized.roundToInt().coerceIn(0, 100)
    }

    private fun formatSizeLabel(sizeBytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return if (sizeBytes >= gb) {
            "${((sizeBytes / gb) * 10.0).roundToInt() / 10.0} GB"
        } else {
            "${((sizeBytes / mb) * 10.0).roundToInt() / 10.0} MB"
        }
    }
}
