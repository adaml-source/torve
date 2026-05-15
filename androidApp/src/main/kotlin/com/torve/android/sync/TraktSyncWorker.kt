package com.torve.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.TraktTokens
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.settings.SettingsViewModel
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class TraktSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (ensureTraktAccessToken().isNullOrBlank()) {
                return Result.success()
            }

            val watchlistRepo: WatchlistRepository = getKoin().get()
            val historyRepo: WatchHistoryRepository = getKoin().get()
            val progressRepo: WatchProgressRepository = getKoin().get()
            val traktSyncRepo: TraktSyncRepository = getKoin().get()
            val prefsRepo: PreferencesRepository = getKoin().get()
            val refreshNotifier: SettingsRefreshNotifier = getKoin().get()

            watchlistRepo.syncFromTrakt()
            historyRepo.syncFromTrakt()
            progressRepo.syncFromTrakt()
            traktSyncRepo.syncRatingsFromTrakt()
            traktSyncRepo.flushPendingWrites()
            val now = System.currentTimeMillis()
            prefsRepo.setString(SettingsViewModel.KEY_TRAKT_LAST_SYNC_TIME, now.toString())
            refreshNotifier.notifyRefresh(now)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun ensureTraktAccessToken(): String? {
        val tokenStore: TraktTokenStore = getKoin().get()
        tokenStore.accessToken()?.takeIf { it.isNotBlank() }?.let { return it }

        val secretStore: IntegrationSecretStore = getKoin().get()
        val accessToken = secretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val refreshToken = secretStore.get(IntegrationSecretKey.TRAKT_REFRESH_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        tokenStore.write(
            TraktTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return accessToken
    }

    companion object {
        private const val WORK_NAME = "trakt_sync_worker"
        private const val IMMEDIATE_WORK_NAME = "trakt_sync_worker_immediate"

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

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<TraktSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}
