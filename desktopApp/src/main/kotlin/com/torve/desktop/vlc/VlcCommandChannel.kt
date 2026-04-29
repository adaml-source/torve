package com.torve.desktop.vlc

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Serializes all native VLC commands through a single dedicated thread.
 *
 * LibVLC is not thread-safe for control operations. All media player
 * commands (play, pause, seek, track selection, etc.) must be executed
 * from one thread. This channel provides that guarantee.
 *
 * No native control calls should be made from Compose recompositions,
 * the Swing EDT, or arbitrary coroutine dispatchers. Everything goes
 * through [execute].
 */
class VlcCommandChannel {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "torve-vlc-cmd").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var shutdown = false

    /**
     * Execute a native VLC command on the dedicated command thread.
     * Suspends until the command completes.
     */
    suspend fun <T> execute(block: () -> T): T {
        check(!shutdown) { "VlcCommandChannel is shut down" }
        return withContext(dispatcher) { block() }
    }

    /**
     * Execute a native VLC command without suspending.
     * Use only for fire-and-forget commands where the result is not needed.
     */
    fun post(block: () -> Unit) {
        if (shutdown) return
        executor.execute {
            runCatching { block() }.onFailure { e ->
                println("TORVE VLC CMD | error: ${e.message}")
            }
        }
    }

    fun shutdown() {
        shutdown = true
        dispatcher.close()
        executor.shutdown()
    }
}
