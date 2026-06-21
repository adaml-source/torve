package com.torve.desktop.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFileNaming
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path

internal enum class RecordingFolderIssue {
    RECORDING_FOLDER_INVALID,
    RECORDING_PATH_IS_FILE,
    RECORDING_FOLDER_PERMISSION_DENIED,
    RECORDING_FOLDER_NOT_WRITABLE,
    RECORDING_STORAGE_FULL_OR_UNAVAILABLE,
    FILE_WRITE_ERROR,
}

internal data class RecordingFolderFailure(
    val issue: RecordingFolderIssue,
    val folder: File?,
    val message: String,
)

internal sealed interface RecordingPathResult {
    data class Ready(val file: File) : RecordingPathResult
    data class Failed(val failure: RecordingFolderFailure) : RecordingPathResult
}

internal object RecordingPathResolver {

    fun resolve(root: File, recording: Recording): RecordingPathResult {
        val rootReady = RecordingFolderValidator.ensureWritableDirectory(root)
        if (rootReady is RecordingPathResult.Failed) return rootReady

        val relative = RecordingFileNaming.relativePath(
            channelName = recording.channelName,
            programmeTitle = recording.displayTitle,
            startEpochMs = recording.startMs,
        )
        val target = File(root, relative)
        val parent = target.parentFile ?: return RecordingPathResult.Failed(
            RecordingFolderFailure(
                issue = RecordingFolderIssue.RECORDING_FOLDER_INVALID,
                folder = root,
                message = "Code: RECORDING_FOLDER_INVALID; Folder: ${root.absolutePath}",
            ),
        )

        val parentReady = RecordingFolderValidator.ensureWritableDirectory(parent)
        if (parentReady is RecordingPathResult.Failed) return parentReady

        return RecordingPathResult.Ready(uniqueFile(target))
    }

    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val parent = target.parentFile ?: return target
        val name = target.nameWithoutExtension
        val ext = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 2
        while (true) {
            val candidate = File(parent, "$name ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}

internal object RecordingFolderValidator {

    fun ensureWritableDirectory(dir: File): RecordingPathResult {
        val path = runCatching { dir.toPath().toAbsolutePath().normalize() }.getOrElse {
            return failed(RecordingFolderIssue.RECORDING_FOLDER_INVALID, dir)
        }

        return try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return failed(RecordingFolderIssue.RECORDING_PATH_IS_FILE, dir)
            }
            Files.createDirectories(path)
            if (!Files.isDirectory(path)) {
                return failed(RecordingFolderIssue.RECORDING_FOLDER_INVALID, dir)
            }
            // Windows Explorer's folder Properties checkbox can set a
            // directory "read-only" marker that only applies to contained
            // files. Do not treat that attribute as a write denial. The
            // only reliable check here is whether Torve can create and
            // delete an actual file in the selected folder.
            if (!canWriteTempFile(path)) {
                return failed(RecordingFolderIssue.RECORDING_FOLDER_NOT_WRITABLE, dir)
            }
            RecordingPathResult.Ready(path.toFile())
        } catch (_: AccessDeniedException) {
            failed(RecordingFolderIssue.RECORDING_FOLDER_PERMISSION_DENIED, dir)
        } catch (_: SecurityException) {
            failed(RecordingFolderIssue.RECORDING_FOLDER_PERMISSION_DENIED, dir)
        } catch (t: Throwable) {
            val issue = if (t.message?.contains("space", ignoreCase = true) == true) {
                RecordingFolderIssue.RECORDING_STORAGE_FULL_OR_UNAVAILABLE
            } else {
                RecordingFolderIssue.FILE_WRITE_ERROR
            }
            failed(issue, dir)
        }
    }

    private fun canWriteTempFile(path: Path): Boolean {
        val tmp = try {
            Files.createTempFile(path, ".torve-recording-write-test-", ".tmp")
        } catch (_: AccessDeniedException) {
            return false
        } catch (_: SecurityException) {
            return false
        } catch (_: Throwable) {
            return false
        }
        return runCatching { Files.deleteIfExists(tmp) }.isSuccess
    }

    private fun failed(issue: RecordingFolderIssue, folder: File?): RecordingPathResult.Failed =
        RecordingPathResult.Failed(
            RecordingFolderFailure(
                issue = issue,
                folder = folder,
                message = "Code: ${issue.name}; Folder: ${folder?.absolutePath.orEmpty()}",
            ),
        )
}
