package com.torve.desktop.ui.v2.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFailureReason
import java.io.File

internal fun recordingFolderValidationError(path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isBlank()) {
        return "Set a Recordings Folder under Settings > Preferences > Downloads first."
    }
    val root = File(trimmed)
    val ready = runCatching {
        if (root.exists()) root.isDirectory else root.mkdirs()
    }.getOrDefault(false)
    if (!ready) {
        return "Recording folder is unavailable: ${root.absolutePath}"
    }
    if (!root.canWrite()) {
        return "Recording folder is not writable: ${root.absolutePath}"
    }
    return null
}

internal fun recordingFailureNotification(row: Recording): String {
    val reason = when (row.failureReason) {
        RecordingFailureReason.OUT_OF_ALLOWLIST ->
            "recordings folder is not allowed"
        RecordingFailureReason.FILE_WRITE_ERROR ->
            "cannot write to the recordings folder"
        RecordingFailureReason.DISK_FULL ->
            "disk is full"
        RecordingFailureReason.UPSTREAM_REJECTED ->
            "the channel rejected the recording stream"
        RecordingFailureReason.NETWORK_ERROR ->
            "network error while reading the channel stream"
        RecordingFailureReason.CANCELLED_BY_USER ->
            "recording was cancelled"
        RecordingFailureReason.UNKNOWN,
        null ->
            "unknown error"
    }
    val detail = row.failureMessage
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(160)
    return if (detail == null) {
        "Recording failed: $reason."
    } else {
        "Recording failed: $reason. $detail"
    }
}
