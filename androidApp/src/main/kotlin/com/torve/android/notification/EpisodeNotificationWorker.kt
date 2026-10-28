package com.torve.android.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.torve.android.BuildConfig
import com.torve.android.MainActivity
import com.torve.android.R
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.auth.UserIdProvider
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.calendar.CalendarViewModel
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class EpisodeNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = checkLock.withLock { performCheck() }
    private suspend fun performCheck(): Result {
        if (BuildConfig.FLAVOR_formFactor != "mobile" || !hasNotificationPermission()) return Result.success()
        val ids: UserIdProvider = getKoin().get()
        val person = ids.currentDataUserIdOrNull() ?: return Result.success()
        if (inputData.getString("person")?.let { it != person } == true) return Result.success()
        return ids.withPersonalDataScope {
            try {
                val prefs: PreferencesRepository = getKoin().get()
                if (prefs.getString(CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) != "true") return@withPersonalDataScope Result.success()
                val diagnostics = applicationContext.getSharedPreferences("episode_alert_checks", Context.MODE_PRIVATE)
                diagnostics.edit().putString("$person:state", "checking").apply()
                val failedRequests = java.util.concurrent.atomic.AtomicInteger()
                fun completed(): Result {
                    if (person != ids.currentDataUserIdOrNull()) return Result.success()
                    val success = failedRequests.get() == 0
                    val edit = diagnostics.edit().putString("$person:state", if (success) "complete" else "retry")
                    if (success) edit.putLong("$person:completed", System.currentTimeMillis())
                    edit.apply()
                    return if (success) Result.success() else Result.retry()
                }
                val history: WatchHistoryRepository = getKoin().get()
                val progress: WatchProgressRepository = getKoin().get()
                val watchlist: WatchlistRepository = getKoin().get()
                val metadata: MetadataRepository = getKoin().get()
                val profiles: ProfileRepository = getKoin().get()
                val policy: com.torve.data.contentpolicy.ContentPolicyRepository = getKoin().get()
                val watchedEntries = history.getAll().filter { MediaType.fromString(it.mediaType) == MediaType.SERIES }
                val seriesProgress = progress.getAllProgress().filter { it.mediaType == MediaType.SERIES }
                val saved = watchlist.getByType("series")
                val tracked = (seriesProgress.sortedByDescending { it.updatedAt }.map { it.mediaId.extractTmdbIdOrNull() } +
                    watchedEntries.sortedByDescending { it.watchedAt }.map { it.mediaId.extractTmdbIdOrNull() } +
                    saved.map { it.tmdbId.takeIf { value -> value > 0 } ?: it.mediaId.extractTmdbIdOrNull() }).filterNotNull().distinct()
                val watched = (watchedEntries.mapNotNull { h -> h.mediaId.extractTmdbIdOrNull()?.let { id ->
                    h.seasonNumber?.let { season -> h.episodeNumber?.let { episode -> "$id:$season:$episode" } } } } +
                    seriesProgress.filter { it.progressPercent >= .85f }.mapNotNull { p -> p.mediaId.extractTmdbIdOrNull()?.let { id ->
                        p.seasonNumber?.let { season -> p.episodeNumber?.let { episode -> "$id:$season:$episode" } } } }).toSet()
                val timeZone = TimeZone.currentSystemDefault()
                val now = Clock.System.now()
                val today = now.toLocalDateTime(timeZone).date
                val maxRating = profiles.getActiveProfile()?.maxContentRating
                val limiter = Semaphore(3)
                val allowedShows = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
                val localReleases = coroutineScope {
                    tracked.map { id -> async { limiter.withPermit {
                        try {
                            val show = metadata.getDetail("tv", id)
                            if (ParentalFilter.filter(listOf(show), maxRating).isEmpty() ||
                                ContentPolicyFilter().filterItems(policy.state.value, ContentAccessContext.HISTORY_DERIVED,
                                    listOf(show), ContentSourceType.TMDB).items.none { !it.isContentPlaceholder && !it.isStubDetail }) return@withPermit emptyList<EpisodeRelease>()
                            allowedShows += id
                            show.seasons.filter { it.seasonNumber > 0 }.sortedByDescending { it.seasonNumber }.take(2).flatMap { season ->
                                val full = if (season.episodes.isEmpty()) metadata.getSeasonDetail(id, season.seasonNumber) else season
                                full.episodes.mapNotNull { episode ->
                                    val date = episode.airDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                                    if (date != null && date in today.minus(1, DateTimeUnit.DAY)..today)
                                        EpisodeRelease(id, show.title, season.seasonNumber, episode.episodeNumber, date) else null
                                }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failedRequests.incrementAndGet()
                            emptyList()
                        }
                    } } }.flatMap { it.await() }
                }
                val releases = localReleases.associateBy { it.key }.toMutableMap()
                val tokens: TraktTokenStore = getKoin().get()
                if (!tokens.accessToken().isNullOrBlank()) {
                    try {
                        val api: TraktAuthorizedApi = getKoin().get()
                        api.getCalendar(days = 2).forEach { entry ->
                            val id = entry.showTmdbId
                            val air = runCatching { Instant.parse(entry.firstAired) }.getOrNull()
                            if (id != null && id in allowedShows && air != null) {
                                val release = EpisodeRelease(id, entry.showTitle, entry.season, entry.episode,
                                    air.toLocalDateTime(timeZone).date, air)
                                releases[release.key] = release
                            }
                        }
                    } catch (error: Exception) { if (error is CancellationException) throw error; failedRequests.incrementAndGet() }
                }
                val journal = applicationContext.getSharedPreferences("episode_alert_delivery", Context.MODE_PRIVATE)
                val delivered = runCatching { Json.decodeFromString(ListSerializer(String.serializer()), journal.getString(person, "[]")!!) }.getOrDefault(emptyList())
                val pending = pendingEpisodeReleases(releases.values.toList(), watched, delivered.toSet(), now, timeZone)
                if (person != ids.currentDataUserIdOrNull() || prefs.getString(CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) != "true") return@withPersonalDataScope Result.success()
                if (pending.isEmpty()) return@withPersonalDataScope completed()
                ensureNotificationChannel()
                val first = pending.first()
                val intent = Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EpisodeAlertTarget.PERSON, person).putExtra(EpisodeAlertTarget.SHOW, first.showId)
                    .putExtra(EpisodeAlertTarget.SEASON, first.season).putExtra(EpisodeAlertTarget.EPISODE, first.episode)
                val tap = PendingIntent.getActivity(applicationContext, NOTIFICATION_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val body = pending.take(3).joinToString(", ") { "${it.showTitle} S${it.season} E${it.episode}" } +
                    if (pending.size > 3) " +${pending.size - 3} more" else ""
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(applicationContext.getString(R.string.notif_new_episodes))
                    .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(tap).setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).setOnlyAlertOnce(true).build()
                if (person != ids.currentDataUserIdOrNull()) return@withPersonalDataScope Result.success()
                try { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
                catch (_: SecurityException) { return@withPersonalDataScope Result.success() }
                journal.edit().putString(person, Json.encodeToString(ListSerializer(String.serializer()),
                    (delivered + pending.map { it.key }).distinct().takeLast(1000))).apply()
                completed()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (person == ids.currentDataUserIdOrNull()) applicationContext.getSharedPreferences("episode_alert_checks", Context.MODE_PRIVATE)
                    .edit().putString("$person:state", "retry").apply()
                Result.retry()
            }
        }
    }
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.notif_channel_episodes), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = applicationContext.getString(R.string.notif_channel_episodes_desc) }
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    private fun hasNotificationPermission() = notificationsAllowed(applicationContext)
    companion object {
        private val checkLock = kotlinx.coroutines.sync.Mutex()
        const val CHANNEL_ID = "episode_notifications"
        const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "episode_notification_check"
        private const val MANUAL_WORK_NAME = "episode_notification_manual"
        fun checkNow(context: Context, person: String) {
            if (BuildConfig.FLAVOR_formFactor != "mobile" || !notificationsAllowed(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EpisodeNotificationWorker>().setInputData(workDataOf("person" to person))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun notificationsAllowed(context: Context): Boolean {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun schedule(context: Context) {
            if (BuildConfig.FLAVOR_formFactor != "mobile") return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EpisodeNotificationWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
        suspend fun scheduleIfEnabled(context: Context) {
            val prefs: PreferencesRepository = getKoin().get()
            if (prefs.getString(CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true") schedule(context) else cancel(context)
        }
    }
}
