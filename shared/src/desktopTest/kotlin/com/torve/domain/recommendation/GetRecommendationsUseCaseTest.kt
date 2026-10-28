package com.torve.domain.recommendation

import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.data.PersonalDeviceFixture
import com.torve.data.auth.UserIdProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.*

class GetRecommendationsUseCaseTest {
    private inline fun <reified T> fake(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    private fun item(id: Int, type: MediaType = MediaType.SERIES, title: String = "Show $id") =
        MediaItem("tmdb:$id", tmdbId = id, type = type, title = title, genres = listOf(Genre(18, "Drama")), rating = 8.0)
    private fun history(id: String, type: String = "series", episode: Int = 1) =
        WatchHistoryEntry("$id:$episode", id, type, "Watched", null, null, episode.toLong(), 0, 1, episode, "Watched")
    private fun useCase(history: List<WatchHistoryEntry>, progress: List<WatchProgress> = emptyList(), saved: List<WatchlistItem> = emptyList(),
        preferences: PreferencesRepository? = null, ids: UserIdProvider? = null,
        onMetadata: (String, Array<out Any?>) -> Any? = { method, args -> when (method) {
            "getDetail" -> item(args[1] as Int, if (args[0] == "tv") MediaType.SERIES else MediaType.MOVIE)
            "getSimilar", "getRecommendations" -> listOf(item(99), item(1))
            "discover" -> PagedResult(listOf(item(99)), 1, 1, 1)
            else -> emptyList<MediaItem>()
        } }): GetRecommendationsUseCase = GetRecommendationsUseCase(
        fake<WatchProgressRepository> { _, _ -> progress }, fake<MetadataRepository>(onMetadata),
        fake<WatchHistoryRepository> { _, _ -> history }, fake<WatchlistRepository> { _, _ -> saved }, preferences, ids)

    @Test fun feedbackSurvivesRecreationNormalizesAliasesAndStaysInItsProfile() = runTest {
        PersonalDeviceFixture(HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            val first = useCase(listOf(history("1")), preferences = device.preferences, ids = device.ids)
            first.dismiss(item(99).copy(id = "99"))
            assertTrue(useCase(listOf(history("1")), preferences = device.preferences, ids = device.ids).execute().isEmpty())
            device.select("other")
            assertEquals(99, first.execute().single().item.tmdbId)
            first.resetFeedback()
            device.select("default")
            assertTrue(first.execute().isEmpty())
            first.resetFeedback()
            assertEquals(99, first.execute().single().item.tmdbId)
        }
    }

    @Test fun corruptFeedbackDoesNotPreventRecommendations() = runTest {
        val prefs = fake<PreferencesRepository> { _, _ -> "not json" }
        assertEquals(99, useCase(listOf(history("1")), preferences = prefs).execute().single().item.tmdbId)
    }

    @Test fun manuallyMarkedHistoryGeneratesRecommendationsWithoutPlaybackProgress() = runTest {
        val result = useCase(listOf(history("1"))).execute()
        assertEquals(listOf(99), result.map { it.item.tmdbId })
        assertEquals("Because you watched Show 1", result.single().reason)
    }
    @Test fun multipleEpisodesOfOneSeriesOnlyFetchOneSeed() = runTest {
        var details = 0
        val result = useCase((1..40).map { history("1", episode = it) }, onMetadata = { method, args -> when (method) {
            "getDetail" -> { details++; item(args[1] as Int) }
            "discover" -> PagedResult(listOf(item(99)), 1, 1, 1)
            else -> listOf(item(99))
        } }).execute()
        assertEquals(1, details); assertEquals(1, result.size)
    }
    @Test fun seriesTasteUsesTvGenresInsteadOfMovieOnlyDiscovery() = runTest {
        val discovered = mutableListOf<String>()
        useCase(listOf(history("1")), onMetadata = { method, args -> when (method) {
            "getDetail" -> item(1)
            "discover" -> { discovered += args[0] as String; PagedResult(listOf(item(99)), 1, 1, 1) }
            else -> listOf(item(99))
        } }).execute()
        assertEquals(listOf("tv"), discovered)
    }
    @Test fun savedTitlesProvideAnExplainableColdStartAndAreNotSuggestedAgain() = runTest {
        val saved = WatchlistItem("1", MediaType.SERIES, 1, title = "Show 1", addedAt = 123)
        val result = useCase(emptyList(), saved = listOf(saved)).execute()
        assertEquals("Because you saved Show 1", result.single().reason)
        assertEquals(99, result.single().item.tmdbId)
    }
    @Test fun aliasesAreDeduplicatedButMovieAndSeriesWithTheSameIdStayDistinct() = runTest {
        val result = useCase(listOf(history("1")), onMetadata = { method, _ -> when (method) {
            "getDetail" -> item(1)
            "discover" -> PagedResult(emptyList(), 1, 1, 0)
            else -> listOf(item(99), item(99).copy(id = "99"), item(99, MediaType.MOVIE), item(1))
        } }).execute()
        assertEquals(2, result.size); assertEquals(setOf(MediaType.SERIES, MediaType.MOVIE), result.map { it.item.type }.toSet())
    }
    @Test fun profileCancellationIsNotSwallowedByBestEffortMetadata() = runTest {
        assertFailsWith<CancellationException> { useCase(listOf(history("1")), onMetadata = { _, _ -> throw CancellationException("Profile changed") }).execute() }
    }
    @Test fun noHistoryAndNonPositiveLimitsDoNotFetchMetadata() = runTest {
        val useCase = useCase(emptyList(), onMetadata = { _, _ -> error("No network needed") })
        assertTrue(useCase.execute().isEmpty()); assertTrue(useCase.execute(0).isEmpty())
    }
}
