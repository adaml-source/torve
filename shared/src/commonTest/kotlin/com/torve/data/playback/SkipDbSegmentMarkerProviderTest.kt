package com.torve.data.playback

import com.torve.domain.player.MediaIdentity
import com.torve.domain.player.PlaybackSegmentRequest
import com.torve.domain.player.ProviderTimelineMatch
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

class SkipDbSegmentMarkerProviderTest {
    @Test
    fun requestsConservativeDurationMatchingAndMapsEverySupportedType() = runTest {
        var requestPath = ""
        var requestQuery = ""
        val provider = provider(
            """
            {
              "imdb_id":"tt0898266",
              "season":5,
              "episode":7,
              "segments":{
                "intro":{"start_ms":173423,"end_ms":195071,"match":"exact","adjusted":false,"offset_ms":0,"confidence":0.93},
                "recap":{"start_ms":0,"end_ms":25000,"match":"exact","adjusted":false,"offset_ms":0,"confidence":0.88},
                "outro":{"start_ms":1162000,"end_ms":1320123,"match":"exact","adjusted":false,"offset_ms":0,"confidence":0.95},
                "preview":{"start_ms":1250000,"end_ms":1290000,"match":"exact","adjusted":false,"offset_ms":0,"confidence":0.90}
              }
            }
            """.trimIndent(),
            capture = { path, query -> requestPath = path; requestQuery = query },
        )

        val markers = provider.getSegments(request())

        assertEquals("/api/segments", requestPath)
        assertTrue("imdb_id=tt0898266" in requestQuery)
        assertTrue("season=5" in requestQuery)
        assertTrue("episode=7" in requestQuery)
        assertTrue("duration=1320.123" in requestQuery)
        assertTrue("adjust=conservative" in requestQuery)
        assertEquals(
            listOf(
                SegmentType.INTRO,
                SegmentType.RECAP,
                SegmentType.CREDITS,
                SegmentType.NEXT_EPISODE_PREVIEW,
            ),
            markers.map { it.type },
        )
        assertTrue(markers.all { it.timelineMatch == ProviderTimelineMatch.EXACT_RUNTIME })
        assertTrue(markers.single { it.type == SegmentType.CREDITS }.endsAtMediaEnd)
    }

    @Test
    fun preservesProviderShiftWithoutApplyingItAgain() = runTest {
        val provider = provider(
            """
            {
              "imdb_id":"tt0898266","season":5,"episode":7,
              "segments":{"intro":{
                "start_ms":168423,"end_ms":190071,"match":"shifted",
                "adjusted":true,"offset_ms":-5000,"confidence":0.84
              }}
            }
            """.trimIndent(),
        )

        val marker = provider.getSegments(request()).single()

        assertEquals(168_423L, marker.startMs)
        assertEquals(190_071L, marker.endMs)
        assertEquals(RUNTIME, marker.referenceRuntimeMs)
        assertEquals(ProviderTimelineMatch.CONSERVATIVE_RUNTIME_MATCH, marker.timelineMatch)
    }

    @Test
    fun acceptsConservativeUnadjustedNearbyMatchAndRetainsOutOfRangeAsWeakEvidence() = runTest {
        val provider = provider(
            """
            {
              "imdb_id":"tt0898266","season":5,"episode":7,
              "segments":{
                "intro":{"start_ms":173500,"end_ms":194500,"match":"shifted","adjusted":false,"offset_ms":8000,"confidence":0.82},
                "outro":{"start_ms":1163000,"end_ms":1188000,"match":"out-of-range","adjusted":false,"offset_ms":132123,"confidence":0.60}
              }
            }
            """.trimIndent(),
        )

        val markers = provider.getSegments(request())

        assertEquals(ProviderTimelineMatch.CONSERVATIVE_RUNTIME_MATCH, markers[0].timelineMatch)
        assertEquals(ProviderTimelineMatch.OUT_OF_RANGE, markers[1].timelineMatch)
        assertFalse(markers[1].referenceRuntimeReliable)
    }

    @Test
    fun confirmedAbsenceIsNotInventedAsAZeroLengthSegment() = runTest {
        val provider = provider(
            """
            {
              "imdb_id":"tt0898266","season":5,"episode":7,
              "segments":{"recap":{
                "start_ms":0,"end_ms":0,"match":"exact",
                "adjusted":false,"offset_ms":0,"confidence":1.0
              }}
            }
            """.trimIndent(),
        )

        assertTrue(provider.getSegments(request()).isEmpty())
    }

    @Test
    fun rejectsIdentityMismatchMalformedConfidenceAndInvalidMatchMetadata() = runTest {
        val wrongIdentity = provider(
            """{"imdb_id":"tt0000001","season":5,"episode":7,"segments":{}}""",
        )
        val malformed = provider(
            """
            {
              "imdb_id":"tt0898266","season":5,"episode":7,
              "segments":{
                "intro":{"start_ms":1,"end_ms":20000,"match":"exact","adjusted":true,"offset_ms":1,"confidence":2.0},
                "outro":{"start_ms":1200000,"end_ms":1400000,"match":"mystery","adjusted":false,"offset_ms":0,"confidence":0.9}
              }
            }
            """.trimIndent(),
        )

        assertTrue(wrongIdentity.getSegments(request()).isEmpty())
        assertTrue(malformed.getSegments(request()).isEmpty())
    }

    @Test
    fun invalidLookupDoesNotTouchNetworkAndOversizedPayloadIsRejected() = runTest {
        var called = false
        val invalidProvider = SkipDbSegmentMarkerProvider(
            HttpClient(MockEngine {
                called = true
                respond("{}", HttpStatusCode.OK)
            }),
            baseUrl = "https://example.test",
        )
        val oversizedProvider = SkipDbSegmentMarkerProvider(
            HttpClient(MockEngine {
                respond(
                    content = "x".repeat(70_000),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, "70000"),
                )
            }),
            baseUrl = "https://example.test",
        )

        assertTrue(invalidProvider.getSegments(request().copy(showImdbId = "bad")).isEmpty())
        assertFalse(called)
        assertTrue(oversizedProvider.getSegments(request()).isEmpty())
    }

    private fun provider(
        body: String,
        capture: (String, String) -> Unit = { _, _ -> },
    ): SkipDbSegmentMarkerProvider {
        val client = HttpClient(MockEngine { request ->
            capture(request.url.encodedPath, request.url.encodedQuery)
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        return SkipDbSegmentMarkerProvider(client, baseUrl = "https://example.test")
    }

    private fun request() = PlaybackSegmentRequest(
        media = MediaIdentity(
            canonicalEpisodeId = "1418:s5e7",
            runtimeMs = RUNTIME,
            fingerprint = "release-a",
        ),
        showImdbId = "tt0898266",
        seasonNumber = 5,
        episodeNumber = 7,
    )

    private companion object {
        const val RUNTIME = 1_320_123L
    }
}
