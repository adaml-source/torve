package com.torve.desktop.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.io.path.Path

/**
 * Filesystem watcher that triggers an incremental rescan when files
 * appear / disappear / move under the tracked library folders.
 *
 * Implemented with Java NIO's [WatchService]. Each tracked root is
 * registered recursively (NIO doesn't auto-recurse on most platforms);
 * we walk the tree once at registration time and re-walk when a new
 * sub-directory shows up.
 *
 * Debouncing: events are coalesced - any flurry within a 1.5s window
 * triggers exactly one full rescan. Manual edits in a file manager
 * (drag-drop, batch rename) usually fire dozens of events; the debounce
 * keeps the rescan cost bounded.
 *
 * Lives for the app lifetime. Auto-(re)registers when the tracked
 * folder set changes (observed via [LocalLibraryRepository.state]).
 */
class LibraryWatcher(
    private val repository: LocalLibraryRepository,
    private val debounceMs: Long = 1_500L,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchService: WatchService? = null
    private var watchJob: Job? = null
    private var debounceJob: Job? = null
    private val registeredFolders = mutableSetOf<Path>()

    /**
     * Begin watching. Idempotent for the same instance - safe to call
     * once at app startup.
     */
    fun start() {
        if (watchService != null) return
        scope.launch {
            // Re-evaluate registrations whenever the tracked folder set
            // changes (add/remove from Settings).
            repository.state.collect { snapshot ->
                ensureService()
                syncRegistrations(snapshot.folders.map { Path(it.path) })
            }
        }
    }

    private fun ensureService() {
        if (watchService != null) return
        watchService = FileSystems.getDefault().newWatchService()
        watchJob = scope.launch { runWatchLoop() }
    }

    private fun syncRegistrations(targets: List<Path>) {
        val targetSet = targets.toSet()
        // Drop registrations for folders that vanished.
        val gone = registeredFolders - targetSet.flatMap { walkDirs(it).toSet() }.toSet()
        // (We don't try to cancel WatchKeys directly - the loop
        // discards events for unknown roots. Cleanup happens naturally
        // when the WatchService closes.)
        registeredFolders.removeAll(gone.toSet())
        // Register every directory under each target root, recursively.
        targets.forEach { root ->
            walkDirs(root).forEach { dir ->
                if (registeredFolders.add(dir)) {
                    registerSafely(dir)
                }
            }
        }
    }

    private fun walkDirs(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val out = ArrayList<Path>()
        runCatching {
            Files.walk(root).use { stream ->
                stream.iterator().forEachRemaining { p ->
                    if (Files.isDirectory(p) &&
                        p.fileName?.toString()?.startsWith(".") != true
                    ) {
                        out.add(p)
                    }
                }
            }
        }
        return out
    }

    private fun registerSafely(dir: Path) {
        runCatching {
            dir.register(
                watchService!!,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
        }.onFailure { t ->
            println("TORVE LIBWATCH | register failed for $dir: ${t.message}")
        }
    }

    private suspend fun runWatchLoop() {
        val service = watchService ?: return
        while (scope.isActive) {
            val key: WatchKey = runCatching { service.take() }.getOrNull() ?: break
            val events = key.pollEvents()
            // Reset before scheduling so we don't lose subsequent events
            // accumulated during the debounce window.
            val valid = key.reset()
            if (events.isNotEmpty()) {
                scheduleRescan()
            }
            if (!valid) {
                // The watched directory was deleted - drop our record
                // and let syncRegistrations re-add if needed.
                registeredFolders.remove(key.watchable() as? Path)
            }
        }
    }

    private fun scheduleRescan() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            runCatching { repository.rescanAll() }
                .onFailure { println("TORVE LIBWATCH | rescan failed: ${it.message}") }
        }
    }
}
