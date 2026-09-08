package com.torve.domain.player

import com.torve.domain.repository.CachedSegmentAnalysis
import com.torve.domain.repository.PlaybackSegmentRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.math.abs

class PlaybackSegmentEngine(
    private val repository: PlaybackSegmentRepository,
    private val providers: List<SegmentMarkerProvider> = emptyList(),
    private val config: PlaybackSegmentConfig = PlaybackSegmentConfig(),
    private val providerTimeoutMs: Long = 1_500L,
) {
    companion object {
        const val CURRENT_ANALYSIS_VERSION = 3
        private const val MAX_PROVIDER_MARKERS = 64
        private const val MAX_LOCAL_OBSERVATIONS = 20
    }

    suspend fun analyze(request: PlaybackSegmentRequest): PlaybackSegmentAnalysis {
        val media = request.media
        if (media.runtimeMs <= 0L || media.canonicalEpisodeId.isBlank()) return emptyAnalysis("invalid-media")
        repository.get(media.canonicalEpisodeId, media.fingerprint, CURRENT_ANALYSIS_VERSION)?.let { cached ->
            val valid = cached.segments.filter { SegmentValidation.isValid(it.startMs, it.endMs, media.runtimeMs) }
            if (valid.isNotEmpty() && valid.size == cached.segments.size) {
                return PlaybackSegmentAnalysis(valid, fromCache = true, CURRENT_ANALYSIS_VERSION)
            }
        }

        return runCatching { analyzeUncached(request) }.getOrElse { error ->
            emptyAnalysis("analysis-failed:${error::class.simpleName}")
        }
    }

    /**
     * Records an exact-source skip undo. One action never changes a marker.
     * A resistant consensus may only shorten the skip end, which preserves
     * content when a learned correction is imperfect.
     */
    suspend fun recordSkipUndo(
        media: MediaIdentity,
        segmentId: String,
        suggestedEndMs: Long,
    ) {
        val cached = repository.get(media.canonicalEpisodeId, media.fingerprint, CURRENT_ANALYSIS_VERSION) ?: return
        val target = cached.segments.firstOrNull { it.id == segmentId } ?: return
        if (target.type !in setOf(SegmentType.INTRO, SegmentType.RECAP)) return
        if (suggestedEndMs <= target.startMs || suggestedEndMs >= target.endMs) return
        val now = Clock.System.now().toEpochMilliseconds()
        val observation = SegmentEvidence(
            source = SegmentEvidenceSource.USER_VALIDATED,
            detectorId = "local-skip-undo",
            detectorVersion = 1,
            confidence = 0.58,
            correlationKey = "local-user-correction",
            startMs = target.startMs,
            endMs = suggestedEndMs,
            referenceRuntimeMs = media.runtimeMs,
            referenceFingerprint = media.fingerprint,
            details = "conservative-end-candidate",
        )
        val observations = (target.evidence + observation)
            .filter { it.source == SegmentEvidenceSource.USER_VALIDATED && it.detectorId == "local-skip-undo" }
            .takeLast(MAX_LOCAL_OBSERVATIONS)
        val validated = SegmentObservationConsensus(config).validate(
            observations.map {
                SegmentBoundaryObservation(it.startMs, it.endMs, "skip-undo", now)
            },
        )
        val retainedEvidence = target.evidence.filterNot {
            it.source == SegmentEvidenceSource.USER_VALIDATED && it.detectorId == "local-skip-undo"
        } + observations
        val updatedTarget = if (validated != null && validated.endMs < target.endMs) {
            val confidence = maxOf(target.confidence, config.highThreshold)
            target.copy(
                endMs = validated.endMs,
                confidence = confidence,
                confidenceLevel = config.level(confidence),
                source = SegmentEvidenceSource.USER_VALIDATED,
                evidence = retainedEvidence,
                updatedAtEpochMs = now,
            )
        } else {
            target.copy(evidence = retainedEvidence, updatedAtEpochMs = now)
        }
        repository.put(
            cached.copy(
                validationCount = observations.size,
                segments = cached.segments.map { if (it.id == segmentId) updatedTarget else it },
                updatedAtEpochMs = now,
            ),
        )
    }

    private suspend fun analyzeUncached(request: PlaybackSegmentRequest): PlaybackSegmentAnalysis = coroutineScope {
        val media = request.media
        val diagnostics = mutableListOf<String>()
        val typed = mutableListOf<TypedEvidence>()

        EmbeddedChapterSegmentDetector.detect(request.chapters, media.runtimeMs).forEach { evidence ->
            typed += TypedEvidence(typeForChapter(evidence), evidence)
        }
        SubtitleSegmentDetector.detect(request.subtitleCues, media.runtimeMs).forEach { evidence ->
            typed += TypedEvidence(SegmentType.RECAP, evidence)
        }
        AudioFingerprintSegmentDetector.detect(request.audioMatches, media.runtimeMs).forEach { evidence ->
            val type = request.audioMatches.firstOrNull {
                it.startMs == evidence.startMs && it.endMs == evidence.endMs
            }?.classification ?: SegmentType.INTRO
            typed += TypedEvidence(type, evidence)
        }
        val visual = VisualSegmentDetector.detect(request.visualObservations, media.runtimeMs)
        visual.evidence.forEach { evidence ->
            val type = request.visualObservations.firstOrNull {
                it.startMs == evidence.startMs && it.endMs == evidence.endMs
            }?.classification ?: if (evidence.details?.contains("credit") == true) SegmentType.CREDITS else inferVisualType(request, evidence)
            typed += TypedEvidence(type, evidence)
        }

        val providerResults = providers.map { provider ->
            async {
                provider.id to withTimeoutOrNull(providerTimeoutMs) {
                    try {
                        provider.getSegments(request)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }.map { it.await() }

        providerResults.forEach { (providerId, markers) ->
            if (markers == null) {
                diagnostics += "provider-timeout:$providerId"
            } else {
                markers.take(MAX_PROVIDER_MARKERS).forEach { marker ->
                    reconcileProviderMarker(marker, request)?.let(typed::add)
                }
            }
        }

        request.seasonPrior?.let { prior ->
            if (prior.episodeCount >= 3) {
                val nearby = typed.filter { it.type == prior.type }.filter {
                    abs(it.evidence.startMs - prior.expectedStartMs) <= maxOf(prior.dispersionMs * 3, 8_000L)
                }
                if (nearby.isNotEmpty()) {
                    typed += TypedEvidence(
                        prior.type,
                        SegmentEvidence(
                            source = SegmentEvidenceSource.CONSENSUS,
                            detectorId = "season-structure",
                            detectorVersion = 1,
                            confidence = (0.42 + prior.episodeCount.coerceAtMost(8) * 0.025 - prior.dispersionMs / 120_000.0)
                                .coerceIn(0.35, 0.62),
                            correlationKey = "season-prior:${request.seasonId ?: media.canonicalEpisodeId}",
                            startMs = prior.expectedStartMs,
                            endMs = prior.expectedEndMs,
                            referenceRuntimeMs = media.runtimeMs,
                            details = "episodes=${prior.episodeCount};dispersionMs=${prior.dispersionMs}",
                        ),
                    )
                }
            }
        }

        val now = Clock.System.now().toEpochMilliseconds()
        var segments = fuse(typed, media, request.seasonId, now)
        segments = markCreditsOverContent(segments, visual.creditsOverContent)
        segments = inferPostCreditSegments(segments, media, request.seasonId, now)
        val detectorVersions = segments.flatMap(PlaybackSegment::evidence)
            .distinctBy { "${it.detectorId}:${it.detectorVersion}" }
            .joinToString(",") { "${it.detectorId}:${it.detectorVersion}" }
        if (segments.isNotEmpty()) repository.put(
            CachedSegmentAnalysis(
                canonicalEpisodeId = media.canonicalEpisodeId,
                mediaFingerprint = media.fingerprint,
                runtimeMs = media.runtimeMs,
                analysisVersion = CURRENT_ANALYSIS_VERSION,
                detectorVersions = detectorVersions,
                validationCount = 0,
                segments = segments,
                analyzedAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        PlaybackSegmentAnalysis(segments, fromCache = false, CURRENT_ANALYSIS_VERSION, diagnostics)
    }

    private fun reconcileProviderMarker(
        marker: ProviderMarker,
        request: PlaybackSegmentRequest,
    ): TypedEvidence? {
        if (!SegmentValidation.isValid(marker.startMs, marker.endMs, marker.referenceRuntimeMs)) return null
        if (marker.endMs - marker.startMs > config.maximumProviderSegmentMs) return null
        val current = request.media
        val exact = marker.referenceFingerprint != null && marker.referenceFingerprint == current.fingerprint
        val aligned: Pair<Long, Long>
        val alignmentConfidence: Double
        val details: String
        when {
            exact -> {
                aligned = marker.startMs to marker.endMs
                alignmentConfidence = 1.0
                details = "exact-source"
            }
            request.alignmentAnchors.count { it.confidence >= 0.75 } >= 2 -> {
                aligned = alignWithAnchors(marker.startMs, marker.endMs, request.alignmentAnchors)
                alignmentConfidence = request.alignmentAnchors.map { it.confidence }.average().coerceIn(0.0, 1.0)
                details = "perceptual-anchor-alignment"
            }
            marker.endsAtMediaEnd && !marker.referenceRuntimeReliable &&
                marker.type in setOf(SegmentType.CREDITS, SegmentType.FINAL_CREDITS) -> {
                // The start belongs to an unknown release, but the provider has
                // explicitly anchored the segment to EOF. This can surface a
                // manual prompt and can never reach the automatic threshold alone.
                aligned = marker.startMs to current.runtimeMs
                alignmentConfidence = config.eofAnchoredUnknownRuntimeConfidence
                details = "provider-eof-anchor-manual-only"
            }
            !marker.referenceRuntimeReliable -> {
                aligned = marker.startMs to marker.endMs
                alignmentConfidence = 0.52
                details = "provider-runtime-unknown"
            }
            isConstantSpeedConversion(marker.referenceRuntimeMs, current.runtimeMs) -> {
                val ratio = current.runtimeMs.toDouble() / marker.referenceRuntimeMs.toDouble()
                aligned = (marker.startMs * ratio).toLong() to (marker.endMs * ratio).toLong()
                alignmentConfidence = 0.82
                details = "constant-speed-alignment"
            }
            abs(marker.referenceRuntimeMs - current.runtimeMs) <= config.durationToleranceMs -> {
                aligned = marker.startMs to marker.endMs
                alignmentConfidence = 0.88
                details = "runtime-compatible"
            }
            abs(marker.referenceRuntimeMs - current.runtimeMs) <= config.minorRuntimeVarianceMs -> {
                // A few seconds of mux/container tail variance can leave structural
                // timestamps unchanged. Keep this manual-only: the difference may
                // still be a logo before the intro rather than padding at EOF.
                aligned = marker.startMs to marker.endMs
                alignmentConfidence = 0.78
                details = "minor-runtime-variance"
            }
            abs(marker.referenceRuntimeMs - current.runtimeMs) <= config.manualSourceVariantVarianceMs -> {
                // A nearby release may differ by logos or tail padding. Do not
                // shift its markers without anchors, and keep the result barely
                // inside the manual-only confidence band.
                aligned = marker.startMs to marker.endMs
                alignmentConfidence = 0.76
                details = "nearby-source-runtime-manual-only"
            }
            else -> {
                // Retain only as weak learning evidence. It stays below the manual threshold.
                aligned = marker.startMs to marker.endMs
                alignmentConfidence = 0.42
                details = "unaligned-release"
            }
        }
        val start = aligned.first.coerceAtLeast(0L)
        val end = aligned.second.coerceAtMost(current.runtimeMs)
        if (!SegmentValidation.isValid(start, end, current.runtimeMs)) return null
        val confidence = (marker.confidence.coerceIn(0.0, 1.0) * alignmentConfidence).coerceIn(0.0, 1.0)
        return TypedEvidence(
            marker.type,
            SegmentEvidence(
                source = if (exact) SegmentEvidenceSource.SOURCE_FINGERPRINT else SegmentEvidenceSource.COMMUNITY_DATABASE,
                detectorId = marker.providerId,
                detectorVersion = marker.providerVersion,
                confidence = confidence,
                correlationKey = "provider:${marker.providerId}",
                startMs = start,
                endMs = end,
                referenceRuntimeMs = marker.referenceRuntimeMs,
                referenceFingerprint = marker.referenceFingerprint,
                details = details,
            ),
        )
    }

    private fun fuse(
        evidence: List<TypedEvidence>,
        media: MediaIdentity,
        seasonId: String?,
        now: Long,
    ): List<PlaybackSegment> {
        val clusters = mutableListOf<MutableList<TypedEvidence>>()
        evidence.sortedBy { it.evidence.startMs }.forEach { item ->
            val cluster = clusters.firstOrNull { existing ->
                existing.first().type == item.type && existing.any { candidate ->
                    boundariesAgree(candidate.evidence, item.evidence) ||
                        eofCreditReleaseVariants(candidate, item, media.runtimeMs)
                }
            }
            if (cluster == null) clusters += mutableListOf(item) else cluster += item
        }
        return clusters.mapNotNull { cluster ->
            val independent = cluster.groupBy { it.evidence.correlationKey }
                .map { (_, group) -> group.maxBy { it.evidence.confidence } }
            if (independent.isEmpty()) return@mapNotNull null
            var missProbability = 1.0
            independent.forEach { missProbability *= 1.0 - it.evidence.confidence.coerceIn(0.0, 0.97) }
            var confidence = 1.0 - missProbability
            val starts = independent.map { it.evidence.startMs }
            val ends = independent.map { it.evidence.endMs }
            val disagreement = (starts.maxOrNull()!! - starts.minOrNull()!! > config.boundaryAgreementMs) ||
                (ends.maxOrNull()!! - ends.minOrNull()!! > config.boundaryAgreementMs)
            if (disagreement) confidence *= config.materialBoundaryDisagreementPenalty
            val type = cluster.first().type
            val start = when (type) {
                SegmentType.CREDITS, SegmentType.FINAL_CREDITS ->
                    if (disagreement) starts.max() else weightedQuantile(starts, 0.65)
                else -> weightedMedian(starts)
            }
            val end = when (type) {
                SegmentType.INTRO, SegmentType.RECAP -> weightedQuantile(ends, 0.35)
                else -> weightedMedian(ends)
            }
            if (!SegmentValidation.isValid(start, end, media.runtimeMs)) return@mapNotNull null
            val strongest = independent.maxBy { it.evidence.confidence }.evidence
            PlaybackSegment(
                id = MediaIdentityFactory.stableHash("${media.fingerprint}|$type|$start|$end|$CURRENT_ANALYSIS_VERSION"),
                type = type,
                startMs = start,
                endMs = end,
                confidence = confidence,
                confidenceLevel = config.level(confidence),
                source = if (independent.size > 1) SegmentEvidenceSource.CONSENSUS else strongest.source,
                evidence = cluster.map(TypedEvidence::evidence),
                mediaFingerprint = media.fingerprint,
                episodeId = media.canonicalEpisodeId,
                seasonId = seasonId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                version = CURRENT_ANALYSIS_VERSION,
            )
        }.sortedWith(compareBy(PlaybackSegment::startMs, PlaybackSegment::endMs))
    }

    private fun markCreditsOverContent(
        segments: List<PlaybackSegment>,
        overlayRanges: List<LongRange>,
    ): List<PlaybackSegment> = segments.map { segment ->
        if (segment.type !in setOf(SegmentType.CREDITS, SegmentType.FINAL_CREDITS)) return@map segment
        val overlay = overlayRanges.any { range -> range.first < segment.endMs && range.last >= segment.startMs }
        if (overlay) segment.copy(type = SegmentType.CONTENT, creditsOverlay = true, confidence = minOf(segment.confidence, 0.61), confidenceLevel = SegmentConfidence.LOW) else segment
    }

    private fun inferPostCreditSegments(
        segments: List<PlaybackSegment>,
        media: MediaIdentity,
        seasonId: String?,
        now: Long,
    ): List<PlaybackSegment> {
        val sorted = segments.sortedBy(PlaybackSegment::startMs).toMutableList()
        val creditSegments = sorted.filter { it.type in setOf(SegmentType.CREDITS, SegmentType.FINAL_CREDITS) && !it.creditsOverlay }
        creditSegments.zipWithNext().forEach { (left, right) ->
            if (left.endMs >= right.startMs) return@forEach
            val gapStart = left.endMs
            val gapEnd = right.startMs
            val explicitContent = sorted.any { it.type == SegmentType.CONTENT && it.startMs <= gapStart && it.endMs >= gapEnd }
            val dialogueOrMotion = false // platform observations classify explicit content before this stage
            if (explicitContent || dialogueOrMotion) {
                val confidence = minOf(left.confidence, right.confidence)
                sorted += PlaybackSegment(
                    id = MediaIdentityFactory.stableHash("${media.fingerprint}|post-credit|$gapStart|$gapEnd"),
                    type = SegmentType.POST_CREDIT,
                    startMs = gapStart,
                    endMs = gapEnd,
                    confidence = confidence,
                    confidenceLevel = config.level(confidence),
                    source = SegmentEvidenceSource.CONSENSUS,
                    evidence = left.evidence + right.evidence,
                    mediaFingerprint = media.fingerprint,
                    episodeId = media.canonicalEpisodeId,
                    seasonId = seasonId,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    version = CURRENT_ANALYSIS_VERSION,
                )
            }
        }
        return sorted.sortedBy(PlaybackSegment::startMs)
    }

    private fun boundariesAgree(a: SegmentEvidence, b: SegmentEvidence): Boolean =
        abs(a.startMs - b.startMs) <= config.boundaryAgreementMs * 2 &&
            abs(a.endMs - b.endMs) <= config.boundaryAgreementMs * 2

    private fun eofCreditReleaseVariants(
        first: TypedEvidence,
        second: TypedEvidence,
        runtimeMs: Long,
    ): Boolean {
        if (first.type !in setOf(SegmentType.CREDITS, SegmentType.FINAL_CREDITS)) return false
        if (second.type != first.type) return false
        val firstEndsAtEof = runtimeMs - first.evidence.endMs in 0..config.minorRuntimeVarianceMs
        val secondEndsAtEof = runtimeMs - second.evidence.endMs in 0..config.minorRuntimeVarianceMs
        return firstEndsAtEof && secondEndsAtEof &&
            abs(first.evidence.startMs - second.evidence.startMs) <= config.creditSourceVariantClusteringMs
    }

    private fun typeForChapter(evidence: SegmentEvidence): SegmentType {
        val label = evidence.details.orEmpty()
        Regex("normalized-type:([A-Z_]+)").find(label)?.groupValues?.getOrNull(1)?.let { encoded ->
            return runCatching { SegmentType.valueOf(encoded) }.getOrNull() ?: SegmentType.UNKNOWN
        }
        return when {
            "recap" in label || "previous" in label || "bisher" in label -> SegmentType.RECAP
            "opening" in label || "intro" in label || "vorspann" in label || "apertura" in label -> SegmentType.INTRO
            "final" in label || "abspann" in label -> SegmentType.FINAL_CREDITS
            "preview" in label || "next episode" in label || "vorschau" in label -> SegmentType.NEXT_EPISODE_PREVIEW
            "credit" in label || "ending" in label -> SegmentType.CREDITS
            else -> SegmentType.INTRO
        }
    }

    private fun inferVisualType(request: PlaybackSegmentRequest, evidence: SegmentEvidence): SegmentType =
        request.visualObservations.firstOrNull { it.startMs == evidence.startMs && it.endMs == evidence.endMs }
            ?.let { if (maxOf(it.creditTextDensity, it.scrollingTextScore) >= 0.7) SegmentType.CREDITS else SegmentType.INTRO }
            ?: SegmentType.UNKNOWN

    private fun alignWithAnchors(start: Long, end: Long, anchors: List<TimelineAlignmentAnchor>): Pair<Long, Long> {
        val ordered = anchors.filter { it.confidence >= 0.75 }.sortedBy(TimelineAlignmentAnchor::referenceMs)
        fun map(value: Long): Long {
            val rightIndex = ordered.indexOfFirst { it.referenceMs >= value }
            if (rightIndex <= 0) {
                val anchor = if (rightIndex == 0) ordered.first() else ordered.last()
                return value + (anchor.currentMs - anchor.referenceMs)
            }
            val right = ordered[rightIndex]
            val left = ordered[rightIndex - 1]
            if (right.referenceMs == left.referenceMs) return value + (left.currentMs - left.referenceMs)
            val progress = (value - left.referenceMs).toDouble() / (right.referenceMs - left.referenceMs)
            return (left.currentMs + progress * (right.currentMs - left.currentMs)).toLong()
        }
        return map(start) to map(end)
    }

    private fun isConstantSpeedConversion(referenceRuntimeMs: Long, currentRuntimeMs: Long): Boolean {
        if (referenceRuntimeMs <= 0L || currentRuntimeMs <= 0L) return false
        val ratio = currentRuntimeMs.toDouble() / referenceRuntimeMs
        val palToFilm = 25.0 / 24.0
        val filmToPal = 24.0 / 25.0
        return abs(ratio - palToFilm) < 0.0025 || abs(ratio - filmToPal) < 0.0025
    }

    private fun weightedMedian(values: List<Long>): Long = weightedQuantile(values, 0.5)

    private fun weightedQuantile(values: List<Long>, quantile: Double): Long {
        val sorted = values.sorted()
        val index = ((sorted.lastIndex) * quantile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun emptyAnalysis(reason: String) = PlaybackSegmentAnalysis(
        segments = emptyList(),
        fromCache = false,
        analysisVersion = CURRENT_ANALYSIS_VERSION,
        diagnostics = listOf(reason),
    )

    private data class TypedEvidence(val type: SegmentType, val evidence: SegmentEvidence)

}
