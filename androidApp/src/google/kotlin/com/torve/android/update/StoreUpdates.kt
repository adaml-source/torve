package com.torve.android.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.torve.android.BuildConfig
import com.torve.android.MainActivity
import com.torve.android.R
import com.torve.android.ui.player.ActivePlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object StoreUpdates {
    const val EXTRA_CHECK_UPDATE = "com.torve.app.CHECK_PLAY_UPDATE"
    private const val WORK_NAME = "google_play_update_check_v1"

    private fun supported(context: Context) = BuildConfig.FLAVOR.contains("mobile", ignoreCase = true) &&
        context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_TELEVISION

    fun createController(activity: AppCompatActivity): StoreUpdateController =
        if (supported(activity)) GooglePlayUpdateController(activity) else NoStoreUpdateController

    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!supported(context)) { work.cancelUniqueWork(WORK_NAME); return }
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlayStoreUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }
}

private class GooglePlayUpdateController(private val activity: AppCompatActivity) : StoreUpdateController {
    private val manager = AppUpdateManagerFactory.create(activity.applicationContext)
    private val prefs = activity.getSharedPreferences("google_play_update_reminders", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(StoreUpdateState(enabled = true))
    override val state = mutableState.asStateFlow()
    private var closed = false
    private var checking = false
    private var forcePrompt = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) dismissPrompt()
        // AppUpdateInfo is single-use; the next attempt always fetches a fresh one.
        check(force = result.resultCode != Activity.RESULT_CANCELED)
    }
    private val listener = InstallStateUpdatedListener { install ->
        if (!closed) {
            val old = mutableState.value
            when (install.installStatus()) {
                InstallStatus.PENDING, InstallStatus.DOWNLOADING -> mutableState.value = old.copy(
                    phase = StoreUpdatePhase.DOWNLOADING, showPrompt = false,
                    downloadedBytes = install.bytesDownloaded(), totalBytes = install.totalBytesToDownload())
                InstallStatus.DOWNLOADED -> {
                    mutableState.value = old.copy(phase = StoreUpdatePhase.DOWNLOADED, showPrompt = true)
                    PlayUpdateNotifications.show(activity, old.versionCode, ready = true)
                }
                InstallStatus.INSTALLING -> mutableState.value = old.copy(phase = StoreUpdatePhase.INSTALLING, showPrompt = false)
                InstallStatus.INSTALLED -> mutableState.value = StoreUpdateState(enabled = true, phase = StoreUpdatePhase.UP_TO_DATE)
                InstallStatus.CANCELED, InstallStatus.FAILED -> check(force = false)
            }
        }
    }
    init { manager.registerListener(listener) }

    override fun onResume() = check(force = activity.intent?.getBooleanExtra(StoreUpdates.EXTRA_CHECK_UPDATE, false) == true)
    override fun checkNow() = check(force = true)

    private fun check(force: Boolean) {
        if (closed) return
        forcePrompt = forcePrompt || force
        activity.intent?.removeExtra(StoreUpdates.EXTRA_CHECK_UPDATE)
        if (checking) return
        checking = true
        if (mutableState.value.phase in setOf(StoreUpdatePhase.IDLE, StoreUpdatePhase.UP_TO_DATE, StoreUpdatePhase.CHECK_FAILED)) {
            mutableState.value = mutableState.value.copy(phase = StoreUpdatePhase.CHECKING)
        }
        manager.appUpdateInfo.addOnSuccessListener { info ->
            checking = false
            if (!closed) { apply(info, forcePrompt); forcePrompt = false }
        }.addOnFailureListener {
            checking = false; forcePrompt = false
            if (!closed && mutableState.value.phase == StoreUpdatePhase.CHECKING) {
                mutableState.value = mutableState.value.copy(phase = StoreUpdatePhase.CHECK_FAILED, showPrompt = false)
            }
        }
    }

    private fun apply(info: AppUpdateInfo, force: Boolean) {
        val version = info.availableVersionCode()
        val ready = info.installStatus() == InstallStatus.DOWNLOADED
        val downloading = info.installStatus() in setOf(InstallStatus.PENDING, InstallStatus.DOWNLOADING)
        val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && version > BuildConfig.VERSION_CODE
        mutableState.value = when {
            ready -> StoreUpdateState(true, StoreUpdatePhase.DOWNLOADED, version, true)
            downloading -> StoreUpdateState(true, StoreUpdatePhase.DOWNLOADING, version, false, info.bytesDownloaded(), info.totalBytesToDownload())
            available -> StoreUpdateState(true, StoreUpdatePhase.AVAILABLE, version,
                force || StoreUpdatePolicy.shouldRemind(version, prefs.getInt("dismissed_version", 0), prefs.getLong("dismissed_at", 0L), System.currentTimeMillis()))
            info.updateAvailability() == UpdateAvailability.UNKNOWN -> StoreUpdateState(enabled = true, phase = StoreUpdatePhase.CHECK_FAILED)
            else -> {
                PlayUpdateNotifications.clear(activity)
                StoreUpdateState(enabled = true, phase = StoreUpdatePhase.UP_TO_DATE)
            }
        }
    }

    private fun safeToInstall() = StoreUpdatePolicy.mayInstall(ActivePlaybackState.isFullScreenPlayerVisible,
        ActivePlaybackState.isPlaying, ActivePlaybackState.session != null, ActivePlaybackState.isInPipMode)

    override fun beginUpdate() {
        if (closed || checking || !safeToInstall()) return
        checking = true
        manager.appUpdateInfo.addOnSuccessListener { info ->
            checking = false
            if (closed || !safeToInstall()) return@addOnSuccessListener
            if (info.installStatus() == InstallStatus.DOWNLOADED) { apply(info, true); return@addOnSuccessListener }
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) { apply(info, true); return@addOnSuccessListener }
            apply(info, true)
            if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                val started = runCatching {
                    manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
                }.getOrDefault(false)
                if (!started) openPlayListing()
            } else openPlayListing()
        }.addOnFailureListener { checking = false; if (!closed && safeToInstall()) openPlayListing() }
    }

    private fun openPlayListing() {
        val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}")).setPackage("com.android.vending")
        if (runCatching { activity.startActivity(play) }.isFailure) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}"))) }
        }
    }

    override fun completeUpdate() {
        if (closed || mutableState.value.phase != StoreUpdatePhase.DOWNLOADED || !safeToInstall()) return
        mutableState.value = mutableState.value.copy(phase = StoreUpdatePhase.INSTALLING, showPrompt = false)
        manager.completeUpdate().addOnFailureListener {
            if (!closed) mutableState.value = mutableState.value.copy(phase = StoreUpdatePhase.DOWNLOADED, showPrompt = true)
        }
    }
    override fun dismissPrompt() {
        val current = mutableState.value
        if (current.phase == StoreUpdatePhase.AVAILABLE) prefs.edit()
            .putInt("dismissed_version", current.versionCode).putLong("dismissed_at", System.currentTimeMillis()).apply()
        mutableState.value = current.copy(showPrompt = false)
    }
    override fun close() { closed = true; manager.unregisterListener(listener) }
}

