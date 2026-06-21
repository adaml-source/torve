package com.torve.presentation.calendar

import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.data.trakt.TraktNetworkException
import com.torve.data.trakt.TraktRateLimitedException
import com.torve.data.trakt.TraktRequestBucket
import com.torve.data.trakt.TraktServerException
import com.torve.data.trakt.api.TraktAuthorizationRequiredException
import com.torve.data.trakt.api.TraktCalendarResult
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.error.UserFacingError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun blank_token_does_not_call_calendar_and_shows_connect_state() = runTest(dispatcher) {
        val dataSource = FakeCalendarDataSource(token = null)
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertFalse(viewModel.state.value.traktConnected)
        assertFalse(viewModel.state.value.requiresTraktReconnect)
        assertNull(viewModel.state.value.error)
        assertEquals(0, dataSource.calendarCalls)
    }

    @Test
    fun successful_getCalendar_produces_content() = runTest(dispatcher) {
        val episode = sampleEpisode()
        val dataSource = FakeCalendarDataSource().apply {
            enqueueSuccess(listOf(episode))
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertTrue(viewModel.state.value.traktConnected)
        assertNull(viewModel.state.value.error)
        assertEquals(listOf(episode), viewModel.state.value.episodes)
        assertEquals(listOf(CalendarViewModel.CALENDAR_LOOKAHEAD_DAYS), dataSource.requestedDays)
    }

    @Test
    fun retry_from_error_calls_calendar_again_and_recovers() = runTest(dispatcher) {
        val episode = sampleEpisode(showTitle = "Recovered Show")
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(IllegalStateException("temporary failure"))
            enqueueSuccess(listOf(episode))
        }
        val viewModel = newViewModel(dataSource)
        advanceUntilIdle()
        assertEquals(UserFacingError.CALENDAR_LOAD_FAILED.messageKey, viewModel.state.value.error)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, dataSource.calendarCalls)
        assertNull(viewModel.state.value.error)
        assertEquals(listOf(episode), viewModel.state.value.episodes)
    }

    @Test
    fun retry_shows_loading_state_before_reattempt_completes() = runTest(dispatcher) {
        val retryResult = CompletableDeferred<TraktCalendarResult>()
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(IllegalStateException("temporary failure"))
            enqueueDeferred(retryResult)
        }
        val viewModel = newViewModel(dataSource)
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent()

        assertTrue(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)

        retryResult.complete(TraktCalendarResult(listOf(sampleEpisode()), isStale = false))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun trakt_authorization_required_maps_to_reconnect_state() = runTest(dispatcher) {
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(TraktAuthorizationRequiredException())
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertFalse(viewModel.state.value.traktConnected)
        assertTrue(viewModel.state.value.requiresTraktReconnect)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun rate_limit_exception_maps_to_rate_limited_error() = runTest(dispatcher) {
        val events = mutableListOf<CalendarDiagnosticsEvent>()
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(IllegalStateException("Trakt is rate-limiting your account (HTTP 429)."))
        }
        val viewModel = newViewModel(dataSource, events)

        advanceUntilIdle()

        assertEquals(UserFacingError.RATE_LIMITED.messageKey, viewModel.state.value.error)
        assertEquals("calendar_rate_limited", events.single().mappedErrorCode)
        assertEquals(429, events.single().httpStatus)
    }

    @Test
    fun network_exception_maps_to_network_error() = runTest(dispatcher) {
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(IllegalStateException("Unable to resolve host api.trakt.tv"))
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(UserFacingError.NETWORK_FAILURE.messageKey, viewModel.state.value.error)
    }

    @Test
    fun decode_exception_maps_to_sanitized_calendar_error() = runTest(dispatcher) {
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(IllegalStateException("Trakt response could not be decoded: token ABC123"))
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(UserFacingError.CALENDAR_LOAD_FAILED.messageKey, viewModel.state.value.error)
    }

    @Test
    fun raw_exception_message_is_never_exposed_in_ui_state() = runTest(dispatcher) {
        val dataSource = FakeCalendarDataSource().apply {
            enqueueFailure(IllegalStateException("Bearer secret_access_token raw provider body"))
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(UserFacingError.CALENDAR_LOAD_FAILED.messageKey, viewModel.state.value.error)
        assertFalse(viewModel.state.value.error.orEmpty().contains("secret_access_token"))
    }

    @Test
    fun rate_limit_stale_calendar_result_renders_rate_limit_warning() = runTest(dispatcher) {
        val episode = sampleEpisode(showTitle = "Cached Show")
        val dataSource = FakeCalendarDataSource().apply {
            enqueueResult(
                TraktCalendarResult(
                    listOf(episode),
                    isStale = true,
                    refreshError = TraktRateLimitedException(60, TraktRequestBucket.AUTHENTICATED_GET),
                ),
            )
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(listOf(episode), viewModel.state.value.episodes)
        assertEquals(CalendarStaleReason.RATE_LIMITED, viewModel.state.value.refreshWarning)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun network_stale_calendar_result_renders_network_warning() = runTest(dispatcher) {
        val episode = sampleEpisode(showTitle = "Cached Show")
        val dataSource = FakeCalendarDataSource().apply {
            enqueueResult(
                TraktCalendarResult(
                    listOf(episode),
                    isStale = true,
                    refreshError = TraktNetworkException(),
                ),
            )
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(CalendarStaleReason.NETWORK, viewModel.state.value.refreshWarning)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun server_stale_calendar_result_renders_server_warning() = runTest(dispatcher) {
        val episode = sampleEpisode(showTitle = "Cached Show")
        val dataSource = FakeCalendarDataSource().apply {
            enqueueResult(
                TraktCalendarResult(
                    listOf(episode),
                    isStale = true,
                    refreshError = TraktServerException(503),
                ),
            )
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(CalendarStaleReason.SERVER, viewModel.state.value.refreshWarning)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun unknown_stale_calendar_result_renders_generic_warning() = runTest(dispatcher) {
        val episode = sampleEpisode(showTitle = "Cached Show")
        val dataSource = FakeCalendarDataSource().apply {
            enqueueResult(TraktCalendarResult(listOf(episode), isStale = true))
        }
        val viewModel = newViewModel(dataSource)

        advanceUntilIdle()

        assertEquals(CalendarStaleReason.UNKNOWN, viewModel.state.value.refreshWarning)
        assertNull(viewModel.state.value.error)
    }

    private fun newViewModel(
        dataSource: FakeCalendarDataSource,
        diagnosticsEvents: MutableList<CalendarDiagnosticsEvent> = mutableListOf(),
    ): CalendarViewModel {
        return CalendarViewModel(
            dataSource = dataSource,
            prefsRepo = FakePreferencesRepository(),
            mainDispatcher = dispatcher,
            diagnostics = CalendarDiagnosticsLogger { diagnosticsEvents += it },
        )
    }

    private fun sampleEpisode(showTitle: String = "The Show") = TraktCalendarEpisode(
        showTitle = showTitle,
        season = 1,
        episode = 2,
        episodeTitle = "The Episode",
        firstAired = "2026-05-25T20:00:00.000Z",
        showTmdbId = 123,
    )
}

private class FakeCalendarDataSource(
    var token: String? = "token",
) : CalendarDataSource {
    private val responses = mutableListOf<suspend () -> TraktCalendarResult>()
    val requestedDays = mutableListOf<Int>()
    var calendarCalls = 0
        private set

    fun enqueueSuccess(episodes: List<TraktCalendarEpisode>) {
        enqueueResult(TraktCalendarResult(episodes, isStale = false))
    }

    fun enqueueResult(result: TraktCalendarResult) {
        responses += { result }
    }

    fun enqueueFailure(error: Exception) {
        responses += { throw error }
    }

    fun enqueueDeferred(deferred: CompletableDeferred<TraktCalendarResult>) {
        responses += { deferred.await() }
    }

    override suspend fun accessToken(): String? = token

    override suspend fun getCalendar(days: Int): TraktCalendarResult {
        calendarCalls++
        requestedDays += days
        return responses.removeAt(0).invoke()
    }
}

private class FakePreferencesRepository : PreferencesRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
