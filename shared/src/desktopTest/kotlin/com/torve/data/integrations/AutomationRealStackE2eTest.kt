package com.torve.data.integrations

import com.torve.data.network.HttpClientFactory
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationReleaseQuery
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.AutomationSubtitleTarget
import com.torve.domain.integrations.TdarrScanRequest
import com.torve.domain.integrations.TdarrJobAction
import com.torve.domain.integrations.TdarrJobActionRequest
import com.torve.domain.integrations.TdarrWorkerLimitChange
import com.torve.domain.integrations.TdarrWorkerLimitRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Opt-in test for tools/arr-e2e. It deliberately uses the production client
 * against real services; the default test suite remains offline and fast.
 */
class AutomationRealStackE2eTest {
    @Test
    fun `generated release is acquired imported and handed to tdarr`() = runBlocking {
        val repositoryRoot = repositoryRoot()
        val localFlag = repositoryRoot.resolve("tools/arr-e2e/state/run-live-test.flag")
        if (System.getenv("TORVE_ARR_E2E") != "true" && !Files.exists(localFlag)) return@runBlocking
        val radarrKey = System.getenv("TORVE_E2E_RADARR_KEY")?.takeIf { it.isNotBlank() }
            ?: readApiKey(repositoryRoot.resolve("tools/arr-e2e/state/radarr/config.xml"))
        val prowlarrKey = System.getenv("TORVE_E2E_PROWLARR_KEY")?.takeIf { it.isNotBlank() }
            ?: readApiKey(repositoryRoot.resolve("tools/arr-e2e/state/prowlarr/config.xml"))
        val bazarrKey = System.getenv("TORVE_E2E_BAZARR_KEY")?.takeIf { it.isNotBlank() }
            ?: readBazarrApiKey(repositoryRoot.resolve("tools/arr-e2e/state/bazarr/config/config.yaml"))
        val client = ServarrAdminClient(HttpClientFactory.create())
        val radarr = instance(AutomationServiceType.RADARR, "http://127.0.0.1:17878")
        val prowlarr = instance(AutomationServiceType.PROWLARR, "http://127.0.0.1:19696")
        val bazarr = instance(AutomationServiceType.BAZARR, "http://127.0.0.1:16767")
        val tdarr = instance(AutomationServiceType.TDARR, "http://127.0.0.1:18266")

        assertIs<AutomationConnectionResult.Connected>(client.testConnection(radarr, radarrKey))
        assertIs<AutomationConnectionResult.Connected>(client.testConnection(prowlarr, prowlarrKey))
        assertIs<AutomationConnectionResult.Connected>(client.testConnection(bazarr, bazarrKey))
        assertIs<AutomationConnectionResult.Connected>(client.testConnection(tdarr, ""))

        val indexers = assertIs<AutomationAdminResult.Success<*>>(client.indexers(prowlarr, prowlarrKey)).value
            as List<com.torve.domain.integrations.AutomationIndexer>
        val fixtureIndexer = indexers.first { it.name == "Torve Legal Fixture" }
        assertIs<AutomationAdminResult.Success<*>>(client.testIndexer(prowlarr, prowlarrKey, fixtureIndexer.id))

        val library = assertIs<AutomationAdminResult.Success<*>>(client.listLibrary(radarr, radarrKey)).value
            as List<com.torve.domain.integrations.AutomationLibraryItem>
        val movie = library.first { it.externalId == 10378 }
        if (!movie.hasFile) {
            val releases = assertIs<AutomationAdminResult.Success<*>>(
                client.interactiveSearch(radarr, radarrKey, AutomationReleaseQuery(movie.id)),
            ).value as List<com.torve.domain.integrations.AutomationRelease>
            val generated = releases.first { it.title.endsWith("TORVE-E2E") }
            assertIs<AutomationAdminResult.Success<*>>(client.grabRelease(radarr, radarrKey, generated))
        }

        val imported = waitUntil(180_000) {
            val current = client.listLibrary(radarr, radarrKey)
            (current as? AutomationAdminResult.Success)?.value
                ?.firstOrNull { it.externalId == 10378 }?.hasFile == true
        }
        assertTrue(imported, "Radarr did not import the generated qBittorrent download")

        var subtitleTarget: AutomationSubtitleTarget? = null
        val bazarrReady = waitUntil(60_000) {
            val wanted = client.wantedSubtitles(bazarr, bazarrKey)
            subtitleTarget = (wanted as? AutomationAdminResult.Success)?.value
                ?.firstOrNull { it.title.contains("Big Buck Bunny", ignoreCase = true) }
            subtitleTarget != null
        }
        assertTrue(bazarrReady, "Bazarr did not expose the imported movie as wanted")
        assertIs<AutomationAdminResult.Success<*>>(
            client.searchSubtitles(bazarr, bazarrKey, requireNotNull(subtitleTarget)),
        )

        val overview = assertIs<AutomationAdminResult.Success<*>>(client.tdarrOverview(tdarr, "")).value
            as com.torve.domain.integrations.TdarrOverview
        val libraryFixture = overview.libraries.first { it.id == "torve-e2e-library" }
        val node = overview.nodes.first { it.online }
        assertIs<AutomationAdminResult.Success<*>>(
            client.changeTdarrWorkerLimit(
                tdarr,
                "",
                TdarrWorkerLimitRequest(node.id, "transcodecpu", TdarrWorkerLimitChange.INCREASE),
            ),
        )
        assertIs<AutomationAdminResult.Success<*>>(
            client.scanTdarrLibrary(tdarr, "", TdarrScanRequest(libraryFixture.id)),
        )

        val outputDirectory = repositoryRoot.resolve("tools/arr-e2e/state/media/transcoded")
        if (!hasMediaFile(outputDirectory)) {
            val accepted = waitUntil(300_000) {
                val current = client.tdarrOverview(tdarr, "")
                val job = (current as? AutomationAdminResult.Success)?.value?.jobs
                    ?.firstOrNull { it.status == "transcodeSuccess" || it.status == "conditionsMet" }
                    ?: return@waitUntil false
                client.actOnTdarrJob(
                    tdarr,
                    "",
                    TdarrJobActionRequest(job.id, TdarrJobAction.ACCEPT),
                ) is AutomationAdminResult.Success
            }
            assertTrue(accepted, "Tdarr did not expose a completed staged transcode for acceptance")
        }
        assertTrue(
            waitUntil(90_000) { hasMediaFile(outputDirectory) },
            "Tdarr did not copy the accepted transcode to the output library",
        )
        assertTrue(overview.nodes.isNotEmpty(), "Tdarr node was not attached")
    }

    private fun instance(type: AutomationServiceType, url: String) = AutomationInstance(
        id = type.name.lowercase(),
        serviceType = type,
        name = type.name,
        serverUrl = url,
    )

    private suspend fun waitUntil(timeoutMs: Long, predicate: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(2_000)
        }
        return false
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.parent ?: error("Could not locate repository root")
        }
        return candidate
    }

    private fun readApiKey(path: Path): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
        return document.getElementsByTagName("ApiKey").item(0)?.textContent?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("No API key in $path")
    }

    private fun readBazarrApiKey(path: Path): String {
        val auth = Regex("(?ms)^auth:\\s*$.*?(?=^[A-Za-z_]+:\\s*$|\\z)")
            .find(Files.readString(path))?.value.orEmpty()
        return Regex("(?m)^\\s{2}apikey:\\s*(\\S+)").find(auth)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() }
            ?: error("No API key in $path")
    }

    private fun hasMediaFile(path: Path): Boolean {
        if (!Files.exists(path)) return false
        return Files.walk(path).use { files ->
            files.anyMatch { Files.isRegularFile(it) && Files.size(it) > 0 }
        }
    }
}
