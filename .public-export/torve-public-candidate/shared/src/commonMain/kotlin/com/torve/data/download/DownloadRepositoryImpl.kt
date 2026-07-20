package com.torve.data.download

import com.torve.data.auth.UserIdProvider
import com.torve.db.TorveDatabase
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DownloadRepository
import kotlinx.datetime.Clock

class DownloadRepositoryImpl(
    private val database: TorveDatabase,
    private val userIdProvider: UserIdProvider,
) : DownloadRepository {

    override suspend fun enqueueDownload(download: Download): Download {
        val now = Clock.System.now().toEpochMilliseconds()
        database.torveQueries.insertDownload(
            user_id = userIdProvider.currentUserId(),
            id = download.id,
            media_id = download.mediaId,
            media_type = download.mediaType.name.lowercase(),
            title = download.title,
            poster_url = download.posterUrl,
            stream_url = download.streamUrl,
            file_path = download.filePath,
            file_size_bytes = download.fileSizeBytes,
            downloaded_bytes = 0,
            status = DownloadStatus.PENDING.name.lowercase(),
            season_number = download.seasonNumber?.toLong(),
            episode_number = download.episodeNumber?.toLong(),
            created_at = now,
            completed_at = null,
            bulk_group_id = download.bulkGroupId,
        )
        return download.copy(createdAt = now)
    }

    override suspend fun getAllDownloads(): List<Download> {
        return database.torveQueries.getAllDownloads(userId = userIdProvider.currentUserId())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getPendingDownloads(): List<Download> {
        return database.torveQueries.getPendingDownloads(userId = userIdProvider.currentUserId())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getCompletedDownloads(): List<Download> {
        return database.torveQueries.getCompletedDownloads(userId = userIdProvider.currentUserId())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getDownload(id: String): Download? {
        return database.torveQueries.getDownload(
            userId = userIdProvider.currentUserId(),
            downloadId = id,
        ).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getDownloadByMediaId(mediaId: String): Download? {
        return database.torveQueries.getDownloadByMediaId(
            userId = userIdProvider.currentUserId(),
            mediaId = mediaId,
        ).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) {
        database.torveQueries.updateDownloadProgress(
            downloaded_bytes = downloadedBytes,
            status = status.name.lowercase(),
            userId = userIdProvider.currentUserId(),
            downloadId = id,
        )
    }

    override suspend fun markCompleted(id: String, filePath: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        database.torveQueries.updateDownloadCompleted(
            file_path = filePath,
            completed_at = now,
            userId = userIdProvider.currentUserId(),
            downloadId = id,
        )
    }

    override suspend fun updateFileSize(id: String, fileSizeBytes: Long) {
        database.torveQueries.updateDownloadFileSize(
            file_size_bytes = fileSizeBytes,
            userId = userIdProvider.currentUserId(),
            downloadId = id,
        )
    }

    override suspend fun deleteDownload(id: String) {
        database.torveQueries.deleteDownload(
            userId = userIdProvider.currentUserId(),
            downloadId = id,
        )
    }

    override suspend fun pauseDownload(id: String) {
        val dl = getDownload(id) ?: return
        updateProgress(id, dl.downloadedBytes, DownloadStatus.PAUSED)
    }

    override suspend fun resumeDownload(id: String) {
        val dl = getDownload(id) ?: return
        updateProgress(id, dl.downloadedBytes, DownloadStatus.PENDING)
    }

    private fun com.torve.db.Download_queue.toDomain(): Download {
        return Download(
            id = id,
            mediaId = media_id,
            mediaType = MediaType.fromString(media_type),
            title = title,
            posterUrl = poster_url,
            streamUrl = stream_url,
            filePath = file_path,
            fileSizeBytes = file_size_bytes,
            downloadedBytes = downloaded_bytes,
            status = DownloadStatus.fromString(status),
            seasonNumber = season_number?.toInt(),
            episodeNumber = episode_number?.toInt(),
            createdAt = created_at,
            completedAt = completed_at,
            bulkGroupId = bulk_group_id,
        )
    }
}
