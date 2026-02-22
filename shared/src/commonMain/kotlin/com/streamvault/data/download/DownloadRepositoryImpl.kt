package com.streamvault.data.download

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.Download
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.DownloadRepository
import kotlinx.datetime.Clock

class DownloadRepositoryImpl(
    private val database: StreamVaultDatabase,
) : DownloadRepository {

    override suspend fun enqueueDownload(download: Download): Download {
        val now = Clock.System.now().toEpochMilliseconds()
        database.streamVaultQueries.insertDownload(
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
        )
        return download.copy(createdAt = now)
    }

    override suspend fun getAllDownloads(): List<Download> {
        return database.streamVaultQueries.getAllDownloads().executeAsList().map { it.toDomain() }
    }

    override suspend fun getPendingDownloads(): List<Download> {
        return database.streamVaultQueries.getPendingDownloads().executeAsList().map { it.toDomain() }
    }

    override suspend fun getCompletedDownloads(): List<Download> {
        return database.streamVaultQueries.getCompletedDownloads().executeAsList().map { it.toDomain() }
    }

    override suspend fun getDownload(id: String): Download? {
        return database.streamVaultQueries.getDownload(id).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getDownloadByMediaId(mediaId: String): Download? {
        return database.streamVaultQueries.getDownloadByMediaId(mediaId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) {
        database.streamVaultQueries.updateDownloadProgress(
            downloaded_bytes = downloadedBytes,
            status = status.name.lowercase(),
            id = id,
        )
    }

    override suspend fun markCompleted(id: String, filePath: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        database.streamVaultQueries.updateDownloadCompleted(
            file_path = filePath,
            completed_at = now,
            id = id,
        )
    }

    override suspend fun deleteDownload(id: String) {
        database.streamVaultQueries.deleteDownload(id)
    }

    override suspend fun pauseDownload(id: String) {
        val dl = getDownload(id) ?: return
        updateProgress(id, dl.downloadedBytes, DownloadStatus.PAUSED)
    }

    override suspend fun resumeDownload(id: String) {
        val dl = getDownload(id) ?: return
        updateProgress(id, dl.downloadedBytes, DownloadStatus.PENDING)
    }

    private fun com.streamvault.db.Download_queue.toDomain(): Download {
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
        )
    }
}
