package com.torve.android.ui.player

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.torve.android.BuildConfig
import com.torve.data.addon.SourceContinuationSessionStore
import com.torve.domain.model.MediaType
import com.torve.domain.model.NextEpisodeMode
import com.torve.domain.model.WatchProgress
import com.torve.domain.player.MediaIdentityFactory
import com.torve.domain.player.MediaChapter
import com.torve.domain.player.MediaIdentity
import com.torve.domain.player.PlaybackSegment
import com.torve.domain.player.PlaybackSegmentEngine
import com.torve.domain.player.PlaybackSegmentRequest
import com.torve.domain.player.PlaybackSegmentSettings
import com.torve.domain.player.PlaybackSegmentStateMachine
import com.torve.domain.player.PlaybackSegmentUiState
import com.torve.domain.player.SegmentActionMode
import com.torve.domain.player.SegmentType
import com.torve.domain.player.SegmentBoundaryCorrection
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.settings.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class PlaybackSegmentRuntimeInput(
    val durationMs: Long,
    val positionMs: Long,
    val mediaId: String,
    val mediaType: MediaType,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val showTmdbId: Int?,
    val showImdbId: String?,
    val currentUrl: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
    val chapters: List<MediaChapter> = emptyList(),
)

internal fun playbackSegmentRuntimeInput(
    durationMs: Long,
    positionMs: Long,
    mediaId: String,
    mediaType: MediaType,
    seasonNumber: Int?,
    episodeNumber: Int?,
    showTmdbId: Int?,
    resolvedTmdbId: Int,
    showImdbId: String?,
    currentUrl: String,
    title: String,
    posterUrl: String,
    backdropUrl: String,
    chapters: List<MediaChapter>,
): PlaybackSegmentRuntimeInput = PlaybackSegmentRuntimeInput(
    durationMs = durationMs,
    positionMs = positionMs,
    mediaId = mediaId,
    mediaType = mediaType,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    showTmdbId = showTmdbId?.takeIf { it > 0 } ?: resolvedTmdbId.takeIf { it > 0 },
    showImdbId = showImdbId?.takeIf { it.isNotBlank() } ?: mediaId.extractImdbIdForSegments(),
    currentUrl = currentUrl,
    title = title,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    chapters = chapters,
)

