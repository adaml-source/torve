package com.torve.desktop.recording

import com.torve.desktop.lanlibrary.DownloadFolderAllowlist
import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFailureReason
import com.torve.domain.recording.RecordingFileNaming
import com.torve.domain.recording.RecordingScheduler
import com.torve.domain.recording.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls an IPTV stream URL into a file under the recordings root.
 *
 * **Storage boundary** (Prompt 10 acceptance):
 *   - Resolves the recording target path under [recordingsRoot].
 *   - Validates it lies inside the [DownloadFolderAllowlist]. The
 *     allowlist already covers the user's configured download folders
 *     including the recordings root; refusing on miss is fail-closed.
 *   - On cancellation / failure / completion the file is closed and
 *     left in place for the user to inspect; the row's status reflects
 *     the outcome.
 *
 * This service is intentionally simple. It does not transcode, does not
 * chunk by HLS segment, and writes whatever bytes the upstream sends
 * (typically MPEG-TS) verbatim. Real DVR stacks usually demux to MKV;
 * that's a follow-up.
 */
class DesktopRecordingService(
    private val scheduler: RecordingScheduler,
    private val repository: com.torve.domain.recording.RecordingRepository,
    private val allowlist: DownloadFolderAllowlist,
    private val recordingsRootProvider: () -> File?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    private val openConnection: (String) -> RecordingHttpStream = ::defaultConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val activeJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()

    @Volatile
    private var started: Boolean = false

    /**
     * Start the lifecycle observer. Idempotent. Watches the
     * scheduler's `active` flow and kicks off recording jobs whenever
     * a SCHEDULED row's start time has arrived.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                tick()
                delay(pollIntervalMs)
            }
        }
    }

    /** Externally observable in tests - picks up new schedules. */
    suspend fun tick() {
        val now = nowMs()
        val due = repository.listByStatus(RecordingStatus.SCHEDULED)
            .filter { it.startMs <= now && it.endMs > now }
        for (rec in due) {
            kickOff(rec)
        }
    }

    /**
     * Manually start a recording right now (used by the "Record this
     * programme" button which arms a job for an in-progress slot). The
     * scheduler call is the canonical lifecycle entry point so the
     * status flips through the same code path as the timer-driven case.
     */
    suspend fun runNow(rec: Recording) = kickOff(rec)

    /**
     * Cancel an in-flight recording. Returns true if the job was
     * actually cancelled (false if already terminated).
     */
    suspend fun cancelRunning(id: String): Boolean = mutex.withLock {
        val job = activeJobs.remove(id)
        if (job != null) {
            job.cancel()
            scheduler.cancel(id)
            true
        } else false
    }

    private suspend fun kickOff(rec: Recording) {
        mutex.withLock {
            if (activeJobs.containsKey(rec.id)) return
            val job = scope.launch { runRecording(rec) }
            activeJobs[rec.id] = job
        }
    }

    private suspend fun runRecording(rec: Recording) {
        val root = recordingsRootProvider()
        if (root == null) {
            scheduler.markFailed(
                rec.id,
                RecordingFailureReason.OUT_OF_ALLOWLIST,
                "No recordings folder configured. Add one in Settings → Storage.",
            )
            return
        }
        val relative = RecordingFileNaming.relativePath(
            channelName = rec.channelName,
            programmeTitle = rec.programmeTitle,
            startEpochMs = rec.startMs,
        )
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        // The file doesn't exist yet, so allowlist.isAllowed(File) needs
        // the parent root + checking the resolved path stays under it.
        // We use the canonical-prefix containment helper directly.
        if (!isWithinRoot(target, root)) {
            scheduler.markFailed(
                rec.id,
                RecordingFailureReason.OUT_OF_ALLOWLIST,
                "Refused to write outside the recordings root.",
            )
            return
        }
        // The configured root must itself be in the allowlist -
        // catches misconfiguration where the user pointed at something
        // outside their declared download folders.
        if (allowlist.roots().none { it.canonicalPath.equals(root.canonicalPath, ignoreCase = pathIsCaseInsensitive()) }) {
            scheduler.markFailed(
                rec.id,
                RecordingFailureReason.OUT_OF_ALLOWLIST,
                "Recordings folder isn't in the configured download allowlist.",
            )
            return
        }

        val started = scheduler.markStarted(rec.id, target.absolutePath) ?: return
        val streamResult = runCatching {
            withContext(Dispatchers.IO) { writeStreamToFile(started, target) }
        }
        val byteCount = streamResult.getOrNull()
        mutex.withLock { activeJobs.remove(rec.id) }
        when {
            byteCount == null -> {
                val t = streamResult.exceptionOrNull()
                val (reason, msg) = mapFailure(t)
                scheduler.markFailed(rec.id, reason, msg)
            }
            byteCount <= 0L -> {
                scheduler.markFailed(
                    rec.id,
                    RecordingFailureReason.UPSTREAM_REJECTED,
                    "Upstream produced no bytes.",
                )
            }
            else -> {
                scheduler.markCompleted(rec.id, byteCount)
            }
        }
    }

    private suspend fun writeStreamToFile(rec: Recording, target: File): Long {
        val stream = openConnection(rec.streamUrl)
        var written = 0L
        try {
            stream.input.use { input ->
                target.outputStream().use { output ->
                    copyUntil(input, output, deadlineMs = rec.endMs) { written += it }
                }
            }
        } finally {
            stream.disconnect()
        }
        return written
    }

    private suspend fun copyUntil(
        input: java.io.InputStream,
        output: OutputStream,
        deadlineMs: Long,
        onChunk: (Long) -> Unit,
    ) {
        val buf = ByteArray(BUFFER_BYTES)
        while (true) {
            if (nowMs() >= deadlineMs) return
            val read = input.read(buf)
            if (read < 0) return
            output.write(buf, 0, read)
            onChunk(read.toLong())
        }
    }

    private fun mapFailure(t: Throwable?): Pair<RecordingFailureReason, String> {
        if (t == null) return RecordingFailureReason.UNKNOWN to "Unknown error."
        val msg = t.message?.take(160).orEmpty()
        return when (t) {
            is java.io.IOException -> RecordingFailureReason.NETWORK_ERROR to msg
            is java.net.SocketTimeoutException -> RecordingFailureReason.NETWORK_ERROR to "Stream stalled."
            else -> RecordingFailureReason.UNKNOWN to msg
        }
    }

    private fun isWithinRoot(target: File, root: File): Boolean {
        val rootPath = runCatching { root.canonicalFile.absolutePath }.getOrNull() ?: return false
        // We don't yet have a canonical form for `target` because the
        // file doesn't exist. Use the parent (which we just mkdir'd).
        val resolved = runCatching {
            (target.parentFile ?: return false).canonicalFile.absolutePath
        }.getOrNull() ?: return false
        return resolved.equals(rootPath, ignoreCase = pathIsCaseInsensitive()) ||
            resolved.startsWith(rootPath + File.separatorChar, ignoreCase = pathIsCaseInsensitive())
    }

    private fun pathIsCaseInsensitive(): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return osName.contains("windows") || osName.contains("mac")
    }

    companion object {
        const val DEFAULT_POLL_MS: Long = 30_000L
        private const val BUFFER_BYTES: Int = 64 * 1024

        internal fun defaultConnection(url: String): RecordingHttpStream {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Torve-RecordingService/1.0")
            }
            return object : RecordingHttpStream {
                override val input: java.io.InputStream = conn.inputStream
                override fun disconnect() {
                    runCatching { conn.disconnect() }
                }
            }
        }
    }
}

/**
 * Test-friendly seam - the production path uses
 * [DesktopRecordingService.defaultConnection], tests substitute an
 * in-memory stream.
 */
interface RecordingHttpStream {
    val input: java.io.InputStream
    fun disconnect()
}
