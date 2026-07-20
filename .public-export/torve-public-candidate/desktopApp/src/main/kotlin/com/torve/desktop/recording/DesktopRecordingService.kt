package com.torve.desktop.recording

import com.torve.desktop.lanlibrary.DownloadFolderAllowlist
import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFailureReason
import com.torve.domain.recording.RecordingScheduler
import com.torve.domain.recording.RecordingStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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
) : com.torve.presentation.recording.RecordingStarter {

    /** RecordingStarter: also delete the .ts file from disk if present. */
    override suspend fun deleteRow(id: String) {
        val row = runCatching { repository.get(id) }.getOrNull() ?: return
        val path = row.filePath ?: return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

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
    override suspend fun runNow(rec: Recording) = kickOff(rec)

    /**
     * Cancel an in-flight recording. Returns true if the job was
     * actually cancelled (false if already terminated).
     */
    override suspend fun stopRunning(id: String): Boolean = cancelRunning(id)

    suspend fun cancelRunning(id: String): Boolean = mutex.withLock {
        val job = activeJobs[id]
        if (job != null) {
            // Cancel the coroutine and let runRecording's catch block decide
            // whether to mark COMPLETED (partial file with bytes) or CANCELLED
            // (nothing was written yet). Calling scheduler.cancel() here would
            // pre-set the status to CANCELLED, which markCompleted then refuses
            // to overwrite -- losing the partial recording from the library.
            // Keep the job in activeJobs until runRecording finalizes it so a
            // repeated Stop click cannot route through the scheduled-cancel path.
            job.cancel()
            true
        } else {
            // No active job: must be SCHEDULED. Pre-running cancel is safe.
            scheduler.cancel(id)
            true
        }
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
        val target = when (val resolved = RecordingPathResolver.resolve(root, rec)) {
            is RecordingPathResult.Ready -> resolved.file
            is RecordingPathResult.Failed -> {
                scheduler.markFailed(
                    rec.id,
                    failureReasonFor(resolved.failure.issue),
                    resolved.failure.message,
                )
                return
            }
        }
        val parent = target.parentFile
        if (parent == null) {
            scheduler.markFailed(
                rec.id,
                RecordingFailureReason.FILE_WRITE_ERROR,
                "Code: RECORDING_FOLDER_INVALID; Folder: ${root.absolutePath}",
            )
            return
        }
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
        // The configured root must be covered by the storage allowlist.
        // Use directory containment instead of exact equality so a user can
        // choose a Recordings folder under a broader configured media root.
        if (!allowlist.coversDirectory(root)) {
            scheduler.markFailed(
                rec.id,
                RecordingFailureReason.OUT_OF_ALLOWLIST,
                "Recordings folder isn't in the configured download allowlist.",
            )
            return
        }

        val started = scheduler.markStarted(rec.id, target.absolutePath) ?: return
        writeSidecarMetadata(started, target)
        val streamResult = runCatching {
            withContext(Dispatchers.IO) { writeStreamToFile(started, target) }
        }
        val byteCount = streamResult.getOrNull()
        val cancelException = streamResult.exceptionOrNull() as? kotlinx.coroutines.CancellationException
        mutex.withLock { activeJobs.remove(rec.id) }
        when {
            // User-initiated cancel (Stop button). Preserve whatever the
            // file already has on disk as a COMPLETED partial recording so
            // it stays in the library and is playable. Only mark CANCELLED
            // if literally zero bytes ever made it to disk.
            cancelException != null -> {
                val partialBytes = runCatching {
                    if (target.exists()) target.length() else 0L
                }.getOrDefault(0L)
                if (partialBytes > 0L) {
                    scheduler.markCompleted(rec.id, partialBytes)
                } else {
                    scheduler.cancel(rec.id)
                }
            }
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
        val output = try {
            target.outputStream()
        } catch (t: FileNotFoundException) {
            throw RecordingFileWriteException(t)
        } catch (t: IOException) {
            throw RecordingFileWriteException(t)
        }
        val stream = try {
            openConnection(rec.streamUrl)
        } catch (t: Throwable) {
            runCatching { output.close() }
            throw t
        }
        var written = 0L
        try {
            stream.input.use { input ->
                output.use { out ->
                    copyUntil(input, out, deadlineMs = rec.endMs) { written += it }
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
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (nowMs() >= deadlineMs) return
            val read = input.read(buf)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (read < 0) return
            output.write(buf, 0, read)
            onChunk(read.toLong())
        }
    }

    private fun mapFailure(t: Throwable?): Pair<RecordingFailureReason, String> {
        if (t == null) return RecordingFailureReason.UNKNOWN to "Unknown error."
        val msg = t.message?.take(160).orEmpty()
        return when (t) {
            is RecordingFileWriteException -> RecordingFailureReason.FILE_WRITE_ERROR to
                (t.cause?.message?.take(160) ?: msg)
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

    private fun failureReasonFor(issue: RecordingFolderIssue): RecordingFailureReason = when (issue) {
        RecordingFolderIssue.RECORDING_STORAGE_FULL_OR_UNAVAILABLE -> RecordingFailureReason.DISK_FULL
        else -> RecordingFailureReason.FILE_WRITE_ERROR
    }

    private fun writeSidecarMetadata(rec: Recording, target: File) {
        runCatching {
            val json = buildJsonObject {
                put("id", rec.id)
                put("title", rec.displayTitle)
                put("channelName", rec.channelName)
                put("programmeDescription", rec.programmeDescription)
                put("epgProgrammeTitle", rec.epgProgrammeTitle)
                put("epgProgrammeSubtitle", rec.epgProgrammeSubtitle)
                put("epgProgrammeCategory", rec.epgProgrammeCategory)
                put("epgChannelId", rec.epgChannelId)
                put("recordingKind", rec.recordingKind.name)
                put("epgMatchStatus", rec.epgMatchStatus.name)
                put("sourceLabel", rec.sourceLabel)
                put("startMs", rec.startMs)
                put("endMs", rec.endMs)
            }
            File(target.parentFile, target.nameWithoutExtension + ".json")
                .writeText(SidecarJson.encodeToString(JsonObject.serializer(), json))
        }.onFailure { t ->
            println("TORVE RECORDINGS | sidecar metadata failed: ${t.message}")
        }
    }

    companion object {
        private val SidecarJson = Json { prettyPrint = true }

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

private class RecordingFileWriteException(cause: Throwable) : IOException(cause.message, cause)

/**
 * Test-friendly seam - the production path uses
 * [DesktopRecordingService.defaultConnection], tests substitute an
 * in-memory stream.
 */
interface RecordingHttpStream {
    val input: java.io.InputStream
    fun disconnect()
}
