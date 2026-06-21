package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED;

    companion object {
        fun fromString(s: String): DownloadStatus = when (s.lowercase()) {
            "pending" -> PENDING
            "downloading" -> DOWNLOADING
            "paused" -> PAUSED
            "completed" -> COMPLETED
            "failed" -> FAILED
            else -> PENDING
        }
    }
}

@Serializable
data class Download(
    val id: String,
    val mediaId: String,
    val mediaType: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val streamUrl: String,
    val filePath: String? = null,
    val fileSizeBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val createdAt: Long = 0,
    val completedAt: Long? = null,
    val bulkGroupId: String? = null,
) {
    val progressPercent: Float
        get() = if (fileSizeBytes != null && fileSizeBytes > 0) {
            (downloadedBytes.toFloat() / fileSizeBytes)
        } else 0f
}
