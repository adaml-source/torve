package com.torve.domain.player

data class SegmentBoundaryObservation(
    val startMs: Long,
    val endMs: Long,
    val kind: String,
    val observedAtEpochMs: Long,
)

data class ValidatedSegmentBoundary(
    val startMs: Long,
    val endMs: Long,
    val evidenceCount: Int,
)

class SegmentObservationConsensus(private val config: PlaybackSegmentConfig = PlaybackSegmentConfig()) {
    fun validate(observations: List<SegmentBoundaryObservation>): ValidatedSegmentBoundary? {
        if (observations.size < config.minimumValidatedObservations) return null
        val starts = observations.map(SegmentBoundaryObservation::startMs).sorted()
        val ends = observations.map(SegmentBoundaryObservation::endMs).sorted()
        val medianStart = median(starts)
        val medianEnd = median(ends)
        val inliers = observations.filter {
            kotlin.math.abs(it.startMs - medianStart) <= config.observationOutlierToleranceMs &&
                kotlin.math.abs(it.endMs - medianEnd) <= config.observationOutlierToleranceMs
        }
        if (inliers.size < config.minimumValidatedObservations) return null
        return ValidatedSegmentBoundary(
            startMs = median(inliers.map(SegmentBoundaryObservation::startMs).sorted()),
            endMs = median(inliers.map(SegmentBoundaryObservation::endMs).sorted()),
            evidenceCount = inliers.size,
        )
    }

    private fun median(values: List<Long>): Long = values[values.size / 2]
}
