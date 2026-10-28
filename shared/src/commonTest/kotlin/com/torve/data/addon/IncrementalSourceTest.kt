package com.torve.data.addon

import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class IncrementalSourceTest {
    private fun client(body: ByteReadChannel, contentType: String) = HttpClient(MockEngine.create {
        dispatcher = Dispatchers.Unconfined
        addHandler { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, contentType)) }
    })
    @Test fun firstSourceArrivesBeforeProviderFinishesAndUtf8ChunksRemainIntact() = runTest {
        val body = ByteChannel(autoFlush = true)
        val first = CompletableDeferred<Unit>()
        val http = client(body, "application/x-ndjson; charset=utf-8")
        val counts = mutableListOf<Int>()
        val writer = launch {
            body.writeStringUtf8("""{"stream":{"title":"Première S01E01","url":"https://media.test/1"}}""" + "\r\n")
            first.await() // A buffered-until-EOF implementation deadlocks here and fails the bounded timeout.
            body.writeStringUtf8("""{"streams":[{"title":"Second S01E02","url":"https://media.test/2"}]}""")
            body.close()
        }
        try {
            val result = withTimeout(5000) {
                StremioAddonClient(http, Json { ignoreUnknownKeys = true }).getStreams("https://fixture.test", MediaType.SERIES, "tt1", 1, 1) {
                    counts += it.size
                    if (it.size == 1) { assertEquals("Première S01E01", it.single().title); first.complete(Unit) }
                }
            }
            assertEquals(listOf(1, 2), counts)
            assertEquals(2, result.size)
        } finally { writer.cancel(); body.close(); http.close() }
    }
    @Test fun ordinaryJsonAddonsKeepWorking() = runTest {
        val http = client(ByteReadChannel("""{"streams":[{"url":"https://media.test/a","title":"Film"}]}"""), "application/json")
        try { assertEquals("Film", StremioAddonClient(http, Json).getStreams("https://fixture.test", MediaType.MOVIE, "tt1").single().title) }
        finally { http.close() }
    }
    @Test fun malformedOrOversizedIncrementalRecordsFailWithoutInventingSources() = runTest {
        for (payload in listOf("{broken}\n", "x".repeat(256 * 1024 + 1))) {
            val http = client(ByteReadChannel(payload), "application/x-ndjson")
            try { assertFails { StremioAddonClient(http, Json).getStreams("https://fixture.test", MediaType.MOVIE, "tt1") } }
            finally { http.close() }
        }
    }
    @Test fun cancellationDoesNotWaitForAProviderThatNeverFinishes() = runTest {
        val body = ByteChannel(autoFlush = true)
        val first = CompletableDeferred<Unit>()
        val http = client(body, "application/x-ndjson")
        val job = launch {
            StremioAddonClient(http, Json).getStreams("https://fixture.test", MediaType.MOVIE, "tt1") { first.complete(Unit) }
        }
        try {
            body.writeStringUtf8("""{"url":"https://media.test/1"}""" + "\n")
            withTimeout(5000) { first.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally { body.close(); job.cancel(); http.close() }
    }
    @Test fun sameTorrentFileDeduplicatesAcrossProviderUrlsButOtherFilesRemain() {
        val a = ParsedStream("A", "1080p", "Episode", infoHash = "ABC", fileIdx = 1, directUrl = "https://a.test/1")
        val cached = a.copy(addonName = "B", infoHash = "abc", directUrl = "https://b.test/1", isCached = true)
        val other = a.copy(fileIdx = 2)
        val result = deduplicateStreams(listOf(a, cached, other))
        assertEquals(2, result.size)
        assertEquals(cached, result.first())
        assertTrue(other in result)
    }
    @Test fun verifyingOneFileDoesNotMarkAnotherFileInTheSameTorrentCached() {
        val first = ParsedStream("A", "1080p", "Episode 1", infoHash = "abc", fileIdx = 1)
        val second = first.copy(title = "Episode 2", fileIdx = 2)
        val verified = first.copy(isCached = true)
        assertEquals(listOf(verified, second), mergeDebridPreflightResults(listOf(first, second), listOf(verified)))
    }
    @Test fun excludingPacksNeverExcludesNormalEpisodesAndAutoplayCannotBypassIt() {
        val episode = ParsedStream("A", "1080p", "My.Show.S01E02.1080p")
        val pack = episode.copy(title = "My.Show.S01.Complete.1080p")
        assertFalse(episode.isSeasonPack())
        assertTrue(pack.isSeasonPack())
        val labelled = StreamParser.parse(StremioStream(title = "1080p", url = "https://media.test/1",
            behaviorHints = StemioBehaviorHints(filename = "My.Show.S01.Complete.mkv")))
        assertTrue(labelled.isSeasonPack())
        assertFalse(StreamAutoSelectionPolicy.evaluate(pack, StreamPreferences(excludeSeasonPacks = true)).eligible)
        assertTrue(StreamAutoSelectionPolicy.evaluate(episode, StreamPreferences(excludeSeasonPacks = true)).eligible)
    }
}
