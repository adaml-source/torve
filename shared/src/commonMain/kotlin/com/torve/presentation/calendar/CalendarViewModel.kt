package com.torve.presentation.calendar

import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

class CalendarViewModel(
    private val traktApi: TraktAuthorizedApi,
    private val tokenStore: TraktTokenStore,
    private val prefsRepo: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        checkConnectionAndLoad()
    }

    fun refresh() {
        checkConnectionAndLoad()
    }

    fun setEpisodeNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            if (enabled) {
                prefsRepo.setString(KEY_EPISODE_NOTIFICATIONS_ENABLED, "true")
            } else {
                prefsRepo.setString(KEY_EPISODE_NOTIFICATIONS_ENABLED, "false")
            }
            _state.update { it.copy(episodeNotificationsEnabled = enabled) }
        }
    }

    private fun checkConnectionAndLoad() {
        scope.launch {
            val connected = tokenStore.accessToken().isNullOrBlank().not()
            val notificationsEnabled = prefsRepo.getString(KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true"
            _state.update {
                it.copy(
                    traktConnected = connected,
                    episodeNotificationsEnabled = connected && notificationsEnabled,
                )
            }
            if (connected) {
                loadCalendar()
            }
        }
    }

    private suspend fun loadCalendar() {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            val episodes = traktApi.getCalendar(days = 33)
            val grouped = groupEpisodesByDate(episodes)
            _state.update {
                it.copy(
                    episodes = episodes,
                    groupedEpisodes = grouped,
                    isLoading = false,
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.CALENDAR_LOAD_FAILED.messageKey)
            }
        }
    }

    private fun groupEpisodesByDate(
        episodes: List<TraktCalendarEpisode>,
    ): Map<String, List<TraktCalendarEpisode>> {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val tomorrow = today.plus(1, DateTimeUnit.DAY)

        return episodes.groupBy { ep ->
            try {
                val instant = Instant.parse(ep.firstAired)
                val localDate = instant.toLocalDateTime(tz).date
                when (localDate) {
                    today -> "Today"
                    tomorrow -> "Tomorrow"
                    else -> {
                        val dow = localDate.dayOfWeek.name.take(3).lowercase()
                            .replaceFirstChar { it.uppercase() }
                        val month = localDate.month.name.take(3).lowercase()
                            .replaceFirstChar { it.uppercase() }
                        "$dow, $month ${localDate.dayOfMonth}"
                    }
                }
            } catch (_: Exception) {
                "Unknown Date"
            }
        }.toSortedMap(compareBy { key ->
            when (key) {
                "Today" -> "0"
                "Tomorrow" -> "1"
                else -> "2_$key"
            }
        })
    }

    companion object {
        const val KEY_EPISODE_NOTIFICATIONS_ENABLED = "calendar_episode_notifications_enabled"
    }
}
