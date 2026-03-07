package com.torve.android.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class EpisodeNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val tokenStore: TraktTokenStore = getKoin().get()
            val traktApi: TraktAuthorizedApi = getKoin().get()

            val accessToken = tokenStore.accessToken()
            if (accessToken.isNullOrBlank()) return Result.success()

            val calendar = traktApi.getCalendar()
            if (calendar.isNotEmpty()) {
                ensureNotificationChannel()
                val titles = calendar.take(3).joinToString(", ") { it.showTitle }
                val body = if (calendar.size > 3) {
                    "$titles and ${calendar.size - 3} more"
                } else {
                    titles
                }

                if (hasNotificationPermission()) {
                    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("New Episodes Available")
                        .setContentText(body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build()

                    NotificationManagerCompat.from(applicationContext)
                        .notify(NOTIFICATION_ID, notification)
                }
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Episode Notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications for new TV show episodes"
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHANNEL_ID = "episode_notifications"
        const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "episode_notification_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpisodeNotificationWorker>(
                6, TimeUnit.HOURS,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
