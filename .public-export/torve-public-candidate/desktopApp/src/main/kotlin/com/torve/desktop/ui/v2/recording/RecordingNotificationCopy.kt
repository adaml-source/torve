package com.torve.desktop.ui.v2.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFailureReason
import com.torve.desktop.recording.RecordingFolderIssue
import com.torve.desktop.recording.RecordingFolderValidator
import com.torve.desktop.recording.RecordingPathResult
import java.io.File

internal fun recordingFolderValidationError(path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isBlank()) {
        return "Set a Recordings Folder under Settings > Preferences > Downloads first."
    }
    val root = File(trimmed)
    return when (val result = RecordingFolderValidator.ensureWritableDirectory(root)) {
        is RecordingPathResult.Ready -> null
        is RecordingPathResult.Failed -> when (result.failure.issue) {
            RecordingFolderIssue.RECORDING_PATH_IS_FILE ->
                "Recording path points to a file. Choose a folder in Settings."
            RecordingFolderIssue.RECORDING_FOLDER_PERMISSION_DENIED ->
                "Torve does not have permission to write to the recordings folder."
            RecordingFolderIssue.RECORDING_FOLDER_NOT_WRITABLE ->
                "Recording folder is not writable. Choose another folder in Settings."
            RecordingFolderIssue.RECORDING_FOLDER_INVALID ->
                "Recording folder path is invalid. Choose another folder in Settings."
            RecordingFolderIssue.RECORDING_STORAGE_FULL_OR_UNAVAILABLE ->
                "Recording storage is full or unavailable."
            RecordingFolderIssue.FILE_WRITE_ERROR ->
                "Torve could not write to the recordings folder."
        }
    }
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
