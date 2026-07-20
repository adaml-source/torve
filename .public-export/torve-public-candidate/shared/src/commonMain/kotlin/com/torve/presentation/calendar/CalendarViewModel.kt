package com.torve.presentation.calendar

import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import com.torve.data.trakt.api.TraktCalendarResult
import com.torve.data.trakt.TraktDecodeException
import com.torve.data.trakt.TraktNetworkException
import com.torve.data.trakt.TraktRateLimitedException
import com.torve.data.trakt.TraktServerException
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
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

class CalendarViewModel internal constructor(
    private val dataSource: CalendarDataSource,
    private val prefsRepo: PreferencesRepository,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val diagnostics: CalendarDiagnosticsLogger = CalendarDiagnosticsLogger.Stdout,
) {
    constructor(
        traktApi: TraktAuthorizedApi,
        tokenStore: TraktTokenStore,
        prefsRepo: PreferencesRepository,
    ) : this(
        dataSource = TraktCalendarDataSource(traktApi, tokenStore),
        prefsRepo = prefsRepo,
    )

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        checkConnectionAndLoad(CalendarLoadSource.INITIAL)
    }

    fun refresh() {
        checkConnectionAndLoad(CalendarLoadSource.RETRY)
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

    private fun checkConnectionAndLoad(source: CalendarLoadSource) {
        scope.launch {
            val connected = runCatching { dataSource.accessToken().isNullOrBlank().not() }
                .getOrElse { error ->
                    diagnostics.log(
                        CalendarDiagnosticsEvent(
                            source = source,
                            mappedErrorCode = CalendarMappedError.NETWORK.code,
                            exceptionClass = error::class.simpleName ?: "Unknown",
                            httpStatus = extractHttpStatus(error),
                        ),
                    )
                    false
                }
            val notificationsEnabled = prefsRepo.getString(KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true"
            _state.update {
                it.copy(
                    traktConnected = connected,
                    requiresTraktReconnect = false,
                    error = null,
                    isLoading = false,
                    refreshWarning = null,
                    episodeNotificationsEnabled = connected && notificationsEnabled,
                )
            }
            if (connected) {
                loadCalendar(source)
            }
        }
    }

    private suspend fun loadCalendar(source: CalendarLoadSource) {
        _state.update { it.copy(isLoading = true, error = null, requiresTraktReconnect = false) }
        try {
            val result = dataSource.getCalendar(days = CALENDAR_LOOKAHEAD_DAYS)
            val episodes = result.episodes
            val grouped = groupEpisodesByDate(episodes)
            _state.update {
                it.copy(
                    episodes = episodes,
                    groupedEpisodes = grouped,
                    isLoading = false,
                    error = null,
                    traktConnected = true,
                    requiresTraktReconnect = false,
                    refreshWarning = if (result.isStale) {
                        staleReasonFor(result.refreshError)
                    } else {
                        null
                    },
                )
            }
        } catch (e: Exception) {
            val mapped = mapCalendarLoadError(e)
            diagnostics.log(
                CalendarDiagnosticsEvent(
                    source = source,
                    mappedErrorCode = mapped.code,
                    exceptionClass = e::class.simpleName ?: "Unknown",
                    httpStatus = extractHttpStatus(e),
                ),
            )
            _state.update { current ->
                when (mapped) {
                    CalendarMappedError.RECONNECT_REQUIRED -> current.copy(
                        isLoading = false,
                        error = null,
                        traktConnected = false,
                        requiresTraktReconnect = true,
                        episodeNotificationsEnabled = false,
                    )
                    else -> current.copy(
                        isLoading = false,
                        error = mapped.messageKey,
                        requiresTraktReconnect = false,
                        refreshWarning = null,
                    )
                }
            }
        }
    }

    private fun mapCalendarLoadError(error: Throwable): CalendarMappedError {
        val message = error.message.orEmpty().lowercase()
        return when {
            error is TraktAuthorizationRequiredException -> CalendarMappedError.RECONNECT_REQUIRED
            error is TraktRateLimitedException -> CalendarMappedError.RATE_LIMITED
            error is TraktNetworkException -> CalendarMappedError.NETWORK
            error is TraktServerException -> CalendarMappedError.DATA_UNAVAILABLE
            error is TraktDecodeException -> CalendarMappedError.DATA_UNAVAILABLE
            "401" in message || "unauthorized" in message || "authentication required" in message ||
                "invalid_grant" in message || "revoked" in message -> CalendarMappedError.RECONNECT_REQUIRED
            "429" in message || "rate-limiting" in message || "rate limiting" in message ||
                "rate_limited" in message || ("rate" in message && "limit" in message) -> CalendarMappedError.RATE_LIMITED
            "unable to resolve host" in message || "no address associated" in message ||
                "network" in message || "timeout" in message || "timed out" in message ||
                ("connect" in message && "refused" in message) -> CalendarMappedError.NETWORK
            "could not be decoded" in message || "serialization" in message || "malformed" in message ->
                CalendarMappedError.DATA_UNAVAILABLE
            else -> CalendarMappedError.DATA_UNAVAILABLE
        }
    }

    private fun staleReasonFor(error: Throwable?): CalendarStaleReason =
        when (error) {
            is TraktRateLimitedException -> CalendarStaleReason.RATE_LIMITED
            is TraktNetworkException -> CalendarStaleReason.NETWORK
            is TraktServerException -> CalendarStaleReason.SERVER
            else -> CalendarStaleReason.UNKNOWN
        }

    private fun extractHttpStatus(error: Throwable): Int? {
        val message = error.message.orEmpty()
        return Regex("""\bHTTP\s+(\d{3})\b|\b(\d{3})\b""")
            .find(message)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.toIntOrNull()
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
        }
            .toList()
            .sortedBy { (key, _) ->
                when (key) {
                    "Today" -> "0"
                    "Tomorrow" -> "1"
                    else -> "2_$key"
                }
            }
            .toMap()
    }

    companion object {
        const val KEY_EPISODE_NOTIFICATIONS_ENABLED = "calendar_episode_notifications_enabled"
        const val CALENDAR_LOOKAHEAD_DAYS = 33
    }
}

