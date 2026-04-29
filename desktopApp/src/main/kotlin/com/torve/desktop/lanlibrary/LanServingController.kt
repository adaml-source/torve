package com.torve.desktop.lanlibrary

import com.torve.data.auth.AuthClient
import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaType
import com.torve.domain.model.DownloadStatus
import com.torve.domain.repository.DownloadRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Owns the LAN HTTP server lifecycle on desktop.
 *
 * Reacts to two flows:
 *   - [SettingsViewModel.state.lanServingEnabled] → start / stop the server.
 *   - [AuthClient.authUserFlow] → on transition to null (signed out),
 *     immediately stop the server AND wipe the token table so any
 *     in-flight LAN clients lose access.
 *
 * Always starts on **localhost only**. LAN binding is a future toggle
 * and is not exposed by this class.
 */
class LanServingController(
    private val tokenTable: LanMediaTokenTable,
    private val allowlist: DownloadFolderAllowlist,
    private val manifestBuilder: LanLibraryManifestBuilder,
    private val downloadRepository: DownloadRepository,
    private val settings: SettingsViewModel,
    private val authClient: AuthClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** The HTTP server. Constructed lazily — only bound after `start()`. */
    private val server: LocalLanHttpServer = LocalLanHttpServer(
        manifestProvider = { snapshotManifestBlocking() },
        tokenTable = tokenTable,
        allowlist = allowlist,
    )

    @Volatile
    private var started: Boolean = false

    /** For tests / diagnostics — the bound port, or -1 when stopped. */
    val serverPort: Int get() = server.port
    /** For tests / diagnostics — the in-process auth secret, or null when stopped. */
    val serverSecret: String? get() = server.secret

    fun start() {
        if (started) return
        started = true

        scope.launch {
            settings.state
                .map { it.lanServingEnabled }
                .distinctUntilChanged()
                .collect { enabled -> applyEnabled(enabled) }
        }

        scope.launch {
            authClient.authUserFlow
                .map { it != null }
                .distinctUntilChanged()
                .collect { signedIn ->
                    if (!signedIn) clearAllAndStop()
                }
        }
    }

    private suspend fun currentManifest(): LanLibraryManifest {
        val rows = runCatching { downloadRepository.getCompletedDownloads() }
            .getOrDefault(emptyList())
            .filter { it.status == DownloadStatus.COMPLETED }
        val inputs = rows.mapNotNull { row ->
            val path = row.filePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = File(path)
            if (!file.exists() || !file.isFile) return@mapNotNull null
            MediaEntryInput(
                file = file,
                title = row.title,
                mediaType = if (row.seasonNumber != null) LanMediaType.SHOW_EPISODE else LanMediaType.MOVIE,
                seasonNumber = row.seasonNumber,
                episodeNumber = row.episodeNumber,
                posterUrl = row.posterUrl,
            )
        }
        return manifestBuilder.build(inputs)
    }

    /**
     * The HTTP server's worker thread calls this once per `/local/manifest`
     * request. Blocking is fine — the worker pool is JDK HttpServer's, not
     * a coroutine dispatcher.
     */
    private fun snapshotManifestBlocking(): LanLibraryManifest = runBlocking { currentManifest() }

    private fun applyEnabled(enabled: Boolean) {
        if (enabled) server.start() else clearAllAndStop()
    }

    private fun clearAllAndStop() {
        runCatching { server.stop() }
        tokenTable.clearAll()
    }
}
