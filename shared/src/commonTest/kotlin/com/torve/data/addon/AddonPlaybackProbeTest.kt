package com.torve.data.addon

import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.repository.StreamReadiness
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AddonPlaybackProbeTest {
    @Test fun providerStatusVideoRedirectsCannotBecomeReadyPlaybackUrls() = runTest {
        for (url in listOf("https://torrentio.strem.fun/videos/torbox_error.mp4",
            "https://aiostreams.elfhosted.com/static/unavailable.mp4")) {
            var requests = 0
            val client = testClient {
                requests++
                respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, url))
            }
            try {
                assertEquals(StreamReadiness.Failed("error_addon_download_failed"), AddonPlaybackProbe(client).check(resolver))
                assertEquals(1, requests, "An error clip must never be fetched or probed as a movie")
            } finally { client.close() }
        }
    }

    @Test fun directProviderErrorClipIsRejectedWithoutRequestingItsBody() = runTest {
        val client = testClient { error("A provider status clip must not start playback") }
        try { assertEquals(StreamReadiness.Failed("error_addon_download_failed"),
            AddonPlaybackProbe(client).check("https://torrentio.strem.fun/videos/torbox_error.mp4")) }
        finally { client.close() }
    }
    // A deterministic engine dispatcher keeps the virtual timeout from outrunning
    // a mocked response on a real worker thread.
    private fun testClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine.create { dispatcher = Dispatchers.Unconfined; addHandler(handler) })
    private val resolver = "https://addon.example/playback/private-token/film.mkv"

    @Test fun readyRedirectIsReusedWithOnlyAHeaderCheckAndWithoutSubmittingTheResolverAgain() = runTest {
        var requests = 0
        var headerRequests = 0
        val media = "https://cdn.example/film.mkv?signature=fixture-only"
        val client = testClient { request ->
            if (request.method == HttpMethod.Head) {
                headerRequests++
                assertEquals(media, request.url.toString())
                assertNull(request.headers[HttpHeaders.Range])
                return@testClient respond("", HttpStatusCode.OK)
            }
            requests++
            assertEquals(resolver, request.url.toString())
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("bytes=0-0", request.headers[HttpHeaders.Range])
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, media))
        }
        try {
            assertEquals(StreamReadiness.Ready(media), AddonPlaybackProbe(client).check(resolver))
            assertEquals(1, requests)
            assertEquals(1, headerRequests)
        } finally { client.close() }
    }

    @Test fun elfHostedFailureSlatesAreErrorsAndTheirPrivateQueryIsNeverExposed() = runTest {
        for ((title, expected) in listOf("Download failed" to "error_addon_download_failed",
            "Too many requests" to "error_rate_limited",
            "Access denied" to "error_addon_access_denied", "Unknown provider message" to "error_addon_download_failed")) {
            var requests = 0
            val location = URLBuilder("https://slate.elfhosted.com/").apply {
                parameters.append("title", title); parameters.append("body", "secret-api-key-and-private-URL")
                parameters.append("sig", "private-signature")
            }.buildString()
            val client = testClient {
                requests++; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, location))
            }
            try {
                assertEquals(StreamReadiness.Failed(expected), AddonPlaybackProbe(client).check(resolver))
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test fun relativeStaticErrorRedirectsDoNotStartTheErrorVideoOrAnAutomaticDownloadRetry() = runTest {
        for ((file, expected) in listOf("download_failed.mp4" to "error_addon_download_failed",
            "429.mp4" to "error_rate_limited")) {
            val client = testClient {
                respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/static/$file"))
            }
            try { assertEquals(StreamReadiness.Failed(expected), AddonPlaybackProbe(client).check(resolver)) }
            finally { client.close() }
        }
    }

    @Test fun pendingAddonVideoKeepsOriginalResolverUntilItsExistingDownloadIsReady() = runTest {
        val pendingLocations = listOf("/static/downloading.mp4",
            "https://slate.elfhosted.com/?title=Downloading&body=private-token",
            "https://slate.elfhosted.com/?title=Download%20in%20progress&sig=private-signature")
        for (location in pendingLocations) {
            var requests = 0
            val media = "https://cdn.example/ready.mkv?signature=fixture-only"
            val client = testClient { request ->
                if (request.method == HttpMethod.Head) return@testClient respond("", HttpStatusCode.OK)
                assertEquals(resolver, request.url.toString())
                assertEquals("bytes=0-0", request.headers[HttpHeaders.Range])
                requests++
                respond("", HttpStatusCode.TemporaryRedirect,
                    headersOf(HttpHeaders.Location, if (requests == 1) location else media))
            }
            try {
                val probe = AddonPlaybackProbe(client)
                assertEquals(StreamReadiness.Preparing, probe.check(resolver))
                assertEquals(1, requests)
                assertEquals(StreamReadiness.Ready(media), probe.check(resolver))
                assertEquals(2, requests)
            } finally { client.close() }
        }
    }

    @Test fun opaqueCdnSampleIsRejectedUsingHeadersWithoutDownloadingItsBody() = runTest {
        val cdn = "https://cdn.example/opaque/abc123?signature=private"
        var resolverCalls = 0; var headerCalls = 0
        val client = testClient { request ->
            if (request.url.host == "addon.example") {
                resolverCalls++
                respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, cdn))
            } else {
                headerCalls++
                assertEquals(HttpMethod.Head, request.method)
                assertNull(request.headers[HttpHeaders.Authorization])
                respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentDisposition,
                    "attachment; filename=\"Movie.2026.1080p.sample.mkv\""))
            }
        }
        try {
            assertEquals(StreamReadiness.Failed(AddonPlaybackFilePolicy.SAMPLE_ONLY), AddonPlaybackProbe(client).check(resolver))
            assertEquals(1, resolverCalls); assertEquals(1, headerCalls)
        } finally { client.close() }
    }

    @Test fun explicitSamplePathsAndEncodedDispositionAreRecognizedWithoutRuntimeGuessing() {
        for (url in listOf("https://cdn.example/Film-Sample.mkv", "https://cdn.example/Sample/Film.mkv",
            "https://cdn.example/Film%20sample.mp4", "https://cdn.example/play?filename=film.sample.mkv")) {
            assertEquals(AddonPlaybackFilePolicy.SAMPLE_ONLY, AddonPlaybackFilePolicy.failure(url))
        }
        assertEquals(AddonPlaybackFilePolicy.SAMPLE_ONLY, AddonPlaybackFilePolicy.failure("https://cdn.example/opaque",
            "attachment; filename*=UTF-8''Film%20sample.mkv"))
        assertNull(AddonPlaybackFilePolicy.failure("https://cdn.example/The.Sample.2026.1080p.mkv"))
        assertNull(AddonPlaybackFilePolicy.failure("https://cdn.example/short-film.mp4"))
        assertNull(AddonPlaybackFilePolicy.failure("https://cdn.example/opaque?signature=sample"))
    }

    @Test fun unsupportedHeadDoesNotBlockAnOtherwisePlayableVideo() = runTest {
        val client = testClient { request ->
            if (request.method == HttpMethod.Head) respond("", HttpStatusCode.MethodNotAllowed)
            else respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://cdn.example/movie.mkv"))
        }
        try { assertEquals(StreamReadiness.Ready("https://cdn.example/movie.mkv"), AddonPlaybackProbe(client).check(resolver)) }
        finally { client.close() }
    }

    @Test fun directSampleResponseCannotReachThePlayer() = runTest {
        var calls = 0
        val client = testClient {
            calls++
            respond("x", HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentDisposition, "inline; filename=\"movie.sample.mkv\""))
        }
        try {
            assertEquals(StreamReadiness.Failed(AddonPlaybackFilePolicy.SAMPLE_ONLY), AddonPlaybackProbe(client).check(resolver))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun cancellingDuringTheHeaderCheckCannotStartThePlayer() = runTest {
        val client = testClient { request ->
            if (request.method == HttpMethod.Head) throw CancellationException("Closed while checking file")
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://cdn.example/opaque"))
        }
        try { assertFailsWith<CancellationException> { AddonPlaybackProbe(client).check(resolver) } }
        finally { client.close() }
    }

    @Test fun directVideoAndLegacyPreparingContractRemainSupported() = runTest {
        val client = testClient { request ->
            when (request.url.encodedPath) {
                "/ready" -> respond("x", HttpStatusCode.PartialContent)
                "/pending" -> respond("""{"error":"nzb_not_ready"}""", HttpStatusCode.GatewayTimeout)
                else -> respond("""{"error":"nzb_access_denied","detail":"secret"}""", HttpStatusCode.Forbidden)
            }
        }
        try {
            val probe = AddonPlaybackProbe(client)
            assertEquals(StreamReadiness.Ready("https://addon.example/ready"), probe.check("https://addon.example/ready"))
            assertEquals(StreamReadiness.Preparing, probe.check("https://addon.example/pending"))
            assertEquals(StreamReadiness.Failed(PandaPreparationFailure.message("nzb_access_denied")!!), probe.check("https://addon.example/denied"))
        } finally { client.close() }
    }

    @Test fun missingOrNonHttpRedirectCannotReachThePlayer() = runTest {
        for (location in listOf("", "file:///private/video.mkv", "javascript:bad")) {
            val client = testClient { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location)) }
            try { assertEquals(StreamReadiness.Failed("error_stream_resolve_failed"), AddonPlaybackProbe(client).check(resolver)) }
            finally { client.close() }
        }
    }

    @Test fun cancellationDoesNotTurnIntoAPreparationPoll() = runTest {
        val client = testClient { throw CancellationException("Closed") }
        try { assertFailsWith<CancellationException> { AddonPlaybackProbe(client).check(resolver) } }
        finally { client.close() }
    }

    @Test fun audioInspectionSkipsUncachedResolversButAllowsCachedSources() {
        val aio = ParsedStream("AIOStreams Newznab", "1080p", "Film", directUrl = resolver, addonBaseUrl = "https://addon.example")
        assertFalse(AddonTrackInspectionPolicy.canInspect(aio))
        assertTrue(AddonTrackInspectionPolicy.canInspect(aio.copy(addonName = "Torrentio", isCached = true)))
        assertTrue(AddonTrackInspectionPolicy.canInspect(aio.copy(isCached = true)))
        assertFalse(AddonTrackInspectionPolicy.canInspect(aio.copy(addonName = "Usenet", addonBaseUrl = null)))
        assertFalse(AddonTrackInspectionPolicy.canInspect(aio.copy(addonName = "Source", addonBaseUrl = null,
            accelerationProvenanceKind = CandidateProvenanceKind.USENET_NZBDAV)))
        assertTrue(AddonTrackInspectionPolicy.canInspect(ParsedStream("Direct library file", "1080p", "Film",
            directUrl = "https://cdn.example/film.mkv", isCached = true)))
    }

    @Test fun cachedLabelNeverMakesRawDownloadsInspectable() {
        for (address in listOf("https://indexer.example/file.nzb", "https://indexer.example/file.torrent",
            "https://indexer.example/getnzb/123", "https://indexer.example/api?t=get&id=123",
            "file:///private/video.mp4", "magnet:?xt=fixture")) {
            assertFalse(AddonTrackInspectionPolicy.canInspect(ParsedStream("Source", "1080p", "Film",
                directUrl = address, isCached = true)), address)
        }
    }
}
