package com.torve.android.background

import android.content.Context
import androidx.work.*
import com.torve.data.auth.UserIdProvider
import com.torve.data.profile.CloudBackupFailure
import com.torve.data.profile.CloudLibraryBackups
import com.torve.presentation.session.AccountSessionCoordinator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit
import java.util.UUID

object CloudBackupScheduling {
    private const val WORK = "torve_cloud_library_backup"
    private const val DAY = 86_400_000L
    private fun preferences(context: Context) = context.getSharedPreferences("cloud_backup_v1", Context.MODE_PRIVATE)
    fun platform(context: Context) = if (context.packageName.contains(".amazon")) "firetv" else "android"
    fun automatic(context: Context, account: String) = preferences(context).getBoolean("$account.automatic", false)
    fun setAutomatic(context: Context, account: String, enabled: Boolean) {
        if (KoinPlatform.getKoin().get<UserIdProvider>().currentUserIdOrNull() != account) return
        preferences(context).edit().putBoolean("$account.automatic", enabled).apply()
        refresh(context)
    }
    fun recordSuccess(context: Context, account: String) {
        preferences(context).edit().putLong("$account.last-success", System.currentTimeMillis()).apply()
    }
    fun refresh(context: Context) {
        val account = KoinPlatform.getKoin().get<UserIdProvider>().currentUserIdOrNull()
        val prefs = preferences(context)
        val work = WorkManager.getInstance(context)
        val previous = prefs.getString("scheduled-account", null)
        if (account != previous || account == null || !automatic(context, account)) {
            work.cancelUniqueWork(WORK)
            prefs.edit().remove("scheduled-account").apply()
        }
        if (account == null || !automatic(context, account)) return
        val delay = (prefs.getLong("$account.last-success", 0) + DAY - System.currentTimeMillis()).coerceIn(0, DAY)
        val request = PeriodicWorkRequestBuilder<CloudLibraryBackupWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf("account" to account))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        prefs.edit().putString("scheduled-account", account).apply()
    }
}

class CloudLibraryBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expected = inputData.getString("account") ?: return Result.success()
        val koin = KoinPlatform.getKoin()
        try {
            koin.get<AccountSessionCoordinator>().restoreSession()
            val ids = koin.get<UserIdProvider>()
            if (ids.currentUserIdOrNull() != expected || !CloudBackupScheduling.automatic(applicationContext, expected)) return Result.success()
            val dailyRequest = UUID.nameUUIDFromBytes("$id:${System.currentTimeMillis() / 86_400_000L}".toByteArray()).toString()
            koin.get<CloudLibraryBackups>().create(CloudBackupScheduling.platform(applicationContext), "automatic", dailyRequest)
            if (ids.currentUserIdOrNull() == expected) CloudBackupScheduling.recordSuccess(applicationContext, expected)
            return Result.success()
        } catch (_: TimeoutCancellationException) { return Result.retry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CloudBackupFailure) { return if (failure.retryable) Result.retry() else Result.failure() }
        catch (_: Exception) { return Result.retry() }
    }
}