/** Only Play verifies which release is actually available to this user's rollout/track. */
class PlayStoreUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!installedFromPlay(applicationContext)) return Result.success()
        return try {
            val task = AppUpdateManagerFactory.create(applicationContext).appUpdateInfo
            val info = suspendCancellableCoroutine<AppUpdateInfo> { continuation ->
                task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> PlayUpdateNotifications.show(applicationContext, info.availableVersionCode(), ready = true)
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.availableVersionCode() > BuildConfig.VERSION_CODE -> PlayUpdateNotifications.show(applicationContext, info.availableVersionCode(), ready = false)
                info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE -> PlayUpdateNotifications.clear(applicationContext)
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.success() }
    }
    @Suppress("DEPRECATION")
    private fun installedFromPlay(context: Context): Boolean = runCatching {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)) == "com.android.vending"
    }.getOrDefault(false)
}

private object PlayUpdateNotifications {
    private const val ID = 1105
    private const val CHANNEL = "google_play_updates"
    fun show(context: Context, version: Int, ready: Boolean) {
        if (version <= BuildConfig.VERSION_CODE) return
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        val prefs = context.getSharedPreferences("google_play_update_notifications", Context.MODE_PRIVATE)
        val identity = "$version:$ready"
        if (prefs.getString("last_notified", null) == identity) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.app_update_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        }
        val intent = Intent(context, MainActivity::class.java).putExtra(StoreUpdates.EXTRA_CHECK_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = android.app.PendingIntent.getActivity(context, ID, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(if (ready) R.string.play_update_ready else R.string.play_update_available)
        val body = context.getString(if (ready) R.string.play_update_ready_body else R.string.play_update_available_body)
        try {
            notifications.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open)
                .setOnlyAlertOnce(true).setAutoCancel(true).build())
            prefs.edit().putString("last_notified", identity).apply()
        } catch (_: SecurityException) { /* The in-app prompt works without notification permission. */ }
    }
    fun clear(context: Context) { NotificationManagerCompat.from(context).cancel(ID) }
}
