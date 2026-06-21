package com.torve.desktop.playback

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

data class DesktopPlaybackStartupMark(
    val stage: String,
    val sincePreviousMs: Long,
    val sinceStartMs: Long,
    val detail: String? = null,
    val threadName: String,
)

data class DesktopPlaybackStartupTraceSnapshot(
    val traceId: String,
    val label: String,
    val marks: List<DesktopPlaybackStartupMark>,
    val completed: Boolean,
) {
    fun stageTime(stage: String): Long? = marks.firstOrNull { it.stage == stage }?.sinceStartMs
}

class DesktopPlaybackStartupTrace internal constructor(
    val traceId: String,
    val label: String,
) {
    private val startNs = System.nanoTime()
    private var lastMarkNs = startNs
    private val marks = mutableListOf<DesktopPlaybackStartupMark>()
    private val seenStages = mutableSetOf<String>()
    @Volatile
    private var completed = false

    @Synchronized
    fun mark(stage: String, detail: String? = null) {
        val now = System.nanoTime()
        val mark = DesktopPlaybackStartupMark(
            stage = stage,
            sincePreviousMs = (now - lastMarkNs) / 1_000_000,
            sinceStartMs = (now - startNs) / 1_000_000,
            detail = detail,
            threadName = Thread.currentThread().name,
        )
        lastMarkNs = now
        marks += mark
        seenStages += stage
        println(
            buildString {
                append("TORVE STARTUP | trace=")
                append(traceId)
                append(" | label=")
                append(label)
                append(" | stage=")
                append(stage)
                append(" | deltaMs=")
                append(mark.sincePreviousMs)
                append(" | totalMs=")
                append(mark.sinceStartMs)
                append(" | thread=")
                append(mark.threadName)
                detail?.takeIf { it.isNotBlank() }?.let {
                    append(" | detail=")
                    append(it)
                }
            },
        )
    }

    @Synchronized
    fun markOnce(stage: String, detail: String? = null) {
        if (stage in seenStages) return
        mark(stage, detail)
    }

    @Synchronized
    fun complete(detail: String? = null) {
        if (completed) return
        completed = true
        mark("trace_complete", detail)
        DesktopPlaybackStartupTelemetry.complete(this)
    }

    @Synchronized
    fun snapshot(): DesktopPlaybackStartupTraceSnapshot {
        return DesktopPlaybackStartupTraceSnapshot(
            traceId = traceId,
            label = label,
            marks = marks.toList(),
            completed = completed,
        )
    }
}

object DesktopPlaybackStartupTelemetry {
    private val counter = AtomicInteger(1)
    private val active = ConcurrentHashMap<String, DesktopPlaybackStartupTrace>()
    private val completed = ConcurrentLinkedDeque<DesktopPlaybackStartupTraceSnapshot>()
    private const val MAX_COMPLETED = 32

    fun begin(label: String): DesktopPlaybackStartupTrace {
        val traceId = "startup-${counter.getAndIncrement()}"
        return DesktopPlaybackStartupTrace(traceId, label).also {
            active[traceId] = it
        }
    }

    internal fun complete(trace: DesktopPlaybackStartupTrace) {
        active.remove(trace.traceId)
        completed.addFirst(trace.snapshot())
        while (completed.size > MAX_COMPLETED) {
            completed.pollLast()
        }
    }

    fun latestCompleted(labelContains: String? = null): DesktopPlaybackStartupTraceSnapshot? {
        return completed.firstOrNull { labelContains == null || it.label.contains(labelContains, ignoreCase = true) }
    }

    fun allCompleted(): List<DesktopPlaybackStartupTraceSnapshot> = completed.toList()

    fun clear() {
        active.clear()
        completed.clear()
    }
}

typealias PlaybackStartupTrace = DesktopPlaybackStartupTrace
typealias PlaybackStartupTraceSnapshot = DesktopPlaybackStartupTraceSnapshot

object PlaybackStartupTelemetry {
    fun begin(request: DesktopPlaybackRequest): PlaybackStartupTrace =
        DesktopPlaybackStartupTelemetry.begin(request.startupTraceLabel())

    fun latest(): PlaybackStartupTraceSnapshot? = DesktopPlaybackStartupTelemetry.latestCompleted()

    fun clear() {
        DesktopPlaybackStartupTelemetry.clear()
    }
}

private fun DesktopPlaybackRequest.startupTraceLabel(): String = buildString {
    append(mediaType.name.lowercase())
    append(':')
    append(imdbId ?: mediaId)
    seasonNumber?.let { append(":s$it") }
    episodeNumber?.let { append(":e$it") }
}
