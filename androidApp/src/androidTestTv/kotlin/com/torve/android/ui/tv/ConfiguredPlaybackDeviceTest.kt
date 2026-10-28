package com.torve.android.ui.tv

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.TvMainActivity
import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamAggregator
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.detail.DetailViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue

/** Opt-in playback check on the spare device; never logs media URLs or credentials. */
class ConfiguredPlaybackDeviceTest {
    @Test fun configuredMoviesFindSourcesAndRenderVideo(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("torvePlaybackProbe") == "true")
        val scenario = ActivityScenario.launch(TvMainActivity::class.java)
        val graph = GlobalContext.get()
        val report = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "configured-playback-observations.txt")
        report.writeText("")
        fun record(message: String) { println("PLAYBACK_PROBE $message"); report.appendText(message + "\n") }
        var player: ExoPlayer? = null
        var detail: DetailViewModel? = null
        try {
            // Wait for the normal account bootstrap, rather than accidentally
            // exercising the initial, empty guest profile.
            withTimeout(30_000) {
                while (graph.get<AddonRepository>().getInstalledAddons().isEmpty()) delay(200)
            }
            val settings = graph.get<SettingsViewModel>()
            settings.awaitPlaybackSettings()
            record("playbackSettingsReady=true connected=${settings.getDebridAccounts().keys.joinToString()}")
            val requestedTitle = InstrumentationRegistry.getArguments().getString("torveTitleProbe")
            for ((imdb, title) in listOf("tt4034228" to "Manchester by the Sea", "tt3553976" to "Captain Fantastic")
                .filter { requestedTitle == null || it.first == requestedTitle }) {
                val started = android.os.SystemClock.elapsedRealtime()
                val viewModel = graph.get<DetailViewModel>()
                detail = viewModel
                scenario.onActivity {
                    viewModel.loadExtensionDetail(Json.encodeToString(com.torve.domain.model.MediaItem(
                        id = imdb, imdbId = imdb, type = MediaType.MOVIE, title = title)))
                    viewModel.fetchStreams(forceManualPick = true)
                }
                val source = withTimeout(30_000) {
                    var selected: ParsedStream? = null
                    while (selected == null) {
                        selected = viewModel.state.value.streams.filter { it.isCached &&
                            (it.directUrl != null || it.infoHash != null || it.magnetUrl != null) && !it.quality.equals("4K", true) }
                            .sortedWith(compareBy<ParsedStream> { if (it.directUrl != null) 0 else 1 }
                                .thenBy { if (it.codec.equals("H264", true)) 0 else 1 }
                                .thenBy { it.size?.let { size -> StreamAggregator.parseSizeToBytes(size) } ?: Long.MAX_VALUE })
                            .firstOrNull()
                        check(viewModel.state.value.streamsError == null) { "Source search failed: ${viewModel.state.value.streamsError}" }
                        if (selected == null) delay(100)
                    }
                    selected
                }
                record("title=$imdb firstReadyMs=${android.os.SystemClock.elapsedRealtime() - started} sourceCount=${viewModel.state.value.streams.size} direct=${source.directUrl != null}")
                val account = settings.getDebridAccounts().entries.firstOrNull()
                scenario.onActivity { viewModel.resolveStream(source, account?.key, account?.value.orEmpty()) }
                val resolved = withTimeout(45_000) {
                    while (viewModel.state.value.resolvedStream == null) {
                        check(viewModel.state.value.resolveError == null) { "Source resolution failed: ${viewModel.state.value.resolveError}" }
                        delay(100)
                    }
                    viewModel.state.value.resolvedStream!!
                }
                record("title=$imdb resolveMs=${android.os.SystemClock.elapsedRealtime() - started}")
                val resolvedUrl = io.ktor.http.Url(resolved.url)
                val selectedUrl = source.directUrl?.let { io.ktor.http.Url(it) }
                val knownFiles = setOf("error.mp4", "torbox_error.mp4", "torbox_unavailable.mp4", "unavailable.mp4",
                    "download_failed.mp4", "failed.mp4", "downloading.mp4", "store_limit_exceeded.mp4")
                record("title=$imdb selectedHost=${selectedUrl?.host} resolvedHost=${resolvedUrl.host} " +
                    "staticPath=${resolvedUrl.encodedPath.startsWith("/static/")} " +
                    "videosPath=${resolvedUrl.encodedPath.startsWith("/videos/")} " +
                    "knownMessageFile=${resolvedUrl.encodedPath.substringAfterLast('/').takeIf { it in knownFiles }}")
                val rendered = AtomicBoolean(false)
                val failure = AtomicReference<String?>(null)
                var position = 0L
                var duration = 0L
                scenario.onActivity { activity ->
                    val surface = SurfaceView(activity)
                    activity.addContentView(surface, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    player = ExoPlayer.Builder(activity).build().also {
                        it.setVideoSurfaceView(surface)
                        it.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { rendered.set(true) }
                            override fun onPlayerError(error: PlaybackException) {
                                val http = generateSequence(error.cause) { it.cause }
                                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                                failure.set(error.errorCodeName + (http?.let { ":HTTP${it.responseCode}" } ?: ""))
                            }
                        })
                        it.setMediaItem(MediaItem.fromUri(resolved.url))
                        it.prepare(); it.play()
                    }
                }
                withTimeoutOrNull(25_000) {
                    while (failure.get() == null && (!rendered.get() || position < 2_000)) {
                        delay(300)
                        scenario.onActivity { position = player?.currentPosition ?: 0; duration = player?.duration ?: 0 }
                    }
                }
                record("title=$imdb rendered=${rendered.get()} positionMs=$position durationMs=$duration errorCode=${failure.get()}")
                assertTrue("Configured source did not play: title=$imdb code=${failure.get()} rendered=${rendered.get()} positionMs=$position",
                    rendered.get() && position >= 2_000 && duration >= 3_600_000 && failure.get() == null)
                scenario.onActivity { player?.release(); player = null; viewModel.clear(); detail = null }
            }
        } finally { scenario.onActivity { player?.release(); detail?.clear() }; scenario.close() }
        Unit
    }
}
