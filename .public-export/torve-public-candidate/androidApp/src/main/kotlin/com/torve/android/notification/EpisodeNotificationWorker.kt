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
import com.torve.android.R
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.calendar.CalendarViewModel
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
            val prefsRepo: PreferencesRepository = getKoin().get()

            val notificationsEnabled =
                prefsRepo.getString(CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true"
            if (!notificationsEnabled) return Result.success()
            val accessToken = tokenStore.accessToken()
            if (accessToken.isNullOrBlank()) return Result.success()

            val calendar = traktApi.getCalendar()
            if (calendar.isNotEmpty()) {
                ensureNotificationChannel()
                val titles = calendar.take(3).joinToString(", ") { it.showTitle }
                val body = if (calendar.size > 3) {
                    applicationContext.getString(R.string.notif_episodes_and_more, titles, calendar.size - 3)
                } else {
                    titles
                }

                if (hasNotificationPermission()) {
                    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle(applicationContext.getString(R.string.notif_new_episodes))
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
                applicationContext.getString(R.string.notif_channel_episodes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = applicationContext.getString(R.string.notif_channel_episodes_desc)
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

        suspend fun scheduleIfEnabled(context: Context) {
            val prefsRepo: PreferencesRepository = getKoin().get()
            if (prefsRepo.getString(CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true") {
                schedule(context)
            } else {
                cancel(context)
            }
        }
    }
}
