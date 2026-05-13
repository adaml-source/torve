package com.torve.android.epg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.torve.android.background.BackgroundWork
import com.torve.data.auth.AuthClient
import com.torve.domain.repository.ChannelRepository
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class EpgWarmupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val authClient: AuthClient = getKoin().get()
            if (authClient.getAuthenticatedUser() == null) {
                return Result.success()
            }

            val channelRepository: ChannelRepository = getKoin().get()
            val playlists = channelRepository.getPlaylists()
                .filter { !it.epgUrl.isNullOrBlank() || it.type.name.equals("XTREAM", ignoreCase = true) }
            android.util.Log.i("EpgWarmupWorker", "start playlists=${playlists.size}")
            if (playlists.isEmpty()) return Result.success()

            playlists.forEachIndexed { index, playlist ->
                publishProgress(
                    label = "Refreshing guide data",
                    progress = index.toFloat() / playlists.size.toFloat(),
                )
                runCatching {
                    android.util.Log.i("EpgWarmupWorker", "refresh playlist=${playlist.id} name=${playlist.name}")
                    channelRepository.refreshEpg(playlist.id)
                    android.util.Log.i("EpgWarmupWorker", "refreshed playlist=${playlist.id}")
                }.onFailure { error ->
                    android.util.Log.w("EpgWarmupWorker", "refresh failed playlist=${playlist.id}: ${error.message}")
                }
            }
            publishProgress("Guide data ready", 1f)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun publishProgress(label: String, progress: Float) {
        setProgress(
            workDataOf(
                BackgroundWork.KEY_LABEL to label,
                BackgroundWork.KEY_PROGRESS to progress.coerceIn(0f, 1f),
                BackgroundWork.KEY_BLOCK_NAVIGATION to true,
            ),
        )
    }

    companion object {
        private const val WORK_NAME = "epg_warmup_worker"
        private const val IMMEDIATE_WORK_NAME = "epg_warmup_worker_immediate"

        fun schedule(context: Context) {
            val periodicConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()
            val immediateConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val periodic = PeriodicWorkRequestBuilder<EpgWarmupWorker>(
                6, TimeUnit.HOURS,
            )
                .setConstraints(periodicConstraints)
                .addTag(BackgroundWork.TAG_HEAVY_PRELOAD)
                .build()
            val immediate = OneTimeWorkRequestBuilder<EpgWarmupWorker>()
                .setConstraints(immediateConstraints)
                .addTag(BackgroundWork.TAG_HEAVY_PRELOAD)
                .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic,
            )
            manager.enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediate,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}
