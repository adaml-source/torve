package com.torve.presentation.apple

import com.torve.data.addon.SourceContinuationSessionStore
import com.torve.data.addon.continuationSourceKey
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.model.MediaType
import com.torve.domain.model.NextEpisodeMode
import com.torve.domain.player.*
import com.torve.presentation.settings.SettingsUiState

data class ApplePlaybackSegmentDecision(
    val state: PlaybackSegmentUiState,
    val automaticSeekMs: Long? = null,
    val showNext: Boolean = false,
    val automaticNext: Boolean = false,
)

/** Uses Android's existing detector and state machine, including undo/resume protection. */
class ApplePlaybackSegments(
    private val ids: UserIdProvider,
    private val sessions: ApplePlaybackSessions,
    private val token: String,
    private val engine: PlaybackSegmentEngine,
    private val preferences: () -> SettingsUiState,
) {
    private val owner = ids.currentDataUserId()
    private val machine = PlaybackSegmentStateMachine()
    private var media: MediaIdentity? = null
    private var watched = false
    private var nextCancelled = false
    private var resumed = false
    var segments: List<PlaybackSegment> = emptyList()
        private set
    private fun identity() = sessions.identity(token).takeIf { ids.currentDataUserId() == owner }

    @Throws(Exception::class)
    suspend fun analyze(durationMs: Long, sourceUrl: String, chapters: List<MediaChapter>): List<PlaybackSegment> {
        val item = identity() ?: return emptyList()
        if (item.mediaType != MediaType.SERIES || item.season == null || item.episode == null || durationMs <= 0) return emptyList()
        machine.resetForNewEpisode()
        resumed = sessions.startPositionMs(token) > 5_000
        if (resumed) machine.suppressAutomaticActionsForResume()
        if (!preferences().smartSegmentDetectionEnabled) return emptyList()
        val key = "${item.mediaId}:s${item.season}e${item.episode}"
        val profile = SourceContinuationSessionStore.session.currentProfile()?.takeIf { current ->
            current.seasonNumber == item.season && current.episodeNumber == item.episode &&
                sessions.sourceSnapshot(token)?.streams.orEmpty().any { continuationSourceKey(it) == current.sourceKey }
        }
        val mediaIdentity = MediaIdentityFactory.fromSourceProfile(key, durationMs, profile, sourceUrl)
        media = mediaIdentity
        val result = ids.withPersonalDataScope {
            engine.analyze(PlaybackSegmentRequest(media = mediaIdentity, seasonId = "${item.mediaId}:s${item.season}",
                showTmdbId = sessions.sourceSnapshot(token)?.mediaItem?.tmdbId,
                showImdbId = item.imdbId, seasonNumber = item.season, episodeNumber = item.episode, chapters = chapters))
        }
        if (identity() == null) return emptyList()
        segments = result.segments
        return segments
    }
    fun update(positionMs: Long, durationMs: Long, automaticActions: Boolean): ApplePlaybackSegmentDecision {
        if (identity() == null) return ApplePlaybackSegmentDecision(PlaybackSegmentUiState())
        val prefs = preferences()
        val options = PlaybackSegmentSettings(prefs.smartSegmentDetectionEnabled, prefs.skipIntroMode,
            prefs.skipRecapMode, prefs.playNextDuringCreditsMode, prefs.protectPostCreditScenes)
        val state = machine.update(positionMs, durationMs, segments, options)
        val mode = when (state.activeActionSegment?.type) {
            SegmentType.INTRO -> options.introMode
            SegmentType.RECAP -> options.recapMode
            else -> SegmentActionMode.OFF
        }
        val seek = state.activeActionSegment?.takeIf { automaticActions }?.let { machine.automaticSkipTarget(it, mode) }
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        val next = !nextCancelled && prefs.nextEpisodeMode != NextEpisodeMode.OFF && when (prefs.nextEpisodeMode) {
            NextEpisodeMode.AT_CREDITS -> state.showNextPrompt ||
                (segments.isEmpty() && options.playNextMode != SegmentActionMode.OFF && remaining in 1..10_000)
            NextEpisodeMode.AT_END -> remaining in 1..3_000
            NextEpisodeMode.OFF -> false
        }
        val automatic = next && !resumed && prefs.autoPlayNextEpisodeEnabled &&
            (prefs.nextEpisodeMode == NextEpisodeMode.AT_END ||
                (state.allowNextCountdown && prefs.nextEpisodeMode == NextEpisodeMode.AT_CREDITS))
        return ApplePlaybackSegmentDecision(state, seek, next, automatic)
    }
    fun manualSkipTarget(): Long? {
        if (identity() == null) return null
        val active = updatePositionSegment ?: return null
        return machine.manualSkipTarget(active)
    }
    private var updatePositionSegment: PlaybackSegment? = null
    fun skipAt(positionMs: Long, durationMs: Long): Long? {
        if (identity() == null) return null
        val prefs = preferences()
        updatePositionSegment = machine.update(positionMs, durationMs, segments,
            PlaybackSegmentSettings(prefs.smartSegmentDetectionEnabled, prefs.skipIntroMode, prefs.skipRecapMode,
                prefs.playNextDuringCreditsMode, prefs.protectPostCreditScenes)).activeActionSegment
        return manualSkipTarget()
    }
    @Throws(Exception::class)
    suspend fun manualSeek(fromMs: Long, toMs: Long) {
        if (identity() == null) return
        if (toMs < fromMs && segments.any { it.type in setOf(SegmentType.INTRO, SegmentType.RECAP) && it.contains(toMs) }) {
            machine.suppressAutomaticSkipForSession()
        }
        if (toMs < fromMs) nextCancelled = true
        val correction = machine.onManualSeek(fromMs, toMs) ?: return
        val identity = media ?: return
        ids.withPersonalDataScope { engine.recordSkipUndo(identity, correction.segmentId, correction.suggestedEndMs) }
    }
    fun cancelNext() { nextCancelled = true; machine.cancelNextPrompt() }
    @Throws(Exception::class)
    suspend fun markWatched(positionMs: Long, durationMs: Long) {
        if (watched || identity() == null) return
        watched = sessions.markMeaningfulWatched(token, positionMs, durationMs)
    }
}
