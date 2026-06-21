package com.torve.desktop.playback

import com.torve.desktop.di.desktopAppModule
import com.torve.desktop.player.VlcDesktopPlaybackEngine
import com.torve.desktop.player.VlcSurfaceCallbacks
import com.torve.di.sharedModule
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.player.TraktScrobbler
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.measureTimeMillis

class DesktopPlaybackStartupBenchmarkTest {

    @Test
    fun manual_startup_benchmark() = runBlocking {
        val benchmarkEnabled = System.getProperty("torve.desktop.runStartupBenchmark")?.toBooleanStrictOrNull()
            ?: System.getenv("TORVE_DESKTOP_RUN_STARTUP_BENCHMARK")?.toBooleanStrictOrNull()
            ?: false
        if (!benchmarkEnabled) {
            return@runBlocking
        }

        val app = koinApplication {
            modules(sharedModule, desktopAppModule)
        }

        try {
            val metadataRepository = app.koin.get<MetadataRepository>()
            val streamRepository = app.koin.get<StreamRepository>()
            val addonRepository = app.koin.get<AddonRepository>()
            val subtitleAggregator = app.koin.get<com.torve.data.addon.SubtitleAggregator>()
            val watchProgressRepository = app.koin.get<WatchProgressRepository>()
            val watchHistoryRepository = app.koin.get<WatchHistoryRepository>()
            val traktScrobbler = app.koin.get<TraktScrobbler>()
            val settingsViewModel = app.koin.get<SettingsViewModel>()

            val mediaFiles = discoverLocalMediaFiles()
            if (mediaFiles.isNotEmpty()) {
                runLocalEngineBenchmark(mediaFiles)
            } else {
                println("TORVE BENCH | local-engine | skipped | no local media file found")
            }

            runSourceResolutionBenchmark(
                metadataRepository = metadataRepository,
                streamRepository = streamRepository,
                addonRepository = addonRepository,
                subtitleAggregator = subtitleAggregator,
                settingsViewModel = settingsViewModel,
            )

            runRealPlaybackBenchmark(
                metadataRepository = metadataRepository,
                streamRepository = streamRepository,
                addonRepository = addonRepository,
                subtitleAggregator = subtitleAggregator,
                watchProgressRepository = watchProgressRepository,
                watchHistoryRepository = watchHistoryRepository,
                traktScrobbler = traktScrobbler,
                settingsViewModel = settingsViewModel,
            )
        } finally {
            runCatching { stopKoin() }
        }
    }

    private suspend fun runLocalEngineBenchmark(mediaFiles: List<Path>) {
        val firstMedia = mediaFiles.first()
        val secondMedia = mediaFiles.getOrElse(1) { firstMedia }
        val engine = VlcDesktopPlaybackEngine()
        var attachment: BenchmarkSurfaceAttachment? = null

        try {
            DesktopPlaybackStartupTelemetry.clear()
            val coldTrace = DesktopPlaybackStartupTelemetry.begin("local-engine-cold")
            coldTrace.mark("play action received", firstMedia.fileName.toString())
            engine.probeRuntime(coldTrace)
            attachment = attachEngineToFrame(engine)
            val coldSession = localSession(firstMedia)
            engine.open(coldSession, autoPlay = true, startupTrace = coldTrace)
            val coldResult = awaitTrace("local-engine-cold")
            println("TORVE BENCH | local-engine-cold | totalMs=${coldResult?.stageTime("first frame shown")}")

            engine.stop()
            delay(400)

            DesktopPlaybackStartupTelemetry.clear()
            val warmTrace = DesktopPlaybackStartupTelemetry.begin("local-engine-warm")
            warmTrace.mark("play action received", firstMedia.fileName.toString())
            engine.open(coldSession, autoPlay = true, startupTrace = warmTrace)
            val warmResult = awaitTrace("local-engine-warm")
            println("TORVE BENCH | local-engine-warm | totalMs=${warmResult?.stageTime("first frame shown")}")

            engine.stop()
            delay(400)

            DesktopPlaybackStartupTelemetry.clear()
            val restartTrace = DesktopPlaybackStartupTelemetry.begin("local-engine-restart")
            restartTrace.mark("play action received", firstMedia.fileName.toString())
            engine.open(coldSession, autoPlay = true, startupTrace = restartTrace)
            val restartResult = awaitTrace("local-engine-restart")
            println("TORVE BENCH | local-engine-restart | totalMs=${restartResult?.stageTime("first frame shown")}")

            engine.stop()
            delay(400)

            DesktopPlaybackStartupTelemetry.clear()
            val switchTrace = DesktopPlaybackStartupTelemetry.begin("local-engine-switch")
            switchTrace.mark("play action received", secondMedia.fileName.toString())
            engine.open(localSession(secondMedia), autoPlay = true, startupTrace = switchTrace)
            val switchResult = awaitTrace("local-engine-switch")
            println("TORVE BENCH | local-engine-switch | totalMs=${switchResult?.stageTime("first frame shown")}")
        } finally {
            runCatching { attachment?.dispose() }
            engine.dispose()
        }
    }

