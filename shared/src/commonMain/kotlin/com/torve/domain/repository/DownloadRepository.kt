package com.torve.domain.repository

import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus

interface DownloadRepository {
    suspend fun enqueueDownload(download: Download): Download
    suspend fun getAllDownloads(): List<Download>
    suspend fun getPendingDownloads(): List<Download>
    suspend fun getCompletedDownloads(): List<Download>
    suspend fun getDownload(id: String): Download?
    suspend fun getDownloadByMediaId(mediaId: String): Download?
    suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus)
    suspend fun markCompleted(id: String, filePath: String)
    suspend fun updateFileSize(id: String, fileSizeBytes: Long)
    suspend fun deleteDownload(id: String)
    suspend fun pauseDownload(id: String)
    suspend fun resumeDownload(id: String)
}
