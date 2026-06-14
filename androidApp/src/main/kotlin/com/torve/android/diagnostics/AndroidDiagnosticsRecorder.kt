package com.torve.android.diagnostics

import android.content.Context
import android.os.SystemClock
import com.torve.domain.diagnostics.DiagnosticsRedactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object AndroidDiagnosticsRecorder {
    private const val PREFS = "torve_diagnostics"
    private const val KEY_LAST_CRASH = "last_crash_json"
    private const val MAX_LOG_LINES = 1_200
    private const val MAX_ACTIONS = 50
    private const val MAX_FOCUS_EVENTS = 80
    private const val WINDOW_MS = 15 * 60 * 1000L

    val sessionId: String = UUID.randomUUID().toString()
    val sessionStartedAtEpochMs: Long = System.currentTimeMillis()
    val processStartedElapsedMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    var currentScreen: String = "startup"
        private set

    @Volatile
    var previousScreen: String = "none"
        private set

    private val lock = Any()
    private val logLines = ArrayDeque<DiagnosticLogLine>()
    private val actions = ArrayDeque<DiagnosticAction>()
    private val focusEvents = ArrayDeque<DiagnosticFocusEvent>()
    private val httpFailures = ArrayDeque<DiagnosticHttpFailure>()
    private val playbackErrors = ArrayDeque<JsonObject>()

    fun init(context: Context) {
        recordLog("INFO", "app", "Torve diagnostics session started")
        recordAction("startup", "app_start", "application", "success")
        prune(System.currentTimeMillis())
        context.applicationContext
    }

    fun recordScreen(screen: String) {
        val clean = safe(screen).ifBlank { "unknown" }
        if (clean == currentScreen) return
        previousScreen = currentScreen
        currentScreen = clean
        recordAction(clean, "open_screen", clean, "success")
        recordLog("INFO", "screen", "open_screen screen=$clean")
    }

    fun recordAction(
        screen: String = currentScreen,
        action: String,
        target: String,
        result: String = "success",
        durationMs: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            actions.addLast(
                DiagnosticAction(
                    timestampEpochMs = now,
                    screen = safe(screen),
                    action = safe(action),
                    target = safe(target),
                    result = safe(result),
                    durationMs = durationMs,
                ),
            )
            while (actions.size > MAX_ACTIONS) actions.removeFirst()
        }
    }

    fun recordFocus(
        screen: String = currentScreen,
        elementId: String,
        direction: String,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            focusEvents.addLast(
                DiagnosticFocusEvent(
                    timestampEpochMs = now,
                    screen = safe(screen),
                    elementId = safe(elementId),
                    direction = safe(direction),
                ),
            )
            while (focusEvents.size > MAX_FOCUS_EVENTS) focusEvents.removeFirst()
        }
        recordLog("DEBUG", "focus", "focus_move screen=${safe(screen)} element=${safe(elementId)} direction=${safe(direction)}")
    }

    fun recordHttpFailure(
        endpoint: String,
        method: String,
        status: Int?,
        latencyMs: Long?,
        errorClass: String?,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            httpFailures.addLast(
                DiagnosticHttpFailure(
                    endpoint = sanitizeEndpoint(endpoint),
                    method = safe(method).uppercase(),
                    status = status,
                    latencyMs = latencyMs,
                    errorClass = safe(errorClass ?: "unknown"),
                    occurredAtEpochMs = now,
                ),
            )
            while (httpFailures.size > 25) httpFailures.removeFirst()
        }
        recordLog("WARN", "http", "http_failure method=${safe(method)} endpoint=${sanitizeEndpoint(endpoint)} status=${status ?: -1}")
    }

    fun recordPlaybackError(error: JsonObject) {
        synchronized(lock) {
            playbackErrors.addLast(error)
            while (playbackErrors.size > 20) playbackErrors.removeFirst()
        }
    }

    fun recordLog(
        severity: String,
        tag: String,
        message: String,
        screen: String = currentScreen,
    ) {
        val now = System.currentTimeMillis()
        val line = DiagnosticLogLine(
            timestampEpochMs = now,
            severity = safe(severity).uppercase(),
            tag = safe(tag),
            screen = safe(screen),
            sessionId = sessionId,
            message = safe(message),
        )
        synchronized(lock) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            pruneLocked(now)
        }
    }

    fun persistCrash(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val crash = buildJsonObject {
            put("timestampEpochMs", now)
            put("thread", safe(thread.name))
            put("exceptionClass", safe(throwable::class.simpleName ?: throwable.javaClass.name))
            put("message", safe(throwable.message ?: ""))
            put("stackTrace", safe(throwable.stackTraceToString().take(16_000)))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CRASH, crash.toString())
            .apply()
        recordLog("ERROR", "crash", "uncaught_exception thread=${safe(thread.name)} class=${safe(throwable.javaClass.name)}")
    }

    fun logsForReport(): List<String> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            pruneLocked(now)
            logLines.map { it.toLine() }
        }
    }

    fun recentActionsJson(): JsonArray = JsonArray(
        synchronized(lock) {
            actions.map { it.toJson() }
        },
    )

    fun focusJson(): JsonObject {
        val events = synchronized(lock) { focusEvents.toList() }
        val last = events.lastOrNull()
        return buildJsonObject {
            put("currentFocusedElement", last?.elementId ?: "unknown")
            put("currentScreen", currentScreen)
            put("lastFocusedElements", JsonArray(events.takeLast(50).map { it.toJson() }))
            put("focusStuckSuspected", false)
            put("lastRemoteInputs", JsonArray(events.takeLast(20).map { it.toRemoteInputJson() }))
        }
    }

    fun networkFailuresJson(): JsonArray = JsonArray(
        synchronized(lock) {
            httpFailures.takeLast(20).map { it.toJson() }
        },
    )

    fun recentPlaybackErrorsJson(): JsonArray = JsonArray(
        synchronized(lock) {
            playbackErrors.takeLast(20)
        },
    )

    fun crashesJson(context: Context): JsonObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CRASH, null)
        val lastCrash = raw?.let {
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject
            }.getOrNull()
        }
        return buildJsonObject {
            if (lastCrash == null) {
                put("lastCrash", JsonNull)
                put("recentCrashes", JsonArray(emptyList()))
            } else {
                put("lastCrash", lastCrash)
                put("recentCrashes", JsonArray(listOf(lastCrash)))
            }
        }
    }

    private fun prune(now: Long) {
        synchronized(lock) { pruneLocked(now) }
    }

    private fun pruneLocked(now: Long) {
        val cutoff = now - WINDOW_MS
        while (logLines.firstOrNull()?.timestampEpochMs?.let { it < cutoff } == true) {
            logLines.removeFirst()
        }
    }

    private fun safe(value: String): String = DiagnosticsRedactor.redact(value).take(2_000)

    private fun sanitizeEndpoint(endpoint: String): String {
        val redacted = DiagnosticsRedactor.redact(endpoint)
        val noScheme = redacted.substringAfter("://", redacted)
        val pathStart = noScheme.indexOf('/')
        val path = if (pathStart >= 0) noScheme.substring(pathStart) else redacted
        return path.substringBefore("?").take(180).ifBlank { "/" }
    }

    private data class DiagnosticLogLine(
        val timestampEpochMs: Long,
        val severity: String,
        val tag: String,
        val screen: String,
        val sessionId: String,
        val message: String,
    ) {
        fun toLine(): String =
            "$timestampEpochMs $severity/$tag screen=$screen session=$sessionId $message"
    }

    private data class DiagnosticAction(
        val timestampEpochMs: Long,
        val screen: String,
        val action: String,
        val target: String,
        val result: String,
        val durationMs: Long?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("timestampEpochMs", timestampEpochMs)
            put("screen", screen)
            put("action", action)
            put("target", target)
            put("result", result)
            durationMs?.let { put("durationMs", it) }
        }
    }

    private data class DiagnosticFocusEvent(
        val timestampEpochMs: Long,
        val screen: String,
        val elementId: String,
        val direction: String,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("screen", screen)
            put("elementId", elementId)
            put("direction", direction)
            put("timestampEpochMs", timestampEpochMs)
        }

        fun toRemoteInputJson(): JsonObject = buildJsonObject {
            put("direction", direction)
            put("timestampEpochMs", timestampEpochMs)
            put("screen", screen)
        }
    }

    private data class DiagnosticHttpFailure(
        val endpoint: String,
        val method: String,
        val status: Int?,
        val latencyMs: Long?,
        val errorClass: String,
        val occurredAtEpochMs: Long,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("endpoint", endpoint)
            put("method", method)
            status?.let { put("status", it) }
            latencyMs?.let { put("latencyMs", it) }
            put("errorClass", errorClass)
            put("occurredAtEpochMs", occurredAtEpochMs)
        }
    }
}
