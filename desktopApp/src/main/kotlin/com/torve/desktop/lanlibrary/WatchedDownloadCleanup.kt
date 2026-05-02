package com.torve.desktop.lanlibrary

import com.torve.domain.model.DownloadStatus
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.WatchHistoryRepository
import java.io.File

/**
 * Optional storage hygiene: deletes completed downloads the user has
 * actually watched, when explicitly invoked.
 *
 * **Boundary safety** (Prompt 9 acceptance):
 * the only files this helper deletes are those whose canonical path
 * sits strictly under one of the [DownloadFolderAllowlist] roots. A
 * download row whose `filePath` somehow drifted outside the allowlist
 * is left alone - we surface a [SkipReason] instead of attempting
 * deletion. This is the same fail-closed posture the LAN HTTP server
 * uses for streams.
 *
 * The helper is invoked manually (e.g. from a "Free up space" button
 * in V2 Settings); it is **not** wired to a scheduler in this slice.
 * Callers decide policy: "auto-delete watched after 7 days" can be
 * built on top by reading WatchHistoryEntry.watchedAt.
 */
class WatchedDownloadCleanup(
    private val downloadRepository: DownloadRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val allowlist: DownloadFolderAllowlist,
) {

    sealed interface CleanupOutcome {
        val mediaId: String
        val title: String
        data class Deleted(
            override val mediaId: String,
            override val title: String,
            val freedBytes: Long,
        ) : CleanupOutcome
        data class Skipped(
            override val mediaId: String,
            override val title: String,
            val reason: SkipReason,
        ) : CleanupOutcome
    }

    enum class SkipReason {
        NOT_COMPLETED,
        NOT_WATCHED,
        OUTSIDE_ALLOWLIST,
        FILE_MISSING,
        DELETE_FAILED,
    }

    /**
     * For every completed download the user has watched at least once,
     * delete the file from disk and remove the database row. Returns
     * one outcome per inspected row so the caller can render a
     * "freed N MB / skipped 2" summary.
     */
    suspend fun cleanWatched(): List<CleanupOutcome> {
        val rows = runCatching { downloadRepository.getCompletedDownloads() }
            .getOrDefault(emptyList())
            .filter { it.status == DownloadStatus.COMPLETED }
        return rows.map { row ->
            val candidatePath = row.filePath
            if (candidatePath.isNullOrBlank()) {
                return@map CleanupOutcome.Skipped(row.mediaId, row.title, SkipReason.FILE_MISSING)
            }
            // Strict allowlist gate - fail-closed. Even a stale row
            // pointing at a system path is left untouched.
            if (!allowlist.isAllowed(candidatePath)) {
                return@map CleanupOutcome.Skipped(row.mediaId, row.title, SkipReason.OUTSIDE_ALLOWLIST)
            }
            val watched = runCatching { watchHistoryRepository.getForMedia(row.mediaId) }
                .getOrDefault(emptyList())
                .isNotEmpty()
            if (!watched) {
                return@map CleanupOutcome.Skipped(row.mediaId, row.title, SkipReason.NOT_WATCHED)
            }
            val file = File(candidatePath)
            if (!file.exists() || !file.isFile) {
                return@map CleanupOutcome.Skipped(row.mediaId, row.title, SkipReason.FILE_MISSING)
            }
            val size = file.length()
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (!deleted) {
                return@map CleanupOutcome.Skipped(row.mediaId, row.title, SkipReason.DELETE_FAILED)
            }
            runCatching { downloadRepository.deleteDownload(row.id) }
            CleanupOutcome.Deleted(mediaId = row.mediaId, title = row.title, freedBytes = size)
        }
    }
}
