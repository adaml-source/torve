package com.torve.android.player

import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState

internal data class LiveTuneSnapshot(
    val state: LiveTuneState = LiveTuneState.IDLE,
    val recoveryMode: LiveAudioRecoveryMode = LiveAudioRecoveryMode.NONE,
    val audioExpected: Boolean = false,
    val audioReady: Boolean = false,
    val videoReady: Boolean = false,
    val fallbackAllowed: Boolean = false,
)

internal class LiveTuneStateMachine(
    private val readinessTimeoutMs: Long,
) {
    private var snapshot = LiveTuneSnapshot()
    private var audioDeadlineElapsedMs: Long? = null

    fun snapshot(): LiveTuneSnapshot = snapshot

    fun begin(nowElapsedMs: Long): LiveTuneSnapshot {
        audioDeadlineElapsedMs = null
        snapshot = LiveTuneSnapshot(
            state = LiveTuneState.OPENING_MEDIA,
        )
        return snapshot
    }

    fun onTracksDiscovered(
        audioTrackCount: Int,
        selectedAudioTrack: Boolean,
        nowElapsedMs: Long,
    ): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        val audioExpected = audioTrackCount > 0
        snapshot = snapshot.copy(
            state = if (audioExpected) LiveTuneState.ANALYZING_TRACKS else LiveTuneState.BUFFERING_AV,
            audioExpected = audioExpected,
            audioReady = if (audioExpected) snapshot.audioReady && selectedAudioTrack else true,
            fallbackAllowed = false,
        )
        refreshDeadline(nowElapsedMs)
        return snapshot
    }

    fun onSelectingAudio(nowElapsedMs: Long): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        snapshot = snapshot.copy(
            state = LiveTuneState.SELECTING_AUDIO,
            audioReady = false,
            fallbackAllowed = false,
        )
        refreshDeadline(nowElapsedMs)
        return snapshot
    }

    fun onVideoReady(nowElapsedMs: Long): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        snapshot = snapshot.copy(videoReady = true)
        refreshDeadline(nowElapsedMs)
        return snapshot
    }

    fun onAudioReady(): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        audioDeadlineElapsedMs = null
        snapshot = snapshot.copy(audioReady = true)
        return snapshot
    }

    fun onPlaybackBuffering(nowElapsedMs: Long): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        if (snapshot.state == LiveTuneState.PLAYING_CONFIRMED) return snapshot
        snapshot = snapshot.copy(state = LiveTuneState.BUFFERING_AV)
        refreshDeadline(nowElapsedMs)
        return snapshot
    }

    fun onPlaybackReady(nowElapsedMs: Long): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        refreshDeadline(nowElapsedMs)
        return if (isConfirmed()) {
            audioDeadlineElapsedMs = null
            snapshot = snapshot.copy(
                state = LiveTuneState.PLAYING_CONFIRMED,
                recoveryMode = LiveAudioRecoveryMode.NONE,
                fallbackAllowed = false,
            )
            snapshot
        } else {
            snapshot = snapshot.copy(state = LiveTuneState.BUFFERING_AV)
            snapshot
        }
    }

    fun onRecoveryStarted(
        recoveryMode: LiveAudioRecoveryMode,
        nowElapsedMs: Long,
    ): LiveTuneSnapshot {
        if (snapshot.fallbackAllowed) return snapshot
        snapshot = snapshot.copy(
            state = LiveTuneState.AUDIO_RECOVERY_RETRY,
            recoveryMode = recoveryMode,
            audioReady = false,
            fallbackAllowed = false,
        )
        refreshDeadline(nowElapsedMs)
        return snapshot
    }

    fun onFailure(fallbackAllowed: Boolean): LiveTuneSnapshot {
        audioDeadlineElapsedMs = null
        snapshot = snapshot.copy(
            state = if (fallbackAllowed) LiveTuneState.FALLBACK_ALLOWED else LiveTuneState.FAILED_EXOPLAYER,
            fallbackAllowed = fallbackAllowed,
        )
        return snapshot
    }

    fun isAudioReadinessTimedOut(nowElapsedMs: Long): Boolean {
        val deadline = audioDeadlineElapsedMs ?: return false
        if (!snapshot.audioExpected || snapshot.audioReady || !snapshot.videoReady) {
            return false
        }
        return nowElapsedMs >= deadline
    }

    private fun refreshDeadline(nowElapsedMs: Long) {
        audioDeadlineElapsedMs = if (snapshot.audioExpected && !snapshot.audioReady && snapshot.videoReady) {
            nowElapsedMs + readinessTimeoutMs
        } else {
            null
        }
    }

    private fun isConfirmed(): Boolean {
        return snapshot.videoReady && (!snapshot.audioExpected || snapshot.audioReady)
    }
}

internal data class LiveAudioTrackCandidate(
    val id: String,
    val language: String? = null,
    val label: String? = null,
    val formatKey: String? = null,
    val channelCount: Int? = null,
    val bitrate: Int? = null,
    val supported: Boolean = true,
    val selected: Boolean = false,
    val isDefault: Boolean = false,
    val groupIndex: Int? = null,
    val trackIndex: Int? = null,
)