    private suspend fun runSourceResolutionBenchmark(
        metadataRepository: MetadataRepository,
        streamRepository: StreamRepository,
        addonRepository: AddonRepository,
        subtitleAggregator: com.torve.data.addon.SubtitleAggregator,
        settingsViewModel: SettingsViewModel,
    ) {
        val addons = addonRepository.getInstalledAddons().filter { it.isEnabled }
        if (addons.isEmpty()) {
            println("TORVE BENCH | source-resolution | skipped | no enabled addons")
            return
        }

        val candidates = listOf(
            Triple("tt1375666", "Inception", MediaType.MOVIE),
            Triple("tt0816692", "Interstellar", MediaType.MOVIE),
            Triple("tt0903747", "Breaking Bad", MediaType.SERIES),
        )

        val selected = candidates.firstOrNull { (imdbId, _, type) ->
            runCatching {
                streamRepository.fetchStreams(
                    type = type,
                    imdbId = imdbId,
                    season = if (type == MediaType.SERIES) 1 else null,
                    episode = if (type == MediaType.SERIES) 1 else null,
                    addons = addons,
                    debridAccounts = settingsViewModel.getDebridAccounts(),
                    preferences = settingsViewModel.buildStreamPreferences(),
                    fetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
                )
            }.getOrDefault(emptyList()).isNotEmpty()
        }

        if (selected == null) {
            println("TORVE BENCH | source-resolution | skipped | no benchmark title resolved")
            return
        }

        val (imdbId, title, mediaType) = selected
        val season = if (mediaType == MediaType.SERIES) 1 else null
        val episode = if (mediaType == MediaType.SERIES) 1 else null

        val legacyMetadataMs = measureTimeMillis {
            if (mediaType == MediaType.MOVIE) {
                metadataRepository.findByImdbId(imdbId, "movie")
            } else {
                metadataRepository.findByImdbId(imdbId, "tv")
            }
        }
        val legacySubtitleMs = measureTimeMillis {
            subtitleAggregator.fetchSubtitles(
                addons = addons,
                type = mediaType,
                imdbId = imdbId,
                season = season,
                episode = episode,
            )
        }
        val legacyStreamMs = measureTimeMillis {
            streamRepository.fetchStreams(
                type = mediaType,
                imdbId = imdbId,
                season = season,
                episode = episode,
                addons = addons,
                debridAccounts = settingsViewModel.getDebridAccounts(),
                preferences = settingsViewModel.buildStreamPreferences(),
                fetchPolicy = StreamFetchPolicy.FULL,
            )
        }
        val startupStreamMs = measureTimeMillis {
            streamRepository.fetchStreams(
                type = mediaType,
                imdbId = imdbId,
                season = season,
                episode = episode,
                addons = addons,
                debridAccounts = settingsViewModel.getDebridAccounts(),
                preferences = settingsViewModel.buildStreamPreferences(),
                fetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
            )
        }
        val backgroundSubtitleMs = measureTimeMillis {
            subtitleAggregator.fetchSubtitles(
                addons = addons,
                type = mediaType,
                imdbId = imdbId,
                season = season,
                episode = episode,
                addonTimeoutMs = 2_500,
            )
        }

        println(
            "TORVE BENCH | source-resolution | title=$title | legacyMetadataMs=$legacyMetadataMs | legacySubtitleMs=$legacySubtitleMs | legacyStreamMs=$legacyStreamMs | startupStreamMs=$startupStreamMs | backgroundSubtitleMs=$backgroundSubtitleMs",
        )
    }

