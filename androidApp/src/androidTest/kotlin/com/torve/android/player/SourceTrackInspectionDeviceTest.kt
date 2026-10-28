package com.torve.android.player

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.data.addon.ParsedStream
import com.torve.data.addon.SourceTrackLanguagePolicy
import com.torve.data.auth.UserIdProvider
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.mp.KoinPlatform

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceTrackInspectionDeviceTest {
    private fun inspector(): AndroidSourceTrackInspector {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = File(context.cacheDir, "track-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = cache }
        return AndroidSourceTrackInspector(isolated, KoinPlatform.getKoin().get<UserIdProvider>())
    }
    private fun stream(url: String) = ParsedStream("Fixture", "1080p", "Title.German", directUrl = url, languages = listOf("DE"))

    @Test fun nativeReaderSeparatesAudioFromSubtitlesAndReusesVerifiedTracks() = runBlocking {
        FixtureServer().use { server ->
            val reader = inspector()
            val subtitleOnly = reader.inspect(stream(server.url("english-german-subtitles.mkv")))
            assertTrue("MKV tracks were not inspected", subtitleOnly.trackLanguagesVerified)
            assertEquals(listOf("EN"), subtitleOnly.languages)
            assertEquals(listOf("DE"), subtitleOnly.subtitleLanguages)
            assertFalse(SourceTrackLanguagePolicy.matches(subtitleOnly, "DE", false, true))
            assertTrue(SourceTrackLanguagePolicy.matches(subtitleOnly, "DE", true, true))
            val requests = server.requests.get()
            assertEquals(subtitleOnly, reader.inspect(stream(server.url("english-german-subtitles.mkv"))))
            assertEquals("Cache should avoid another network request", requests, server.requests.get())
            val multi = reader.inspect(stream(server.url("multi-audio.mp4")))
            assertTrue("MP4 tracks were not inspected", multi.trackLanguagesVerified)
            assertEquals(setOf("EN", "DE"), multi.languages.toSet())
            assertTrue(SourceTrackLanguagePolicy.matches(multi, "DE", false, true))
        }
    }

    @Test fun inspectingLazyOrRawNzbLinksMakesNoNetworkRequest() = runBlocking {
        FixtureServer().use { server ->
            val reader = inspector()
            for (path in listOf("u/token/nzb/signed", "api?t=get&id=release", "getnzb/release", "source.nzb")) {
                val item = stream(server.url(path))
                assertFalse(reader.canInspect(item))
                assertFalse(reader.inspect(item).trackLanguagesVerified)
            }
            assertEquals(0, server.requests.get())
        }
    }

    @Test fun networkReaderCapsBytesAndDoesNotSkipAnIgnoredRange() {
        FixtureServer().use { server ->
            val budget = InspectionReadBudget(1024)
            val url = server.url("multi-audio.mp4")
            val source = InspectionHttpDataSource(URI(url), emptyMap(), false, budget)
            try {
                source.open(DataSpec(Uri.parse(url)))
                val buffer = ByteArray(4096)
                var read = 0
                try { while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; read += count } }
                catch (_: IOException) { }
                assertEquals(1024, read)
            } finally { source.close(); budget.cancel() }
            val ignored = server.url("ignored-range")
            val second = InspectionHttpDataSource(URI(ignored), emptyMap(), false, InspectionReadBudget(1024))
            try {
                second.open(DataSpec.Builder().setUri(ignored).setPosition(1_000_000).build())
                fail("An ignored range must not scan the file to reach the offset")
            } catch (_: IOException) { } finally { second.close() }
        }
    }

    private class FixtureServer : Closeable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "track-fixture-server") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                client.use {
                    runCatching {
                        val input = it.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1].substringBefore('?').substringAfterLast('/')
                        val headers = mutableMapOf<String, String>()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                        requests.incrementAndGet()
                        val filename = if (path == "ignored-range") "multi-audio.mp4" else path
                        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/tracks/$filename").use { file -> file.readBytes() }
                        val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
                        val partial = range != null && path != "ignored-range"
                        val start = if (partial) range!!.groupValues[1].toInt() else 0
                        val end = if (partial) range!!.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex else bytes.lastIndex
                        val output = it.getOutputStream()
                        val response = buildString {
                            append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                            append("Content-Type: ${if (filename.endsWith(".mp4")) "video/mp4" else "video/x-matroska"}\r\n")
                            if (partial) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                            append("Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
                        }
                        output.write(response.toByteArray()); output.write(bytes, start, end - start + 1); output.flush()
                    }
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$path"
        override fun close() { socket.close(); worker.join(1000) }
    }
}