internal object LiveTrackSelectionPolicy {
    fun selectBestCandidate(
        candidates: List<LiveAudioTrackCandidate>,
        cachedTrack: LiveAudioTrackHint?,
        currentSelectionId: String?,
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode,
        excludeIds: Set<String> = emptySet(),
    ): LiveAudioTrackCandidate? {
        if (candidates.isEmpty()) return null
        return candidates
            .asSequence()
            .filterNot { it.id in excludeIds }
            .sortedByDescending { candidate ->
                candidateScore(
                    candidate = candidate,
                    cachedTrack = cachedTrack,
                    currentSelectionId = currentSelectionId,
                    passthroughEnabled = passthroughEnabled,
                    preferSurround = preferSurround,
                    outputMode = outputMode,
                )
            }
            .firstOrNull()
    }

    private fun candidateScore(
        candidate: LiveAudioTrackCandidate,
        cachedTrack: LiveAudioTrackHint?,
        currentSelectionId: String?,
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode,
    ): Int {
        var score = 0
        val isCurrentSelection = currentSelectionId != null && currentSelectionId == candidate.id
        if (candidate.supported) score += 500 else score -= 200
        if (candidate.isDefault) score += 24
        if (candidate.selected && isCurrentSelection) score -= 600

        val normalizedFormat = candidate.formatKey?.lowercase()
        score += when (normalizedFormat) {
            "audio/aac",
            "audio/mp4a-latm" -> 180
            "audio/opus" -> 172
            "audio/mp2",
            "audio/mpeg-l2",
            "mp2",
            "mpeg-l2" -> 168
            "audio/mpeg" -> 164
            "audio/raw" -> 160
            "audio/flac" -> 150
            "audio/vorbis" -> 144
            "audio/ac4" -> if (passthroughEnabled) 136 else 118
            "audio/eac3",
            "audio/eac3-joc" -> when (outputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 26
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 74
                LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 152 else 90
            }
            "audio/ac3" -> when (outputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 22
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 68
                LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 146 else 82
            }
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd",
            "audio/mlp" -> when (outputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> -40
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 18
                LiveAudioOutputMode.AUTO -> if (passthroughEnabled || preferSurround) 170 else 24
            }
            null -> -12
            else -> 42
        }

        score += when {
            candidate.channelCount == null -> 0
            candidate.channelCount <= 2 -> 36
            outputMode == LiveAudioOutputMode.FORCE_STEREO_PCM -> -120
            outputMode == LiveAudioOutputMode.PREFER_COMPATIBLE -> -24
            else -> 12
        }

        if (!candidate.label.isNullOrBlank()) score += 4
        if (!candidate.language.isNullOrBlank()) score += 3
        if ((candidate.bitrate ?: 0) in 1..320_000) score += 8

        if (cachedTrack != null && !isCurrentSelection) {
            if (candidate.groupIndex != null && candidate.trackIndex != null &&
                candidate.groupIndex == cachedTrack.groupIndex &&
                candidate.trackIndex == cachedTrack.trackIndex
            ) {
                score += 400
            }
            if (candidate.formatKey == cachedTrack.formatKey) score += 120
            if (candidate.language.equals(cachedTrack.language, ignoreCase = true)) score += 60
            if (candidate.label.equals(cachedTrack.label, ignoreCase = true)) score += 40
            if (candidate.channelCount == cachedTrack.channelCount) score += 20
        }

        return score
    }
}

internal enum class ExoAudioRecoveryAction {
    SWITCH_TRACK,
    DISABLE_PASSTHROUGH,
    PREFER_SOFTWARE_AUDIO,
    FORCE_STEREO_PCM,
    MARK_FAILED,
}

internal data class AudioRecoveryPlanInput(
    val alternateTrackAvailable: Boolean,
    val alternateTrackAttempted: Boolean,
    val compatibleModeAttempted: Boolean,
    val softwareAudioAttempted: Boolean,
    val stereoPcmAttempted: Boolean,
    val passthroughEnabled: Boolean,
    val preferSurround: Boolean,
    val outputMode: LiveAudioOutputMode,
)

internal object AudioTrackRecoveryPlanner {
    fun nextAction(input: AudioRecoveryPlanInput): ExoAudioRecoveryAction {
        if (input.alternateTrackAvailable && !input.alternateTrackAttempted) {
            return ExoAudioRecoveryAction.SWITCH_TRACK
        }
        if (!input.compatibleModeAttempted &&
            (input.passthroughEnabled || input.preferSurround || input.outputMode == LiveAudioOutputMode.AUTO)
        ) {
            return ExoAudioRecoveryAction.DISABLE_PASSTHROUGH
        }
        if (!input.softwareAudioAttempted) {
            return ExoAudioRecoveryAction.PREFER_SOFTWARE_AUDIO
        }
        if (!input.stereoPcmAttempted && input.outputMode != LiveAudioOutputMode.FORCE_STEREO_PCM) {
            return ExoAudioRecoveryAction.FORCE_STEREO_PCM
        }
        return ExoAudioRecoveryAction.MARK_FAILED
    }
}