internal interface CalendarDataSource {
    suspend fun accessToken(): String?
    suspend fun getCalendar(days: Int): TraktCalendarResult
}

private class TraktCalendarDataSource(
    private val traktApi: TraktAuthorizedApi,
    private val tokenStore: TraktTokenStore,
) : CalendarDataSource {
    override suspend fun accessToken(): String? = tokenStore.accessToken()
    override suspend fun getCalendar(days: Int): TraktCalendarResult = traktApi.getCalendarCached(days)
}

internal data class CalendarDiagnosticsEvent(
    val source: CalendarLoadSource,
    val mappedErrorCode: String,
    val exceptionClass: String,
    val httpStatus: Int?,
)

internal fun interface CalendarDiagnosticsLogger {
    fun log(event: CalendarDiagnosticsEvent)

    object Stdout : CalendarDiagnosticsLogger {
        override fun log(event: CalendarDiagnosticsEvent) {
            println(
                "calendar_load_failure " +
                    "feature=calendar " +
                    "operation=trakt_calendar_load " +
                    "days=${CalendarViewModel.CALENDAR_LOOKAHEAD_DAYS} " +
                    "source=${event.source.logName} " +
                    "mapped_error_code=${event.mappedErrorCode} " +
                    "exception_class=${event.exceptionClass} " +
                    "http_status=${event.httpStatus ?: "none"}",
            )
        }
    }
}

internal enum class CalendarLoadSource(val logName: String) {
    INITIAL("initial_load"),
    RETRY("retry"),
}

private enum class CalendarMappedError(
    val code: String,
    val messageKey: String?,
) {
    RECONNECT_REQUIRED("calendar_reconnect_required", null),
    RATE_LIMITED("calendar_rate_limited", com.torve.presentation.error.UserFacingError.RATE_LIMITED.messageKey),
    NETWORK("calendar_network_unavailable", com.torve.presentation.error.UserFacingError.NETWORK_FAILURE.messageKey),
    DATA_UNAVAILABLE("calendar_data_unavailable", com.torve.presentation.error.UserFacingError.CALENDAR_LOAD_FAILED.messageKey),
}
