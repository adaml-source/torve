package com.torve.domain.player

import kotlinx.serialization.Serializable

@Serializable
enum class SegmentType {
    UNKNOWN,
    COLD_OPEN,
    RECAP,
    INTRO,
    CONTENT,
    MIDROLL,
    CREDITS,
    POST_CREDIT,
    FINAL_CREDITS,
    NEXT_EPISODE_PREVIEW,
}

@Serializable
enum class SegmentEvidenceSource {
    EMBEDDED_CHAPTER,
    COMMUNITY_DATABASE,
    SOURCE_FINGERPRINT,
    AUDIO_FINGERPRINT,
    SUBTITLE_ANALYSIS,
    VISUAL_ANALYSIS,
    METADATA,
    HEURISTIC,
    USER_VALIDATED,
    CONSENSUS,
}

@Serializable
enum class SegmentConfidence {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH,
}

@Serializable
data class SegmentEvidence(
    val source: SegmentEvidenceSource,
    val detectorId: String,
    val detectorVersion: Int,
    val confidence: Double,
    /** Evidence sharing a correlation key is counted only once during fusion. */
    val correlationKey: String,
    val startMs: Long,
    val endMs: Long,
    val referenceRuntimeMs: Long? = null,
    val referenceFingerprint: String? = null,
    val details: String? = null,
)

@Serializable
data class PlaybackSegment(
    val id: String,
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double,
    val confidenceLevel: SegmentConfidence,
    val source: SegmentEvidenceSource,
    val evidence: List<SegmentEvidence>,
    val mediaFingerprint: String? = null,
    val episodeId: String? = null,
    val seasonId: String? = null,
    /** Credit text is present over meaningful content; this is never safe for auto-transition. */
    val creditsOverlay: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val version: Int,
) {
    val durationMs: Long get() = endMs - startMs

    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

@Serializable
data class MediaIdentity(
    val canonicalEpisodeId: String,
    val runtimeMs: Long,
    val releaseName: String? = null,
    val releaseGroup: String? = null,
    val releaseFamily: String? = null,
    val resolutionHeight: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: String? = null,
    val frameRate: Double? = null,
    val edition: String? = null,
    val provider: String? = null,
    val exactStreamHash: String? = null,
    val fileIndex: Int? = null,
    val fingerprint: String,
)

data class TimelineAlignmentAnchor(
    val referenceMs: Long,
    val currentMs: Long,
    val confidence: Double,
)

data class PlaybackSegmentRequest(
    val media: MediaIdentity,
    val seasonId: String? = null,
    /** Canonical show IDs used only for provider lookup; never derived from a stream URL. */
    val showTmdbId: Int? = null,
    val showImdbId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val chapters: List<MediaChapter> = emptyList(),
    val subtitleCues: List<SubtitleCueObservation> = emptyList(),
    val audioMatches: List<AudioMatchObservation> = emptyList(),
    val visualObservations: List<VisualSegmentObservation> = emptyList(),
    val seasonPrior: SeasonSegmentPrior? = null,
    val alignmentAnchors: List<TimelineAlignmentAnchor> = emptyList(),
)

data class MediaChapter(val title: String, val startMs: Long, val endMs: Long)

data class SubtitleCueObservation(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String? = null,
    val isDialogue: Boolean = true,
)

data class AudioMatchObservation(
    val startMs: Long,
    val endMs: Long,
    val similarity: Double,
    val matchingEpisodeCount: Int,
    val trackLanguage: String? = null,
    val trackId: String? = null,
    val classification: SegmentType? = null,
)

data class VisualSegmentObservation(
    val startMs: Long,
    val endMs: Long,
    val repeatedSequenceScore: Double = 0.0,
    val creditTextDensity: Double = 0.0,
    val scrollingTextScore: Double = 0.0,
    val normalSceneMotionScore: Double = 0.0,
    val dialogueActive: Boolean = false,
    val classification: SegmentType? = null,
)

data class SeasonSegmentPrior(
    val type: SegmentType,
    val expectedStartMs: Long,
    val expectedEndMs: Long,
    val episodeCount: Int,
    val dispersionMs: Long,
)

data class ProviderMarker(
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double,
    val providerId: String,
    val providerVersion: Int,
    val referenceRuntimeMs: Long,
    val referenceFingerprint: String? = null,
    /** False when the provider did not supply a trustworthy source-runtime boundary. */
    val referenceRuntimeReliable: Boolean = true,
)

interface SegmentMarkerProvider {
    val id: String
    suspend fun getSegments(request: PlaybackSegmentRequest): List<ProviderMarker>
}

data class PlaybackSegmentAnalysis(
    val segments: List<PlaybackSegment>,
    val fromCache: Boolean,
    val analysisVersion: Int,
    val diagnostics: List<String> = emptyList(),
)

enum class SegmentActionMode { OFF, SHOW_BUTTON, AUTOMATIC }

data class PlaybackSegmentSettings(
    val smartDetectionEnabled: Boolean = true,
    val introMode: SegmentActionMode = SegmentActionMode.SHOW_BUTTON,
    val recapMode: SegmentActionMode = SegmentActionMode.SHOW_BUTTON,
    val playNextMode: SegmentActionMode = SegmentActionMode.SHOW_BUTTON,
    val protectPostCreditScenes: Boolean = true,
)
