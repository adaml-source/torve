package com.torve.android.files

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.integrations.*
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.ProfileRepository
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

data class FilesBrowserState(val configured: Boolean = false, val folder: String = "", val root: String = "",
    val entries: List<WebDavEntry> = emptyList(), val progress: Map<String, WatchProgress> = emptyMap(),
    val loading: Boolean = true, val restricted: Boolean = false, val error: WebDavFailure? = null)

class FilesBrowserController(private val ids: UserIdProvider, private val secrets: IntegrationSecretStore,
    private val profiles: ProfileRepository, private val progress: WatchProgressRepository,
    private val browser: WebDavBrowser = WebDavBrowser()) : AutoCloseable {
    private val owner = ids.currentDataUserId()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private var connection: WebDavConnection? = null
    private var operation: Job? = null
    private val mutable = MutableStateFlow(FilesBrowserState())
    val state = mutable.asStateFlow()
    init { launch {
        if (profiles.getActiveProfile()?.maxContentRating != null) {
            mutable.value = FilesBrowserState(loading = false, restricted = true); return@launch
        }
        connection = secrets.get(IntegrationSecretKey.WEBDAV_CONNECTION)?.let { runCatching {
            json.decodeFromString<WebDavConnection>(it).also { config -> config.root() }
        }.getOrNull() }
        val config = connection
        if (config == null) mutable.value = FilesBrowserState(loading = false) else load(config, config.root().toASCIIString())
    } }
    fun connect(url: String, username: String, password: String) = launch {
        if (profiles.getActiveProfile()?.maxContentRating != null) { mutable.value = FilesBrowserState(loading = false, restricted = true); return@launch }
        val config = try { WebDavConnection(url, username, password).also { it.root() } }
            catch (_: Exception) { mutable.value = mutable.value.copy(loading = false, error = WebDavFailure.ADDRESS); return@launch }
        if (load(config, config.root().toASCIIString())) {
            secrets.setStorageMode(IntegrationSecretKey.WEBDAV_CONNECTION, IntegrationStorageMode.DEVICE_ONLY)
            secrets.put(IntegrationSecretKey.WEBDAV_CONNECTION, json.encodeToString(config)); connection = config
        }
    }
    fun refresh() = launch { connection?.let { load(it, mutable.value.folder) } }
    fun openFolder(entry: WebDavEntry) = launch { if (entry.folder) connection?.let { load(it, entry.url) } }
    fun parent(): Boolean {
        val value = mutable.value
        if (!value.configured || value.folder == value.root) return false
        launch { connection?.let { config -> load(config, java.net.URI(value.folder).resolve("../").toASCIIString()) } }
        return true
    }
    fun disconnect() = launch {
        secrets.remove(IntegrationSecretKey.WEBDAV_CONNECTION); connection = null; mutable.value = FilesBrowserState(loading = false)
    }
    fun play(entry: WebDavEntry, onPlay: (WebDavEntry) -> Unit) = launch {
        if (entry.folder || profiles.getActiveProfile()?.maxContentRating != null) return@launch
        val config = connection ?: return@launch
        if (scopedWebDavUri(config.root(), config.root(), entry.url) == null) return@launch
        if (ids.currentDataUserId() != owner) return@launch
        PendingAuthenticatedPlayback.stage(entry.url, owner, config.headers())
        onPlay(entry)
    }
    private fun launch(block: suspend () -> Unit) {
        if (ids.currentDataUserId() != owner) return
        operation?.cancel()
        operation = scope.launch {
            try { ids.withPersonalDataScope { block() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ids.currentDataUserId() == owner) mutable.value = mutable.value.copy(loading = false,
                    error = (error as? WebDavException)?.reason ?: WebDavFailure.NETWORK)
            }
        }
    }
    private suspend fun load(config: WebDavConnection, folder: String): Boolean {
        mutable.value = mutable.value.copy(loading = true, error = null)
        return try {
            val entries = browser.list(config, folder)
            val bookmarks = progress.getAllProgress().filter { it.mediaId.startsWith("webdav:") }.associateBy { it.mediaId }
            if (ids.currentDataUserId() != owner) return false
            mutable.value = FilesBrowserState(true, folder, config.root().toASCIIString(), entries, bookmarks, loading = false)
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value = mutable.value.copy(loading = false, error = (error as? WebDavException)?.reason ?: WebDavFailure.NETWORK)
            false
        }
    }
    override fun close() { scope.cancel(); browser.close() }
}