private fun String.extractImdbIdForSegments(): String? =
    Regex("(?:^|[^A-Za-z0-9])(tt[0-9]{5,12})(?:$|[^0-9])", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()

@Stable
internal class PlaybackSegmentRuntimeController {
    var segments by mutableStateOf<List<PlaybackSegment>>(emptyList())
        private set
    var activeSkipSegment by mutableStateOf<PlaybackSegment?>(null)
    var uiState by mutableStateOf(PlaybackSegmentUiState())
    val stateMachine = PlaybackSegmentStateMachine()

    private var analysisKey: String? = null
    private var analysisJob: Job? = null
    private var completionPersisted = false
    private var currentIdentity: MediaIdentity? = null
    private var pendingCorrection: SegmentBoundaryCorrection? = null

    fun onPlaybackState(
        input: PlaybackSegmentRuntimeInput,
        settings: PlaybackSegmentSettings,
        engine: PlaybackSegmentEngine,
        watchProgressRepository: WatchProgressRepository,
        scope: CoroutineScope,
        onAutomaticSeek: (Long) -> Unit,
    ) {
        scheduleAnalysis(input, engine, scope)
        pendingCorrection?.let { correction ->
            currentIdentity?.let { identity ->
                pendingCorrection = null
                scope.launch {
                    engine.recordSkipUndo(identity, correction.segmentId, correction.suggestedEndMs)
                }
            }
        }
        val state = stateMachine.update(input.positionMs, input.durationMs, segments, settings)
        uiState = state
        val active = state.activeActionSegment?.takeIf { it.type == SegmentType.INTRO || it.type == SegmentType.RECAP }
        activeSkipSegment = active
        val mode = when (active?.type) {
            SegmentType.INTRO -> settings.introMode
            SegmentType.RECAP -> settings.recapMode
            else -> SegmentActionMode.OFF
        }
        active?.let { segment ->
            stateMachine.automaticSkipTarget(segment, mode)?.let { target ->
                onAutomaticSeek(target)
                activeSkipSegment = null
                if (BuildConfig.DEBUG) Log.d("PlaybackSegments", "auto-skip type=${segment.type} confidence=${segment.confidence}")
            }
        }
        if (state.markWatched && !completionPersisted && input.mediaId.isNotBlank() && input.durationMs > 0L) {
            completionPersisted = true
            scope.launch {
                watchProgressRepository.markWatchedAtMeaningfulContentEnd(
                    WatchProgress(
                        mediaId = input.mediaId,
                        mediaType = input.mediaType,
                        title = input.title,
                        posterUrl = input.posterUrl.takeIf(String::isNotBlank),
                        backdropUrl = input.backdropUrl.takeIf(String::isNotBlank),
                        positionMs = input.positionMs,
                        durationMs = input.durationMs,
                        seasonNumber = input.seasonNumber,
                        episodeNumber = input.episodeNumber,
                    ),
                )
            }
        }
    }

    fun dismissActiveAfterManualSkip() {
        activeSkipSegment = null
    }

    fun onManualSeek(fromMs: Long, toMs: Long) {
        if (toMs < fromMs && segments.any {
                it.type in setOf(SegmentType.INTRO, SegmentType.RECAP) && it.contains(toMs)
            }
        ) {
            stateMachine.suppressAutomaticSkipForSession()
        }
        pendingCorrection = stateMachine.onManualSeek(fromMs, toMs) ?: pendingCorrection
    }

    private fun scheduleAnalysis(input: PlaybackSegmentRuntimeInput, engine: PlaybackSegmentEngine, scope: CoroutineScope) {
        if (input.durationMs <= 0L || input.mediaType != MediaType.SERIES) return
        val episodeKey = "${input.mediaId}:s${input.seasonNumber ?: 0}e${input.episodeNumber ?: 0}"
        val chapterKey = input.chapters.joinToString("|") { "${it.title}:${it.startMs}:${it.endMs}" }
        val newKey = "$episodeKey|${input.currentUrl.substringBefore('#')}|${input.durationMs}|$chapterKey"
        if (analysisKey == newKey) return
        analysisKey = newKey
        completionPersisted = false
        stateMachine.resetForNewEpisode()
        if (input.positionMs > RESUME_ACTION_SUPPRESSION_POSITION_MS) {
            stateMachine.suppressAutomaticActionsForResume()
        }
        segments = emptyList()
        analysisJob?.cancel()
        analysisJob = scope.launch {
            val identity = MediaIdentityFactory.fromSourceProfile(
                canonicalEpisodeId = episodeKey,
                runtimeMs = input.durationMs,
                profile = SourceContinuationSessionStore.session.currentProfile(),
                fallbackSourceKey = input.currentUrl,
            )
            currentIdentity = identity
            val analysis = engine.analyze(
                PlaybackSegmentRequest(
                    media = identity,
                    seasonId = input.seasonNumber?.let { "${input.mediaId}:s$it" },
                    showTmdbId = input.showTmdbId,
                    showImdbId = input.showImdbId,
                    seasonNumber = input.seasonNumber,
                    episodeNumber = input.episodeNumber,
                    chapters = input.chapters,
                ),
            )
            segments = analysis.segments
            val markerSummary = segments.joinToString(limit = 8) {
                "${it.type}:${it.startMs}-${it.endMs}@${"%.2f".format(it.confidence)}"
            }
            Log.i(
                "PlaybackSegments",
                "season=${input.seasonNumber} episode=${input.episodeNumber} cache=${analysis.fromCache} " +
                    "segments=[$markerSummary] diagnostics=${analysis.diagnostics.joinToString()}",
            )
        }
    }

    private companion object {
        const val RESUME_ACTION_SUPPRESSION_POSITION_MS = 5_000L
    }
}

internal fun SettingsUiState.toPlaybackSegmentSettings() = PlaybackSegmentSettings(
    smartDetectionEnabled = smartSegmentDetectionEnabled,
    introMode = skipIntroMode,
    recapMode = skipRecapMode,
    playNextMode = playNextDuringCreditsMode,
    protectPostCreditScenes = protectPostCreditScenes,
)

/** Keeps detected-credit prompts independent from the legacy countdown timing choice. */
internal fun shouldShowNextEpisodePrompt(
    detectedCreditsPrompt: Boolean,
    hasSegments: Boolean,
    remainingMs: Long,
    nextEpisodeMode: NextEpisodeMode,
    creditsActionMode: SegmentActionMode,
): Boolean {
    if (nextEpisodeMode == NextEpisodeMode.OFF) return false
    if (detectedCreditsPrompt) return true
    return when (nextEpisodeMode) {
        NextEpisodeMode.AT_CREDITS -> creditsActionMode != SegmentActionMode.OFF &&
            !hasSegments && remainingMs in 1..10_000L
        NextEpisodeMode.AT_END -> remainingMs in 1..3_000L
        NextEpisodeMode.OFF -> false
    }
}

internal fun allowDetectedCreditsCountdown(
    segmentAllowsCountdown: Boolean,
    nextEpisodeMode: NextEpisodeMode,
): Boolean = segmentAllowsCountdown && nextEpisodeMode == NextEpisodeMode.AT_CREDITS
