package com.streamvault.android.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID_BASE = 2000
        private const val PROGRESS_UPDATE_BYTES = 256 * 1024L
        private const val WORK_NAME_PREFIX = "download_"

        fun enqueue(context: Context, downloadId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_DOWNLOAD_ID to downloadId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$downloadId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, downloadId: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork("$WORK_NAME_PREFIX$downloadId")
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Download progress notifications"
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val repo: DownloadRepository = getKoin().get()
        val download = repo.getDownload(downloadId) ?: return Result.failure()

        val notificationId = NOTIFICATION_ID_BASE + downloadId.hashCode()

        // Set up as foreground work with notification
        setForeground(createForegroundInfo(notificationId, download.title, 0, true))

        // Prepare output file
        val downloadsDir = applicationContext.getExternalFilesDir("downloads")
            ?: applicationContext.filesDir.resolve("downloads")
        downloadsDir.mkdirs()

        val extension = download.streamUrl.substringAfterLast('.', "mp4")
            .substringBefore('?').take(5).ifBlank { "mp4" }
        val outputFile = File(downloadsDir, "$downloadId.$extension")

        // Check for existing partial file (resume support)
        val existingBytes = if (outputFile.exists()) outputFile.length() else 0L

        return try {
            repo.updateProgress(downloadId, existingBytes, DownloadStatus.DOWNLOADING)

            withContext(Dispatchers.IO) {
                val connection = (URL(download.streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    if (existingBytes > 0) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                try {
                    connection.connect()

                    val responseCode = connection.responseCode
                    val contentLength = connection.contentLengthLong
                    val isResume = existingBytes > 0 && responseCode == 206
                    val totalSize = if (isResume) {
                        existingBytes + (contentLength.takeIf { it > 0 } ?: 0L)
                    } else {
                        contentLength.takeIf { it > 0 } ?: 0L
                    }

                    if (totalSize > 0) {
                        repo.updateFileSize(downloadId, totalSize)
                    }

                    // If not resuming and file exists, start fresh
                    if (!isResume && outputFile.exists()) {
                        outputFile.delete()
                    }

                    var downloadedBytes = if (isResume) existingBytes else 0L
                    var lastProgressUpdate = downloadedBytes
                    val buffer = ByteArray(8192)

                    connection.inputStream.use { inputStream ->
                        FileOutputStream(outputFile, isResume).use { fos ->
                            while (true) {
                                if (isStopped) break

                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                fos.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                // Update progress periodically
                                if (downloadedBytes - lastProgressUpdate >= PROGRESS_UPDATE_BYTES) {
                                    repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.DOWNLOADING)
                                    lastProgressUpdate = downloadedBytes

                                    val percent = if (totalSize > 0) {
                                        ((downloadedBytes.toDouble() / totalSize) * 100).toInt()
                                    } else 0
                                    setForeground(
                                        createForegroundInfo(notificationId, download.title, percent, false),
                                    )
                                }
                            }
                        }
                    }

                    if (isStopped) {
                        // Preserve partial file for resume
                        repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.PAUSED)
                        Result.success()
                    } else {
                        // Download complete
                        repo.markCompleted(downloadId, outputFile.absolutePath)
                        showCompletionNotification(notificationId, download.title)
                        Result.success()
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            repo.updateProgress(
                downloadId,
                if (outputFile.exists()) outputFile.length() else 0L,
                DownloadStatus.FAILED,
            )
            Result.failure()
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        progress: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Starting download..." else "$progress% downloaded")
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun showCompletionNotification(notificationId: Int, title: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Download complete")
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}
