package com.torve.domain.player

data class PlaybackSegmentConfig(
    val manualActionThreshold: Double = 0.62,
    val automaticActionThreshold: Double = 0.88,
    val exactSourceThreshold: Double = 0.94,
    val highThreshold: Double = 0.78,
    val mediumThreshold: Double = 0.62,
    val lowThreshold: Double = 0.35,
    val boundaryAgreementMs: Long = 2_500L,
    val maximumProviderSegmentMs: Long = 15L * 60_000L,
    val durationToleranceMs: Long = 1_500L,
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
