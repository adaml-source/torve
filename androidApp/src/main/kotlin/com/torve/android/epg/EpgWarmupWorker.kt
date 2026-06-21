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
import com.torve.domain.diagnostics.DiagnosticsRedactor
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
            val blockNavigation = inputData.getBoolean(
                BackgroundWork.KEY_BLOCK_NAVIGATION,
                true,
            )

            val channelRepository: ChannelRepository = getKoin().get()
            val playlists = channelRepository.getPlaylists()
                .filter { !it.epgUrl.isNullOrBlank() || it.type.name.equals("XTREAM", ignoreCase = true) }
            android.util.Log.i("EpgWarmupWorker", "start playlists=${playlists.size}")
            if (playlists.isEmpty()) return Result.success()

            playlists.forEachIndexed { index, playlist ->
                publishProgress(
                    label = "Refreshing guide data",
                    progress = index.toFloat() / playlists.size.toFloat(),
                    blockNavigation = blockNavigation,
                )
                runCatching {
                    val channelCount = channelRepository.getTotalChannelCount(playlist.id)
                    if (channelCount <= 0L) {
                        android.util.Log.i(
                            "EpgWarmupWorker",
                            "skip playlist=${playlist.id} reason=no_catalog_rows",
                        )
                        return@runCatching
                    }
                    android.util.Log.i("EpgWarmupWorker", "refresh playlist=${playlist.id} name=${playlist.name}")
                    channelRepository.refreshEpg(playlist.id)
                    android.util.Log.i("EpgWarmupWorker", "refreshed playlist=${playlist.id}")
                }.onFailure { error ->
                    android.util.Log.w("EpgWarmupWorker", "refresh failed playlist=${playlist.id}: ${DiagnosticsRedactor.redact(error.message)}")
                }
            }
            publishProgress("Guide data ready", 1f, blockNavigation = blockNavigation)

            Result.success()
        } catch (error: Exception) {
            val immediateRefresh = inputData.getBoolean(KEY_IMMEDIATE_REFRESH, false)
            if (immediateRefresh) {
                runCatching {
                    publishProgress(
                        label = "Guide refresh failed",
                        progress = 1f,
                        blockNavigation = false,
                    )
                }
                android.util.Log.w("EpgWarmupWorker", "foreground refresh failed: ${DiagnosticsRedactor.redact(error.message)}")
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun publishProgress(
        label: String,
        progress: Float,
        blockNavigation: Boolean,
    ) {
        setProgress(
            workDataOf(
                BackgroundWork.KEY_LABEL to label,
                BackgroundWork.KEY_PROGRESS to progress.coerceIn(0f, 1f),
                BackgroundWork.KEY_BLOCK_NAVIGATION to blockNavigation,
            ),
        )
    }

    companion object {
        private const val LEGACY_WORK_NAME = "epg_warmup_worker"
        private const val LEGACY_IMMEDIATE_WORK_NAME = "epg_warmup_worker_immediate"
        private const val WORK_NAME = "epg_warmup_worker_silent_v2"
        private const val IMMEDIATE_WORK_NAME = "epg_warmup_worker_immediate_v2"
        private const val KEY_IMMEDIATE_REFRESH = "immediate_refresh"

        fun schedule(context: Context) {
            val periodicConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()
            val periodic = PeriodicWorkRequestBuilder<EpgWarmupWorker>(
                6, TimeUnit.HOURS,
            )
                .setConstraints(periodicConstraints)
                .build()
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(LEGACY_WORK_NAME)
            manager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK_NAME)
            manager.cancelUniqueWork("${LEGACY_IMMEDIATE_WORK_NAME}_refresh")
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        fun refreshNow(
            context: Context,
            blockNavigation: Boolean = true,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<EpgWarmupWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        BackgroundWork.KEY_BLOCK_NAVIGATION to blockNavigation,
                        KEY_IMMEDIATE_REFRESH to true,
                    ),
                )
                .addTag(BackgroundWork.TAG_HEAVY_PRELOAD)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${IMMEDIATE_WORK_NAME}_refresh",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("${IMMEDIATE_WORK_NAME}_refresh")
        }
    }
}
