package com.torve.presentation.library

import com.torve.domain.integrations.*
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LibraryRequestMonitorTest {
    @Test fun disabledArrDoesNotPollOrDeliverQueuedNoticesAndRetainsTheRequest() = runTest {
        val prefs = Prefs()
        val service = Service()
        val monitor = LibraryRequestMonitor(service, prefs)
        monitor.track(MediaLifecycleStatus(42, MediaType.MOVIE, MediaLifecycleState.PROCESSING), "Movie")
        prefs.setString(com.torve.presentation.settings.OptionalAppFeature.ARR_STACK.preferenceKey, "false")
        service.state = MediaLifecycleState.AVAILABLE
        assertTrue(monitor.poll().isEmpty())
        assertEquals(0, service.calls)
        prefs.setString(com.torve.presentation.settings.OptionalAppFeature.ARR_STACK.preferenceKey, "true")
        assertEquals("Movie", monitor.poll().single().title)
    }
    private class Prefs : PreferencesRepository {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private class Service : MediaLifecycleService {
        var state = MediaLifecycleState.PROCESSING
        var calls = 0
        var fail = false
        override suspend fun isConfigured() = true
        override suspend fun testConnection(serverUrl: String, apiKey: String) = true
        override suspend fun getStatus(tmdbId: Int, mediaType: MediaType, seasons: List<Int>, is4k: Boolean): MediaLifecycleStatus {
            calls++
            if (fail) error("offline")
            return MediaLifecycleStatus(tmdbId, mediaType, state, is4k = is4k)
        }
        override suspend fun request(request: MediaLifecycleRequest) = getStatus(request.tmdbId, request.mediaType, request.seasons, request.is4k)
        override suspend fun retry(requestId: Int) = getStatus(42, MediaType.MOVIE, emptyList(), false)
    }
    @Test fun completionSurvivesLeavingTheScreenAndRestartUntilAcknowledged() = runTest {
        val service = Service()
        val prefs = Prefs()
        LibraryRequestMonitor(service, prefs).track(MediaLifecycleStatus(42, MediaType.MOVIE, MediaLifecycleState.PROCESSING), "Resident Evil")
        service.state = MediaLifecycleState.AVAILABLE
        val restarted = LibraryRequestMonitor(service, prefs)
        val notice = restarted.poll().single()
        assertEquals("Resident Evil is now available in your library", notice.message)
        assertEquals(notice, LibraryRequestMonitor(service, prefs).poll().single())
        assertEquals(1, service.calls)
        restarted.acknowledge(notice.key)
        assertTrue(restarted.poll().isEmpty())
    }
    @Test fun emptyMonitorAndAlreadyAvailableTitlesDoNotPollOrAnnounce() = runTest {
        val service = Service()
        val monitor = LibraryRequestMonitor(service, Prefs())
        assertTrue(monitor.poll().isEmpty())
        monitor.track(MediaLifecycleStatus(42, MediaType.MOVIE, MediaLifecycleState.AVAILABLE), "Movie")
        assertTrue(monitor.poll().isEmpty())
        assertEquals(0, service.calls)
    }
    @Test fun outageKeepsPendingRequestAndCancellationStopsPolling() = runTest {
        val service = Service()
        val monitor = LibraryRequestMonitor(service, Prefs())
        monitor.track(MediaLifecycleStatus(42, MediaType.SERIES, MediaLifecycleState.PROCESSING), "Show", listOf(2))
        service.fail = true
        assertTrue(monitor.poll().isEmpty())
        service.fail = false
        service.state = MediaLifecycleState.DECLINED
        assertEquals(MediaLifecycleState.DECLINED, monitor.poll().single().state)
        monitor.forget(42, MediaType.SERIES)
        assertTrue(monitor.poll().isEmpty())
        assertEquals(2, service.calls)
    }
    @Test fun releaseFilenameProducesReadableSeriesTitle() {
        assertEquals("Two and a Half Men", "Two.and.a.Half.Men.S02.1080p.BluRay".releaseDisplayTitle())
        assertEquals("Show", "Show.S03E06.2160p".releaseDisplayTitle())
        assertEquals("S.W.A.T.", "S.W.A.T.".releaseDisplayTitle())
    }
}
