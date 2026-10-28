package com.torve.data.playback

import com.torve.domain.player.MediaIdentity
import com.torve.domain.player.PlaybackSegmentRequest
import com.torve.domain.player.SegmentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroDbSegmentMarkerProviderTest {
    @Test
    fun bigBangAggregateMapsIntroRecapAndOutroToTypedMarkers() = runTest {
        var requestPath = ""
        var requestQuery = ""
        val provider = provider(
            """
            {
              "imdb_id":"tt0898266",
              "season":8,
              "episode":1,
              "intro":{"start_ms":133000,"end_ms":156000,"confidence":1,"submission_count":1},
              "recap":{"start_ms":3500,"end_ms":38000,"confidence":1,"submission_count":1},
              "outro":{"start_ms":1240000,"end_ms":1268000,"confidence":1,"submission_count":1}
            }
            """.trimIndent(),
            capture = { path, query -> requestPath = path; requestQuery = query },
        )

        val markers = provider.getSegments(request(runtimeMs = 1_268_000L))

        assertEquals("/segments", requestPath)
        assertTrue("imdb_id=tt0898266" in requestQuery)
        assertTrue("season=8" in requestQuery)
        assertTrue("episode=1" in requestQuery)
        assertEquals(listOf(SegmentType.INTRO, SegmentType.RECAP, SegmentType.CREDITS), markers.map { it.type })
        assertTrue(markers.all { it.referenceRuntimeReliable })
        assertTrue(markers.all { it.confidence in 0.62..<0.88 })
    }

    @Test
    fun mismatchedIdentityAndUnsafeTimestampsAreRejected() = runTest {
        val identityMismatch = provider(
            """{"imdb_id":"tt0903747","season":8,"episode":1,"intro":{"start_ms":1,"end_ms":20,"confidence":1,"submission_count":1}}""",
        )
        assertTrue(identityMismatch.getSegments(request()).isEmpty())

        val malformed = provider(
            """{"imdb_id":"tt0898266","season":8,"episode":1,"intro":{"start_ms":-1,"end_ms":99999999,"confidence":1,"submission_count":1}}""",
        )
        assertTrue(malformed.getSegments(request()).isEmpty())
    }

    @Test
    fun providerWithoutRuntimeAnchorRemainsMarkedUnreconciled() = runTest {
        val provider = provider(
            """{"imdb_id":"tt0898266","season":8,"episode":1,"intro":{"start_ms":133000,"end_ms":156000,"confidence":1,"submission_count":2}}""",
        )

        val marker = provider.getSegments(request()).single()

        assertFalse(marker.referenceRuntimeReliable)
    }

    @Test
    fun invalidLookupDoesNotTouchNetwork() = runTest {
        var called = false
        val provider = IntroDbSegmentMarkerProvider(
            HttpClient(MockEngine {
                called = true
                respond("{}", HttpStatusCode.OK)
            }),
            baseUrl = "https://example.test",
        )

        assertTrue(provider.getSegments(request().copy(showImdbId = "not-imdb")).isEmpty())
        assertFalse(called)
    }

    @Test
    fun excessiveDeclaredPayloadIsRejectedBeforeParsing() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = "x".repeat(70_000),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "70000"),
            )
        })
        val provider = IntroDbSegmentMarkerProvider(client, baseUrl = "https://example.test")

        assertTrue(provider.getSegments(request()).isEmpty())
    }

    private fun provider(
        body: String,
        capture: (String, String) -> Unit = { _, _ -> },
    ): IntroDbSegmentMarkerProvider {
        val client = HttpClient(MockEngine { request ->
            capture(request.url.encodedPath, request.url.encodedQuery)
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        return IntroDbSegmentMarkerProvider(client, baseUrl = "https://example.test")
    }

    private fun request(runtimeMs: Long = 1_268_000L) = PlaybackSegmentRequest(
        media = MediaIdentity(
            canonicalEpisodeId = "1418:s8e1",
            runtimeMs = runtimeMs,
            fingerprint = "release-a",
        ),
        showTmdbId = 1418,
        showImdbId = "tt0898266",
        seasonNumber = 8,
        episodeNumber = 1,
    )
}
