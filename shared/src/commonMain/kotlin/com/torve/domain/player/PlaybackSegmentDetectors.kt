package com.torve.domain.player

import kotlin.math.min

object EmbeddedChapterSegmentDetector {
    private val recapLabels = listOf("previously on", "previously", "recap", "last time", "was bisher geschah", "précédemment", "anteriormente", "riassunto")
    private val introLabels = listOf("intro", "opening", "opening credits", "op", "vorspann", "générique début", "apertura")
    private val finalCreditLabels = listOf("final credits", "end credits", "ending credits", "abspann", "générique fin", "créditos finales")
    private val creditLabels = listOf("credits", "ending", "end theme", "ed", "créditos", "générique")
    private val previewLabels = listOf("preview", "next episode", "next time", "vorschau", "prochain épisode", "avance")

    fun detect(chapters: List<MediaChapter>, durationMs: Long): List<SegmentEvidence> = chapters.mapNotNull { chapter ->
        val label = chapter.title.trim().lowercase()
        val type = when {
            recapLabels.any(label::contains) -> SegmentType.RECAP
            finalCreditLabels.any(label::contains) -> SegmentType.FINAL_CREDITS
            introLabels.any(label::contains) -> SegmentType.INTRO
            previewLabels.any(label::contains) -> SegmentType.NEXT_EPISODE_PREVIEW
            creditLabels.any(label::contains) -> SegmentType.CREDITS
            else -> null
        } ?: return@mapNotNull null
        if (!SegmentValidation.isValid(chapter.startMs, chapter.endMs, durationMs)) return@mapNotNull null
        val plausibleDuration = chapter.endMs - chapter.startMs <= when (type) {
            SegmentType.RECAP, SegmentType.INTRO -> 8 * 60_000L
            else -> 15 * 60_000L
        }
        if (!plausibleDuration) return@mapNotNull null
        SegmentEvidence(
            source = SegmentEvidenceSource.EMBEDDED_CHAPTER,
            detectorId = "embedded-chapter",
            detectorVersion = 1,
            confidence = 0.76,
            correlationKey = "container-chapters",
            startMs = chapter.startMs,
            endMs = chapter.endMs,
            referenceRuntimeMs = durationMs,
            details = "normalized-type:${type.name};label:$label",
        )
    }
}

object SubtitleSegmentDetector {
    private val recapPhrases = listOf(
        "previously on", "last time on", "earlier on", "was bisher geschah",
        "précédemment dans", "anteriormente en", "nelle puntate precedenti",
    )

    fun detect(cues: List<SubtitleCueObservation>, durationMs: Long): List<SegmentEvidence> {
        val cue = cues.asSequence()
            .filter { it.startMs <= min(durationMs, 10 * 60_000L) }
            .firstOrNull { candidate -> recapPhrases.any(candidate.text.lowercase()::contains) }
            ?: return emptyList()
        val end = cues.asSequence()
            .filter { it.startMs >= cue.startMs && it.startMs <= cue.startMs + 8 * 60_000L }
            .take(12)
            .maxOfOrNull(SubtitleCueObservation::endMs)
            ?: cue.endMs
        return listOf(
            SegmentEvidence(
                source = SegmentEvidenceSource.SUBTITLE_ANALYSIS,
                detectorId = "subtitle-recap-phrase",
                detectorVersion = 1,
                confidence = 0.48,
                correlationKey = "subtitle-track:${cue.language ?: "unknown"}",
                startMs = cue.startMs,
                endMs = end,
                referenceRuntimeMs = durationMs,
                details = "localized-recap-phrase",
            ),
        )
    }
}

object AudioFingerprintSegmentDetector {
    fun detect(matches: List<AudioMatchObservation>, durationMs: Long): List<SegmentEvidence> = matches.mapNotNull { match ->
        if (match.matchingEpisodeCount < 2 || match.similarity < 0.78) return@mapNotNull null
        if (!SegmentValidation.isValid(match.startMs, match.endMs, durationMs)) return@mapNotNull null
        val type = match.classification ?: SegmentType.INTRO
        if (type !in setOf(SegmentType.INTRO, SegmentType.CREDITS, SegmentType.FINAL_CREDITS)) return@mapNotNull null
        val confidence = (0.38 + match.similarity * 0.34 + min(match.matchingEpisodeCount, 5) * 0.035)
            .coerceAtMost(0.82)
        SegmentEvidence(
            source = SegmentEvidenceSource.AUDIO_FINGERPRINT,
            detectorId = "repeated-audio",
            detectorVersion = 1,
            confidence = confidence,
            correlationKey = "audio:${match.trackLanguage ?: "unknown"}:${match.trackId ?: "default"}",
            startMs = match.startMs,
            endMs = match.endMs,
            referenceRuntimeMs = durationMs,
            details = "episodes=${match.matchingEpisodeCount};similarity=${match.similarity}",
        )
    }
}

data class VisualDetectionResult(
    val evidence: List<SegmentEvidence>,
    val creditsOverContent: List<LongRange>,
)

object VisualSegmentDetector {
    fun detect(observations: List<VisualSegmentObservation>, durationMs: Long): VisualDetectionResult {
        val overlays = mutableListOf<LongRange>()
        val evidence = observations.mapNotNull { sample ->
            if (!SegmentValidation.isValid(sample.startMs, sample.endMs, durationMs)) return@mapNotNull null
            val looksLikeCredits = sample.creditTextDensity >= 0.7 || sample.scrollingTextScore >= 0.7
            if (looksLikeCredits && (sample.dialogueActive || sample.normalSceneMotionScore >= 0.65)) {
                overlays += sample.startMs until sample.endMs
                return@mapNotNull null
            }
            val type = sample.classification ?: when {
                sample.repeatedSequenceScore >= 0.82 -> SegmentType.INTRO
                looksLikeCredits -> SegmentType.CREDITS
                else -> return@mapNotNull null
            }
            val score = when (type) {
                SegmentType.INTRO -> 0.42 + sample.repeatedSequenceScore * 0.38
                SegmentType.CREDITS, SegmentType.FINAL_CREDITS ->
                    0.38 + maxOf(sample.creditTextDensity, sample.scrollingTextScore) * 0.36
                else -> 0.45
            }.coerceAtMost(0.80)
            SegmentEvidence(
                source = SegmentEvidenceSource.VISUAL_ANALYSIS,
                detectorId = "sampled-visual-structure",
                detectorVersion = 1,
                confidence = score,
                correlationKey = "sampled-frames",
                startMs = sample.startMs,
                endMs = sample.endMs,
                referenceRuntimeMs = durationMs,
                details = "sampled-window",
            )
        }
        return VisualDetectionResult(evidence, overlays)
    }
}

object SegmentValidation {
    fun isValid(startMs: Long, endMs: Long, durationMs: Long, toleranceMs: Long = 1_500L): Boolean =
        startMs >= 0L && startMs < endMs && durationMs > 0L && endMs <= durationMs + toleranceMs
}
