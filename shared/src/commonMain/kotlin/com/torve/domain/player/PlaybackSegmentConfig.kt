package com.torve.domain.player

data class PlaybackSegmentConfig(
    val manualActionThreshold: Double = 0.62,
    val automaticActionThreshold: Double = 0.88,
    val exactSourceThreshold: Double = 0.94,
    val highThreshold: Double = 0.78,
    val mediumThreshold: Double = 0.62,
    val lowThreshold: Double = 0.35,
    val boundaryAgreementMs: Long = 2_500L,
    /** EOF credit markers this far apart may be release variants of one boundary. */
    val creditSourceVariantClusteringMs: Long = 90_000L,
    val materialBoundaryDisagreementPenalty: Double = 0.70,
    val maximumProviderSegmentMs: Long = 15L * 60_000L,
    val durationToleranceMs: Long = 1_500L,
    /** Small container/encoder tail variance. This permits manual actions only. */
    val minorRuntimeVarianceMs: Long = 5_000L,
    /** A nearby release runtime can support a manual prompt, never automatic action. */
    val manualSourceVariantVarianceMs: Long = 30_000L,
    /** EOF-anchored credits without a source runtime may only support manual interaction. */
    val eofAnchoredUnknownRuntimeConfidence: Double = 0.80,
    /** An exact runtime match is stronger than an unqualified community marker, but is not an exact file match. */
    val providerExactRuntimeAlignmentConfidence: Double = 0.87,
    /** A provider-side conservative shift is useful for a manual action and requires corroboration for automation. */
    val providerConservativeShiftAlignmentConfidence: Double = 0.78,
    val providerAgnosticAlignmentConfidence: Double = 0.52,
    val providerOutOfRangeAlignmentConfidence: Double = 0.30,
    val trustedBoundaryPaddingMs: Long = 350L,
    val highBoundaryPaddingMs: Long = 750L,
    val mediumBoundaryPaddingMs: Long = 1_500L,
    val minimumValidatedObservations: Int = 3,
    val observationOutlierToleranceMs: Long = 2_000L,
    val correctionRecognitionWindowMs: Long = 12_000L,
) {
    fun level(confidence: Double): SegmentConfidence = when {
        confidence >= automaticActionThreshold -> SegmentConfidence.VERY_HIGH
        confidence >= highThreshold -> SegmentConfidence.HIGH
        confidence >= mediumThreshold -> SegmentConfidence.MEDIUM
        confidence >= lowThreshold -> SegmentConfidence.LOW
        else -> SegmentConfidence.UNKNOWN
    }

    fun skipTarget(segment: PlaybackSegment): Long {
        val padding = when {
            segment.source == SegmentEvidenceSource.USER_VALIDATED -> trustedBoundaryPaddingMs
            segment.confidence >= exactSourceThreshold -> trustedBoundaryPaddingMs
            segment.confidence >= highThreshold -> highBoundaryPaddingMs
            else -> mediumBoundaryPaddingMs
        }
        return (segment.endMs - padding).coerceAtLeast(segment.startMs)
    }
}
