package com.torve.android.player

import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.LiveAudioRecoveryMode
import com.torve.domain.player.LiveTuneState
import com.torve.domain.player.TrackDescription

internal data class LiveAudioCompatibilityFailureReport(
    val selectedEngine: LivePlayerEngineId,
    val playbackContext: LiveAudioPlaybackContext?,
    val selectedMime: String?,
    val trackCount: Int,
    val selectedTrack: LiveAudioTrackHint?,
    val passthroughEnabled: Boolean,
    val outputMode: LiveAudioOutputMode,
    val preferSurround: Boolean,
    val recoveryAttempts: Int,
    val diagnostics: String,
    val fallbackAllowed: Boolean = false,
    val recoveryMode: LiveAudioRecoveryMode = LiveAudioRecoveryMode.NONE,
    val tuneState: LiveTuneState = LiveTuneState.IDLE,
)

internal enum class LiveAudioClientSurface(val storageValue: String) {
    MOBILE("mobile"),
    TV("tv"),
}

internal data class LiveAudioPathSnapshot(
    val surface: LiveAudioClientSurface,
    val engineId: LivePlayerEngineId,
    val channelName: String,
    val trackCount: Int,
    val selectedTrack: TrackDescription?,
    val audioTracks: List<TrackDescription>,
    val passthroughEnabled: Boolean,
    val preferSurround: Boolean,
    val outputMode: LiveAudioOutputMode,
    val rememberedHint: LiveAudioCompatibilityHint?,
    val tuneState: LiveTuneState = LiveTuneState.IDLE,
    val recoveryMode: LiveAudioRecoveryMode = LiveAudioRecoveryMode.NONE,
    val note: String? = null,
)

internal fun buildLiveAudioPathLog(snapshot: LiveAudioPathSnapshot): String {
    return buildString {
        append("surface=")
        append(snapshot.surface.storageValue)
        append(" engine=")
        append(snapshot.engineId.storageValue)
        append(" channel=")
        append(snapshot.channelName)
        append(" trackCount=")
        append(snapshot.trackCount)
        append(" selectedTrack=")
        append(snapshot.selectedTrack.toDiagnosticSummary())
        append(" passthrough=")
        append(snapshot.passthroughEnabled)
        append(" preferSurround=")
        append(snapshot.preferSurround)
        append(" outputMode=")
        append(snapshot.outputMode.storageValue)
        append(" rememberedHint=")
        append(snapshot.rememberedHint.toDiagnosticSummary())
        append(" tuneState=")
        append(snapshot.tuneState.name)
        append(" recoveryMode=")
        append(snapshot.recoveryMode.name)
        append(" inventory=")
        append(snapshot.audioTracks.joinToString(separator = " | ") { it.toDiagnosticSummary() }.ifBlank { "none" })
        if (!snapshot.note.isNullOrBlank()) {
            append(" note=")
            append(snapshot.note)
        }
    }
}

internal fun TrackDescription?.toDiagnosticSummary(): String {
    if (this == null) return "none"
    return listOf(
        "id=$id",
        "label=${label.ifBlank { "n/a" }}",
        "lang=${language ?: "und"}",
        "format=${formatHint ?: "unknown"}",
        "channels=${channelCount ?: -1}",
        "selected=$isSelected",
    ).joinToString(separator = ",")
}

internal fun LiveAudioCompatibilityHint?.toDiagnosticSummary(): String {
    if (this == null) return "none"
    return listOf(
        "engine=${preferredEngine ?: "none"}",
        "track=${preferredTrack?.formatKey ?: "none"}",
        "mode=$outputMode",
        "passthrough=$passthroughEnabled",
        "preferSurround=$preferSurround",
        "kind=${recoveryKind.name}",
    ).joinToString(separator = ",")
}
