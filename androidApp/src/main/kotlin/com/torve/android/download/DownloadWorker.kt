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
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.model.DownloadStatus
import com.torve.domain.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_OWNER = "download_owner"
        const val CHANNEL_ID = "download_transfer"
        private const val NOTIFICATION_ID_BASE = 2000
        private const val PROGRESS_UPDATE_BYTES = 256 * 1024L
        private const val WORK_NAME_PREFIX = "download_"

        fun enqueue(context: Context, downloadId: String) {
            val owner = getKoin().get<UserIdProvider>().currentDataUserId()
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_DOWNLOAD_ID to downloadId, KEY_OWNER to owner))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("download_work")
                // Expedited: starts immediately, promotes to foreground service right away
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(owner, downloadId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, downloadId: String) {
            val owner = getKoin().get<UserIdProvider>().currentDataUserId()
            WorkManager.getInstance(context)
                .cancelUniqueWork(workName(owner, downloadId))
            WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME_PREFIX$downloadId")
        }

        private fun workName(owner: String, id: String) = "$WORK_NAME_PREFIX${ownerDigest(owner)}_$id"
        private fun ownerDigest(owner: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(owner.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

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
        if (!downloadId.matches(Regex("[A-Za-z0-9_-]+"))) return Result.failure()
        val ids: UserIdProvider = getKoin().get()
        val owner = inputData.getString(KEY_OWNER) ?: return Result.failure()
        if (ids.currentDataUserId() != owner) return Result.success()
        return ids.withPersonalDataScope { transfer(downloadId, owner, ids) }
    }

    private suspend fun transfer(downloadId: String, owner: String, ids: UserIdProvider): Result {
        val repo: DownloadRepository = getKoin().get()
        val download = repo.getDownload(downloadId) ?: return Result.failure()
        if (download.status == DownloadStatus.COMPLETED || download.status == DownloadStatus.PAUSED) return Result.success()

        val notificationId = NOTIFICATION_ID_BASE + downloadId.hashCode()

        // Immediately enter foreground with visible notification BEFORE any network I/O.
        setForeground(createForegroundInfo(notificationId, download.title, 0, indeterminate = true))

        val downloadsDir = applicationContext.getExternalFilesDir("downloads")
            ?: applicationContext.filesDir.resolve("downloads")
        downloadsDir.mkdirs()

        val extension = runCatching { URL(download.streamUrl).path.substringAfterLast('.', "mp4").lowercase() }.getOrDefault("mp4")
            .takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v") } ?: "mp4"
        // Preserve primary-profile files while isolating every additional profile.
        val fileId = if (ids.currentProfileId() == "default") downloadId else "${ownerDigest(owner)}_$downloadId"
        val outputFile = File(downloadsDir, "$fileId.$extension")
        val validatorFile = File(downloadsDir, "$fileId.validator")
        val validator = validatorFile.takeIf { it.exists() && it.length() <= 1024 }?.readText()?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        val existingBytes = if (outputFile.exists() && validator != null) outputFile.length() else 0L

        return try {
            repo.updateProgress(downloadId, existingBytes, DownloadStatus.DOWNLOADING)

            withContext(Dispatchers.IO) {
                if (downloadsDir.usableSpace < 32 * 1024 * 1024L) throw InsufficientDownloadStorage()
                val connection = (URL(download.streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existingBytes > 0) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                        setRequestProperty("If-Range", validator)
                    }
                }

                try {
                    connection.connect()

                    val responseCode = connection.responseCode
                    val plan = DownloadResponsePolicy.plan(responseCode, existingBytes,
                        connection.contentLengthLong.takeIf { it >= 0 }, connection.getHeaderField("Content-Range"))
                    val isResume = plan.append
                    val totalSize = plan.totalBytes ?: 0L
                    val remaining = (totalSize - if (isResume) existingBytes else 0L).coerceAtLeast(0)
                    if (remaining > 0 && downloadsDir.usableSpace - 16 * 1024 * 1024L < remaining) throw InsufficientDownloadStorage()
                    // Discard the previous representation before recording the new validator.
                    // A crash between those operations must never append new bytes to the old file.
                    if (!isResume && outputFile.exists() && !outputFile.delete()) throw java.io.IOException("Could not restart the download file")
                    val nextValidator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: connection.getHeaderField("Last-Modified")
                    if (nextValidator != null && nextValidator.length <= 1024) validatorFile.writeText(nextValidator)
                    else if (!isResume) validatorFile.delete()

                    if (totalSize > 0) {
                        repo.updateFileSize(downloadId, totalSize)
                    }

                    var downloadedBytes = if (isResume) existingBytes else 0L
                    repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.DOWNLOADING)
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

                    if (!plan.alreadyComplete) connection.inputStream.use { inputStream ->
                        FileOutputStream(outputFile, isResume).use { fos ->
                            while (true) {
                                coroutineContext.ensureActive()
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
                            fos.fd.sync()
                        }
                    }

                    if (isStopped) {
                        repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.PAUSED)
                        showCancelledNotification(notificationId, download.title)
                        Result.success()
                    } else {
                        DownloadResponsePolicy.verifyComplete(downloadedBytes, plan.totalBytes)
                        repo.updateProgress(downloadId, downloadedBytes, DownloadStatus.DOWNLOADING)
                        repo.markCompleted(downloadId, outputFile.absolutePath)
                        validatorFile.delete()
                        showCompletionNotification(notificationId, download.title)
                        Result.success()
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: CancellationException) {
            if (ids.currentDataUserId() == owner) withContext(NonCancellable) {
                repo.updateProgress(downloadId, outputFile.length(), DownloadStatus.PAUSED)
            }
            throw e
        } catch (e: Exception) {
            val retry = runAttemptCount < 2 && e is IOException && e !is InsufficientDownloadStorage &&
                (e !is DownloadHttpException || e.retryable)
            repo.updateProgress(
                downloadId,
                if (outputFile.exists()) outputFile.length() else 0L,
                if (retry) DownloadStatus.PENDING else DownloadStatus.FAILED,
            )
            if (!retry) showFailureNotification(notificationId, download.title)
            if (retry) Result.retry() else Result.failure()
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

private class InsufficientDownloadStorage : IOException("Not enough storage for this download")
