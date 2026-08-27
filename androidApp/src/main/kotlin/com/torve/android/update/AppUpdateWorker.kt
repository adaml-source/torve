package com.torve.android.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.torve.android.BuildConfig
import com.torve.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

internal data class AvailableAppUpdate(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val versionCode: Long = 0L,
)

internal fun usesVpsReleaseUpdates(flavor: String): Boolean =
    flavor.contains("amazon", ignoreCase = true) && flavor.contains("tv", ignoreCase = true)

internal fun releasePlatformForFlavor(flavor: String): String = when {
    flavor.contains("amazon", ignoreCase = true) -> "fire_tv"
    flavor.contains("tv", ignoreCase = true) -> "android_tv"
    else -> "android_mobile"
}

internal fun isTrustedUpdateUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        (uri.host.equals("torve.app", ignoreCase = true) ||
            uri.host?.endsWith(".torve.app", ignoreCase = true) == true)
}.getOrDefault(false)

internal fun isNewerRelease(
    candidate: String,
    installed: String,
    candidateVersionCode: Long = 0L,
    installedVersionCode: Long = 0L,
): Boolean {
    if (candidateVersionCode > 0L && installedVersionCode > 0L) {
        return candidateVersionCode > installedVersionCode
    }
    fun numericParts(version: String): List<Int> = version
        .substringBefore('-')
        .split('.')
        .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    val candidateParts = numericParts(candidate)
    val installedParts = numericParts(installed)
    val width = maxOf(candidateParts.size, installedParts.size)
    for (index in 0 until width) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val installedPart = installedParts.getOrElse(index) { 0 }
        if (candidatePart != installedPart) return candidatePart > installedPart
    }
    return false
}

internal fun parseAvailableUpdate(
    manifest: String,
    platform: String,
    installedVersion: String,
    installedVersionCode: Long = 0L,
): AvailableAppUpdate? = runCatching {
    parseAvailableUpdateStrict(manifest, platform, installedVersion, installedVersionCode)
}.getOrNull()

internal fun parseAvailableUpdateStrict(
    manifest: String,
    platform: String,
    installedVersion: String,
    installedVersionCode: Long = 0L,
): AvailableAppUpdate? {
    val root = Json.parseToJsonElement(manifest).jsonObject
    val entry = root["channels"]?.jsonObject
        ?.get("stable")?.jsonObject
        ?.get(platform)?.jsonObject
        ?: throw IllegalArgumentException("Release manifest is missing the $platform channel")
    val status = entry["status"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("Release channel is missing status")
    if (status != "available") return null

    val version = entry["version"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val versionCode = entry["version_code"]?.jsonPrimitive?.longOrNull ?: 0L
    if (version.isBlank()) throw IllegalArgumentException("Release channel is missing version")
    if (!isNewerRelease(version, installedVersion, versionCode, installedVersionCode)) return null

    val downloadUrl = entry["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val sha256 = entry["sha256"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
    val sizeBytes = entry["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
    if (
        !isTrustedUpdateUrl(downloadUrl) ||
        !sha256.matches(Regex("^[0-9a-f]{64}$")) ||
        sizeBytes <= 0L
    ) throw IllegalArgumentException("Release channel has no valid downloadable APK asset")

    return AvailableAppUpdate(
        version = version,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        versionCode = versionCode,
    )
}

internal object AppUpdateChecker {
    private const val RELEASE_MANIFEST_URL = "https://torve.app/downloads/releases.json"

    suspend fun checkForUpdate(): AvailableAppUpdate? = withContext(Dispatchers.IO) {
        if (!usesVpsReleaseUpdates(BuildConfig.FLAVOR)) return@withContext null
        val connection = (URL(RELEASE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // The VPS deliberately serves a short cache lifetime. Explicit revalidation
            // prevents an older local HTTP cache from hiding a newly uploaded release.
            setRequestProperty("Cache-Control", "no-cache")
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Release manifest returned HTTP ${connection.responseCode}")
            }
            val manifest = connection.inputStream.bufferedReader().use { it.readText() }
            parseAvailableUpdateStrict(
                manifest = manifest,
                platform = releasePlatformForFlavor(BuildConfig.FLAVOR),
                installedVersion = BuildConfig.VERSION_NAME,
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            )
        } finally {
            connection.disconnect()
        }
    }
}

internal object AppUpdateNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 1104
    private const val PREFS_NAME = "app_update_notifications"
    private const val LAST_NOTIFIED_VERSION = "last_notified_version"

    @Synchronized
    fun showSystemNotificationIfNew(context: Context, update: AvailableAppUpdate): Boolean {
        // Persist before creating or launching cross-application intents. This also makes the
        // validated URL recoverable if Torve is killed while Downloader owns the foreground.
        AppUpdateHandoffStore(context).save(update)
        if (!hasNotificationPermission(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_NOTIFIED_VERSION, null) == update.version) return false

        ensureChannel(context)
        val downloadAndInstall = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            AppUpdateActivity.createIntent(context, update),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_update_notification_title, update.version))
            .setContentText(context.getString(R.string.app_update_notification_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.app_update_notification_body)),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(downloadAndInstall)
            .addAction(
                android.R.drawable.stat_sys_download_done,
                context.getString(R.string.app_update_download_install),
                downloadAndInstall,
            )
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(LAST_NOTIFIED_VERSION, update.version).apply()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.app_update_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.app_update_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

class AppUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (!usesVpsReleaseUpdates(BuildConfig.FLAVOR)) return Result.success()
        AppUpdateChecker.checkForUpdate()?.let { update ->
            AppUpdateNotifier.showSystemNotificationIfNew(applicationContext, update)
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "app_update_check_v1"
        private const val IMMEDIATE_WORK_NAME = "app_update_check_now_v1"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val periodic = PeriodicWorkRequestBuilder<AppUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        fun checkNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val immediate = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediate,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }
    }
}
