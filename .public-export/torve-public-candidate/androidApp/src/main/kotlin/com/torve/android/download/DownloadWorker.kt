package com.torve.android.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.torve.android.R
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.torve.domain.model.DownloadStatus
import com.torve.domain.repository.DownloadRepository
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
        const val CHANNEL_ID = "download_transfer"
        private const val NOTIFICATION_ID_BASE = 2000
        private const val PROGRESS_UPDATE_BYTES = 256 * 1024L
        private const val WORK_NAME_PREFIX = "download_"

        fun enqueue(context: Context, downloadId: String) {
            android.util.Log.w("DownloadWorker", "enqueue() called for downloadId=$downloadId")
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_DOWNLOAD_ID to downloadId))
                .addTag("download_work")
                // Expedited: starts immediately, promotes to foreground service right away
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
                val manager = context.getSystemService(NotificationManager::class.java)
                // Clean up legacy channel with wrong importance
                manager.deleteNotificationChannel("download_progress")
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_downloads),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notif_channel_downloads_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        android.util.Log.w("DownloadWorker", "doWork() started for downloadId=$downloadId")
        val repo: DownloadRepository = getKoin().get()
        val download = repo.getDownload(downloadId) ?: run {
            android.util.Log.e("DownloadWorker", "Download not found in DB: $downloadId")
            return Result.failure()
        }

        val notificationId = NOTIFICATION_ID_BASE + downloadId.hashCode()

        // Immediately enter foreground with visible notification BEFORE any network I/O.
        setForeground(createForegroundInfo(notificationId, download.title, 0, indeterminate = true))

        val downloadsDir = applicationContext.getExternalFilesDir("downloads")
            ?: applicationContext.filesDir.resolve("downloads")
        downloadsDir.mkdirs()

        val extension = download.streamUrl.substringAfterLast('.', "mp4")
            .substringBefore('?').take(5).ifBlank { "mp4" }
        val outputFile = File(downloadsDir, "$downloadId.$extension")
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

                    if (!isResume && outputFile.exists()) {
                        outputFile.delete()
                    }

                    var downloadedBytes = if (isResume) existingBytes else 0L
                    var lastProgressUpdate = downloadedBytes
                    val buffer = ByteArray(8192)

                    // Switch from indeterminate to determinate once we know the total size
                    if (totalSize > 0) {
                        val initialPercent = if (downloadedBytes > 0) {
                            ((downloadedBytes.toDouble() / totalSize) * 100).toInt()
                        } else 0
                        setForeground(
                            createForegroundInfo(notificationId, download.title, initialPercent, indeterminate = false),
                        )
                    }

                    connection.inputStream.use { inputStream ->
                        FileOutputStream(outputFile, isResume).use { fos ->
                            while (true) {
                                if (isStopped) break

                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                fos.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                if (downloadedBytes - lastProgressUpdate >= PROGRESS_UPDATE_BYTES) {
                                    repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.DOWNLOADING)
                                    lastProgressUpdate = downloadedBytes

                                    val percent = if (totalSize > 0) {
                                        ((downloadedBytes.toDouble() / totalSize) * 100).toInt()
                                    } else 0
                                    setForeground(
                                        createForegroundInfo(notificationId, download.title, percent, indeterminate = false),
                                    )
                                }
                            }
                        }
                    }

                    if (isStopped) {
                        repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.PAUSED)
                        showCancelledNotification(notificationId, download.title)
                        Result.success()
                    } else {
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
            showFailureNotification(notificationId, download.title)
            Result.failure()
        }
    }

    // Called by WorkManager for expedited work on API 31+ to get the foreground notification
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: "unknown"
        val notificationId = NOTIFICATION_ID_BASE + downloadId.hashCode()
        return createForegroundInfo(notificationId, "Preparing download…", 0, indeterminate = true)
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        progress: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val ctx = applicationContext
        val notifTitle = if (indeterminate) {
            ctx.getString(R.string.notif_download_started)
        } else {
            ctx.getString(R.string.notif_download_progress_title)
        }
        val notifText = if (indeterminate) {
            ctx.getString(R.string.notif_download_starting, title)
        } else {
            ctx.getString(R.string.notif_download_progress, title, progress)
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
            .setContentTitle(applicationContext.getString(R.string.notif_download_complete_title))
            .setContentText(applicationContext.getString(R.string.notif_download_complete, title))
            .setAutoCancel(true)
            .build()

        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    private fun showFailureNotification(notificationId: Int, title: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(applicationContext.getString(R.string.notif_download_failed_title))
            .setContentText(applicationContext.getString(R.string.notif_download_failed, title))
            .setAutoCancel(true)
            .build()

        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    private fun showCancelledNotification(notificationId: Int, title: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download cancelled")
            .setContentText("\"$title\" download was cancelled")
            .setAutoCancel(true)
            .build()

        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }
}
