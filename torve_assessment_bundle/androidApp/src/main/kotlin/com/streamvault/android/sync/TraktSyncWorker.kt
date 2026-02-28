package com.streamvault.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamvault.data.trakt.auth.TraktTokenStore
import com.streamvault.data.trakt.repo.TraktSyncRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.domain.repository.WatchlistRepository
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class TraktSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val tokenStore: TraktTokenStore = getKoin().get()
            if (tokenStore.accessToken().isNullOrBlank()) {
                return Result.success()
            }

            val watchlistRepo: WatchlistRepository = getKoin().get()
            val historyRepo: WatchHistoryRepository = getKoin().get()
            val progressRepo: WatchProgressRepository = getKoin().get()
            val traktSyncRepo: TraktSyncRepository = getKoin().get()

            watchlistRepo.syncFromTrakt()
            historyRepo.syncFromTrakt()
            progressRepo.syncFromTrakt()
            traktSyncRepo.syncRatingsFromTrakt()
            traktSyncRepo.flushPendingWrites()

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "trakt_sync_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<TraktSyncWorker>(
                6, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