    private suspend fun runRealPlaybackBenchmark(
        metadataRepository: MetadataRepository,
        streamRepository: StreamRepository,
        addonRepository: AddonRepository,
        subtitleAggregator: com.torve.data.addon.SubtitleAggregator,
        watchProgressRepository: WatchProgressRepository,
        watchHistoryRepository: WatchHistoryRepository,
        traktScrobbler: TraktScrobbler,
        settingsViewModel: SettingsViewModel,
    ) {
        if (settingsViewModel.getDebridApiKey().isBlank()) {
            println("TORVE BENCH | controller-playback | skipped | no debrid account configured")
            return
        }

        val request = DesktopPlaybackRequest(
            mediaId = "bench-inception",
            mediaType = MediaType.MOVIE,
            title = "Inception",
            imdbId = "tt1375666",
            sourceSurface = "Benchmark",
        )
        val engine = VlcDesktopPlaybackEngine()
        val controller = DesktopPlayerController(
            metadataRepository = metadataRepository,
            streamRepository = streamRepository,
            addonRepository = addonRepository,
            subtitleAggregator = subtitleAggregator,
            watchProgressRepository = watchProgressRepository,
            watchHistoryRepository = watchHistoryRepository,
            traktScrobbler = traktScrobbler,
            settingsViewModel = settingsViewModel,
            playbackEngine = engine,
        )
        var attachment: BenchmarkSurfaceAttachment? = null

        try {
            controller.probeRuntime()
            delay(800)
            attachment = attachEngineToFrame(engine)
            DesktopPlaybackStartupTelemetry.clear()
            controller.open(request)
            controller.play()
            val firstTrace = awaitTrace(request.startupTraceLabel())
            println("TORVE BENCH | controller-first-play | totalMs=${firstTrace?.stageTime("first frame shown")}")

            controller.stop()
            delay(500)

            DesktopPlaybackStartupTelemetry.clear()
            controller.open(request)
            controller.play()
            val repeatTrace = awaitTrace(request.startupTraceLabel())
            println("TORVE BENCH | controller-repeat-play | totalMs=${repeatTrace?.stageTime("first frame shown")}")
        } catch (t: Throwable) {
            println("TORVE BENCH | controller-playback | failed | ${t.message}")
        } finally {
            runCatching { attachment?.dispose() }
            controller.dispose()
        }
    }

    private suspend fun awaitTrace(labelContains: String): DesktopPlaybackStartupTraceSnapshot? {
        repeat(300) {
            val snapshot = DesktopPlaybackStartupTelemetry.latestCompleted(labelContains)
            if (snapshot != null) return snapshot
            delay(200)
        }
        return null
    }

    private fun attachEngineToFrame(engine: VlcDesktopPlaybackEngine): BenchmarkSurfaceAttachment {
        val frame = JFrame("Torve Startup Benchmark")
        val chromeHost = engine.obtainChromeHost()
        val surfaceHost = engine.obtainSurfaceHost(
            callbacks = VlcSurfaceCallbacks(),
            chromeHost = chromeHost,
        )
        SwingUtilities.invokeAndWait {
            frame.layout = BorderLayout()
            frame.setSize(960, 540)
            frame.setLocationRelativeTo(null)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.contentPane.add(surfaceHost, BorderLayout.CENTER)
            frame.isVisible = true
        }
        return BenchmarkSurfaceAttachment(
            frame = frame,
            surfaceHost = surfaceHost,
            chromeHost = chromeHost,
        )
    }

    private fun localSession(mediaPath: Path): DesktopPlaybackSession {
        val item = MediaItem(
            id = mediaPath.fileName.toString(),
            title = mediaPath.fileName.toString(),
            type = MediaType.MOVIE,
        )
        val request = DesktopPlaybackRequest(
            mediaId = item.id,
            mediaType = item.type,
            title = item.title,
            sourceSurface = "Local Benchmark",
        )
        return DesktopPlaybackSession(
            request = request,
            mediaItem = item,
            resolvedUrl = mediaPath.toUri().toString(),
        )
    }

    private fun discoverLocalMediaFiles(): List<Path> {
        val candidates = buildList {
            System.getProperty("torve.desktop.benchmark.media")?.let { add(Paths.get(it)) }
            System.getenv("TORVE_DESKTOP_BENCHMARK_MEDIA")?.let { add(Paths.get(it)) }
            val userHome = Paths.get(System.getProperty("user.home"))
            add(userHome.resolve("Videos"))
            add(userHome.resolve("Downloads"))
        }

        val results = mutableListOf<Path>()
        candidates.forEach { base ->
            if (results.size >= 2 || !Files.exists(base)) return@forEach
            runCatching {
                Files.walk(base, 3).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter {
                            val name = it.fileName.toString().lowercase()
                            name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov")
                        }
                        .limit((2 - results.size).toLong())
                        .forEach(results::add)
                }
            }
        }
        return results
    }
}

private data class BenchmarkSurfaceAttachment(
    val frame: JFrame,
    val surfaceHost: com.torve.desktop.player.VlcVideoSurfaceHost,
    val chromeHost: com.torve.desktop.player.VlcPlayerChromeHost,
) {
    fun dispose() {
        SwingUtilities.invokeAndWait {
            surfaceHost.dispose()
            chromeHost.dispose()
            frame.dispose()
        }
    }
}

private fun DesktopPlaybackRequest.startupTraceLabel(): String = buildString {
    append(mediaType.name.lowercase())
    append(':')
    append(imdbId ?: mediaId)
    seasonNumber?.let { append(":s$it") }
    episodeNumber?.let { append(":e$it") }
}
