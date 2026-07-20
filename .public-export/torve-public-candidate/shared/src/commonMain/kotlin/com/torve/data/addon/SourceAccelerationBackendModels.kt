package com.torve.data.addon

import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.HashAvailabilityState
import com.torve.domain.model.InventoryMatchType
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.StartupConfidenceReasonCode

data class StartupCandidateBackendModel(
    val streamKey: String,
    val title: String,
    val qualityLabel: String,
    val addonName: String,
    val sourceLabel: String?,
    val readinessState: ReadinessState,
    val provenanceKind: CandidateProvenanceKind,
    val provenanceProviderLabel: String? = null,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val isDirectPlayback: Boolean = false,
    val isKnownCached: Boolean = false,
    val sizeBytes: Long? = null,
    val seeds: Int? = null,
    val score: Double? = null,
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    val memoryId: String? = null,
)

data class InventoryMatchBackendModel(
    val matchKey: String,
    val providerLabel: String,
    val displayTitle: String,
    val matchType: InventoryMatchType,
    val readinessState: ReadinessState,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val qualityLabel: String? = null,
    val sizeBytes: Long? = null,
    val lastSeenAt: Long? = null,
)

data class KnownHashAvailabilityObservationBackendModel(
    val infoHash: String,
    val providerLabel: String,
    val availabilityState: HashAvailabilityState,
    val readinessState: ReadinessState,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val observedAt: Long? = null,
    val expiresAt: Long? = null,
)

data class RecentSuccessCandidateBackendModel(
    val streamKey: String,
    val title: String,
    val providerLabel: String? = null,
    val addonName: String,
    val qualityLabel: String,
    val sourceLabel: String? = null,
    val readinessState: ReadinessState = ReadinessState.READY_NOW,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val lastSuccessfulAt: Long,
    val successCount: Int,
)
