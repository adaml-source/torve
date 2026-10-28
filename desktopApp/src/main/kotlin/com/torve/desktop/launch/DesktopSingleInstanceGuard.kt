package com.torve.desktop.launch

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Owns the operating-system lock for one desktop Torve process. */
internal class DesktopSingleInstanceHandle private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { file.close() }
    }

    companion object {
        fun tryAcquire(lockFile: File): DesktopSingleInstanceHandle? {
            lockFile.parentFile?.mkdirs()
            val file = RandomAccessFile(lockFile, "rw")
            val lock = try {
                file.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Throwable) {
                file.close()
                throw error
            }
            if (lock == null) {
                file.close()
                return null
            }
            return DesktopSingleInstanceHandle(file, lock)
        }
    }
}

private var processInstanceHandle: DesktopSingleInstanceHandle? = null

internal fun desktopSingleInstanceLockFile(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File(System.getProperty("user.home"), ".torve")
    return File(File(base, "Torve"), "torve.instance.lock")
}

/** Returns false when another Torve process already owns the shared database. */
internal fun acquireDesktopSingleInstance(): Boolean {
    if (processInstanceHandle != null) return true
    val handle = runCatching {
        DesktopSingleInstanceHandle.tryAcquire(desktopSingleInstanceLockFile())
    }.getOrElse { error ->
        // Do not make Torve permanently unlaunchable because a policy blocks
        // the lock file; WAL/busy_timeout still provide database protection.
        launchGuardLog(
            "single_instance_guard_unavailable",
            "type" to error::class.simpleName,
            "message" to error.message.orEmpty().take(240),
        )
        return true
    }
    if (handle == null) {
        launchGuardLog("duplicate_instance_blocked")
        return false
    }
    processInstanceHandle = handle
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                processInstanceHandle?.close()
                processInstanceHandle = null
            },
            "torve-single-instance-release",
        ),
    )
    launchGuardLog("single_instance_acquired")
    return true
}
