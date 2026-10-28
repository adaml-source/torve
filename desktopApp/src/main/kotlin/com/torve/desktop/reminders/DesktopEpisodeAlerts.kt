package com.torve.desktop.reminders

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.watching.WatchingHubViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DesktopEpisodeAlertState(val checking: Boolean = false, val completedAt: Long? = null,
    val error: String? = null, val person: String? = null)

/** Checks while Torve is running, including when its window is in the system tray. */
class DesktopEpisodeAlerts(private val watching: WatchingHubViewModel, private val ids: UserIdProvider,
    private val prefs: PreferencesRepository, private val notify: (String, String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timer: Job? = null
    private var checkJob: Job? = null
    private val mutable = MutableStateFlow(DesktopEpisodeAlertState())
    val state = mutable.asStateFlow()
    fun start() {
        if (timer?.isActive == true) return
        timer = scope.launch {
            while (isActive) { check(); delay(6 * 60 * 60_000L) }
        }
    }
    fun check() {
        if (checkJob?.isActive == true) return
        val person = ids.currentDataUserIdOrNull() ?: return
        checkJob = scope.launch {
            try { ids.withPersonalDataScope {
                if (prefs.getString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) != "true") return@withPersonalDataScope
                mutable.value = DesktopEpisodeAlertState(checking = true, person = person,
                    completedAt = prefs.getString("desktop_episode_alert_completed")?.toLongOrNull())
                watching.refresh(force = true)
                val result = withTimeout(120_000) { watching.state.first { !it.loading && it.personalDataUserId == person } }
                check(result.error == null && result.incompleteShows == 0) { "Some shows could not be checked. Try again." }
                val delivered = prefs.getString("desktop_episode_alert_delivered")?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()
                val releases = result.calendar.mapNotNull { row -> row.show.tmdbId?.let { id ->
                    EpisodeRelease(id, row.show.title, row.season, row.episode.episodeNumber, row.date) } }
                val watched = result.calendar.filter { it.watched }.mapNotNull { row -> row.show.tmdbId?.let { "$it:${row.season}:${row.episode.episodeNumber}" } }.toSet()
                val pending = pendingEpisodeReleases(releases, watched, delivered.toSet(), Clock.System.now(), TimeZone.currentSystemDefault())
                if (person != ids.currentDataUserIdOrNull() || prefs.getString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) != "true") return@withPersonalDataScope
                if (pending.isNotEmpty()) {
                    notify("New episodes", pending.take(3).joinToString(", ") { "${it.showTitle} S${it.season} E${it.episode}" })
                    prefs.setString("desktop_episode_alert_delivered", Json.encodeToString((delivered + pending.map { it.key }).distinct().takeLast(1000)))
                }
                val completed = Clock.System.now().toEpochMilliseconds()
                prefs.setString("desktop_episode_alert_completed", completed.toString())
                mutable.value = DesktopEpisodeAlertState(completedAt = completed, person = person)
            } }
            catch (cancelled: CancellationException) { if (person == ids.currentDataUserIdOrNull()) mutable.update { it.copy(checking = false) }; throw cancelled }
            catch (_: Exception) { if (person == ids.currentDataUserIdOrNull()) mutable.update { it.copy(checking = false, error = "Episode check failed. Try again.") } }
        }
    }
    fun stop() { timer?.cancel(); checkJob?.cancel(); timer = null; checkJob = null }
}
