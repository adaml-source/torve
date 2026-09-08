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

class TheIntroDbSegmentMarkerProviderTest {
    @Test
    fun mapsMultipleSegmentsAndEofAnchoredCredits() = runTest {
        var requestPath = ""
        var requestQuery = ""
        val provider = provider(
            """
            {
              "tmdb_id":1418,
              "type":"tv",
              "season":5,
              "episode":7,
              "intro":[{"start_ms":173423,"end_ms":195071}],
              "recap":[{"start_ms":0,"end_ms":25000},{"start_ms":30000,"end_ms":42000}],
              "credits":[{"start_ms":1162000,"end_ms":null}],
              "preview":[{"start_ms":1280000,"end_ms":null}]
            }
            """.trimIndent(),
            capture = { path, query -> requestPath = path; requestQuery = query },
        )

        val markers = provider.getSegments(request())

        assertEquals("/v3/media", requestPath)
        assertTrue("tmdb_id=1418" in requestQuery)
        assertTrue("type=tv" in requestQuery)
        assertEquals(
            listOf(
                SegmentType.INTRO,
                SegmentType.RECAP,
                SegmentType.RECAP,
                SegmentType.CREDITS,
                SegmentType.NEXT_EPISODE_PREVIEW,
            ),
            markers.map { it.type },
        )
        assertTrue(markers.filter { it.type == SegmentType.CREDITS }.single().endsAtMediaEnd)
        assertEquals(RUNTIME, markers.filter { it.type == SegmentType.CREDITS }.single().endMs)
        assertFalse(markers.single { it.type == SegmentType.INTRO }.referenceRuntimeReliable)
    }

    @Test
    fun reliableResponseRuntimeIsPreservedForSourceReconciliation() = runTest {
        val provider = provider(
            """
            {
              "tmdb_id":1418,
              "type":"tv",
              "season":5,
              "episode":7,
              "video_duration_ms":1300000,
              "credits":[{"start_ms":1162000,"end_ms":null}]
            }
            """.trimIndent(),
        )

        val marker = provider.getSegments(request()).single()

        assertTrue(marker.referenceRuntimeReliable)
        assertEquals(1_300_000L, marker.referenceRuntimeMs)
        assertEquals(1_300_000L, marker.endMs)
    }

    @Test
    fun identityMismatchAndMalformedSegmentsAreRejected() = runTest {
        val provider = provider(
            """
            {
              "tmdb_id":999,
              "type":"tv",
              "season":5,
              "episode":7,
              "credits":[{"start_ms":-1,"end_ms":99999999}]
            }
            """.trimIndent(),
        )

        assertTrue(provider.getSegments(request()).isEmpty())
    }

    @Test
    fun invalidLookupDoesNotTouchNetwork() = runTest {
        var called = false
        val provider = TheIntroDbSegmentMarkerProvider(
            HttpClient(MockEngine {
                called = true
                respond("{}", HttpStatusCode.OK)
            }),
            baseUrl = "https://example.test/v3",
        )

        assertTrue(provider.getSegments(request().copy(showTmdbId = null, showImdbId = "bad")).isEmpty())
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
        val provider = TheIntroDbSegmentMarkerProvider(client, baseUrl = "https://example.test/v3")

        assertTrue(provider.getSegments(request()).isEmpty())
    }

    private fun provider(
        body: String,
        capture: (String, String) -> Unit = { _, _ -> },
    ): TheIntroDbSegmentMarkerProvider {
        val client = HttpClient(MockEngine { request ->
            capture(request.url.encodedPath, request.url.encodedQuery)
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        return TheIntroDbSegmentMarkerProvider(client, baseUrl = "https://example.test/v3")
    }

    private fun request() = PlaybackSegmentRequest(
        media = MediaIdentity(
            canonicalEpisodeId = "1418:s5e7",
            runtimeMs = RUNTIME,
            fingerprint = "release-a",
        ),
        showTmdbId = 1418,
        showImdbId = "tt0898266",
        seasonNumber = 5,
        episodeNumber = 7,
    )

    private companion object {
        const val RUNTIME = 22L * 60_000L
    }
}
