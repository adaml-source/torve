@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.torve.desktop.search

import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.*
import org.junit.Test

class DesktopSearchControllerTest {
    @Test fun revisitingDetailsKeepsTheEpisodeAndResetInvalidatesTheSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var detailRequests = 0
        val metadata = Proxy.newProxyInstance(MetadataRepository::class.java.classLoader, arrayOf(MetadataRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getDetail" -> { detailRequests++; MediaItem(args[1].toString(), type = MediaType.SERIES, title = "Show ${args[1]}", tmdbId = args[1] as Int) }
                "getSeasonDetail" -> Season(seasonNumber = args[1] as Int, episodeCount = 0, name = "Season", episodes = emptyList())
                else -> emptyList<MediaItem>()
            }
        } as MetadataRepository
        val controller = DesktopSearchController(metadata)
        try {
            val first = MediaItem("1", type = MediaType.SERIES, title = "One", tmdbId = 1)
            controller.selectResult(first, 2, 6); advanceUntilIdle()
            assertEquals(2, controller.state.value.selectedSeasonNumber); assertEquals(6, controller.state.value.selectedEpisodeNumber)
            controller.selectResult(MediaItem("2", type = MediaType.SERIES, title = "Two", tmdbId = 2)); advanceUntilIdle()
            controller.selectResult(first); advanceUntilIdle()
            assertEquals(2, detailRequests); assertEquals(6, controller.state.value.selectedEpisodeNumber)
            controller.reset(); assertNull(controller.state.value.detailItem)
            controller.selectResult(first); advanceUntilIdle(); assertEquals(3, detailRequests)
        } finally { controller.dispose(); Dispatchers.resetMain() }
    }

    @Test fun aSlowSeasonCannotReplaceTheNewerExactEpisodeSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val metadata = Proxy.newProxyInstance(MetadataRepository::class.java.classLoader, arrayOf(MetadataRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getDetail" -> MediaItem("1", type = MediaType.SERIES, title = "One", tmdbId = 1)
                "getSeasonDetail" -> {
                    val number = args[1] as Int
                    @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Season>
                    CoroutineScope(continuation.context).launch { delay(if (number == 1) 200 else 10); continuation.resume(Season(seasonNumber = number, episodeCount = 0, name = "Season $number", episodes = emptyList())) }
                    COROUTINE_SUSPENDED
                }
                else -> emptyList<MediaItem>()
            }
        } as MetadataRepository
        val controller = DesktopSearchController(metadata)
        try {
            controller.selectResult(MediaItem("1", type = MediaType.SERIES, title = "One", tmdbId = 1)); runCurrent()
            controller.selectSeason(1, 2); runCurrent()
            controller.selectSeason(2, 6); advanceUntilIdle()
            assertEquals(2, controller.state.value.selectedSeasonNumber)
            assertEquals(2, controller.state.value.selectedSeason?.seasonNumber)
            assertEquals(6, controller.state.value.selectedEpisodeNumber)
        } finally { controller.dispose(); Dispatchers.resetMain() }
    }
}
