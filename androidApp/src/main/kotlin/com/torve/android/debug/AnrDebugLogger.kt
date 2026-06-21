package com.torve.android.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.torve.android.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight ANR diagnostics for Fire TV debug builds.
 * Logs key events, focus changes, state rebuilds, and detects main-thread stalls.
 *
 * Gated by [BuildConfig.DEBUG] — no-op in release.
 */
object AnrDebugLogger {
    private const val TAG = "AnrDebug"
    private const val STALL_THRESHOLD_MS = 1200L
    private const val WATCHDOG_INTERVAL_MS = 500L
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024 // 2 MB
    private val enabled = BuildConfig.DEBUG
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Rolling 1-second window counters
    val keyEventCount = AtomicInteger(0)
    val focusChangeCount = AtomicInteger(0)
    val guideRebuildCount = AtomicInteger(0)
    val zapChannelCount = AtomicInteger(0)
    val stateUpdateCount = AtomicInteger(0)

    // Last-event tracking for stall diagnostics
    @Volatile var lastKeyEvent: String = "none"
    @Volatile var lastScreen: String = "unknown"
    @Volatile var lastOverlay: String = "NONE"
    @Volatile var lastFocusedItem: String = "unknown"

    private var logFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogThread = Thread(::watchdogLoop, "AnrWatchdog").apply { isDaemon = true }
    @Volatile private var mainThreadHeartbeat = SystemClock.elapsedRealtime()
    @Volatile private var started = false

    // Timing helpers
    private val timingStack = ThreadLocal<ArrayDeque<Pair<String, Long>>>()

    fun init(filesDir: File) {
        if (!enabled) return
        logFile = File(filesDir, "anr_debug.log").also {
            if (it.length() > MAX_LOG_SIZE) it.writeText("") // rotate
        }
        // Heartbeat: main thread posts every WATCHDOG_INTERVAL_MS
        mainHandler.post(object : Runnable {
            override fun run() {
                mainThreadHeartbeat = SystemClock.elapsedRealtime()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        })
        // Counter reset every 1 second
        mainHandler.post(object : Runnable {
            override fun run() {
                val keys = keyEventCount.getAndSet(0)
                val focus = focusChangeCount.getAndSet(0)
                val guide = guideRebuildCount.getAndSet(0)
                val zap = zapChannelCount.getAndSet(0)
                val state = stateUpdateCount.getAndSet(0)
                if (keys + focus + guide + zap + state > 10) {
                    log("COUNTERS keys=$keys focus=$focus guide=$guide zap=$zap state=$state")
                }
                mainHandler.postDelayed(this, 1_000)
            }
        })
        if (!started) {
            started = true
            watchdogThread.start()
        }
        log("INIT AnrDebugLogger started")
    }

    fun log(message: String) {
        if (!enabled) return
        val ts = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "$ts [$thread] $message"
        Log.d(TAG, line)
        try {
            logFile?.let { file ->
                FileOutputStream(file, true).bufferedWriter().use { w ->
                    w.appendLine(line)
                }
            }
        } catch (_: Throwable) { }
    }

    fun beginTiming(label: String) {
        if (!enabled) return
        val stack = timingStack.get() ?: ArrayDeque<Pair<String, Long>>().also { timingStack.set(it) }
        stack.addLast(label to SystemClock.elapsedRealtime())
    }

    fun endTiming(label: String) {
        if (!enabled) return
        val stack = timingStack.get() ?: return
        val (name, start) = stack.removeLastOrNull() ?: return
        val elapsed = SystemClock.elapsedRealtime() - start
        if (elapsed > 8) { // Only log if >8ms
            log("TIMING $name ${elapsed}ms")
        }
    }

    fun logKeyEvent(key: String, type: String) {
        if (!enabled) return
        keyEventCount.incrementAndGet()
        lastKeyEvent = "$key/$type"
        log("KEY $key $type screen=$lastScreen overlay=$lastOverlay")
    }

    fun logFocusChange(item: String) {
        if (!enabled) return
        focusChangeCount.incrementAndGet()
        lastFocusedItem = item
    }

    fun logZapChannel(direction: Int, channelName: String) {
        if (!enabled) return
        zapChannelCount.incrementAndGet()
        log("ZAP direction=$direction channel=$channelName")
    }

    fun logOverlayChange(overlay: String) {
        if (!enabled) return
        lastOverlay = overlay
        log("OVERLAY $overlay")
    }

    fun logScreenChange(screen: String) {
        if (!enabled) return
        lastScreen = screen
    }

    fun logGuideRebuild(channelCount: Int) {
        if (!enabled) return
        guideRebuildCount.incrementAndGet()
        log("GUIDE_REBUILD channels=$channelCount")
    }

    fun logStateUpdate(source: String) {
        if (!enabled) return
        stateUpdateCount.incrementAndGet()
    }

    private fun watchdogLoop() {
        while (true) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MS)
                if (!enabled) continue
                val elapsed = SystemClock.elapsedRealtime() - mainThreadHeartbeat
                if (elapsed > STALL_THRESHOLD_MS) {
                    val stallMs = elapsed
                    log(buildString {
                        appendLine("⚠️ MAIN THREAD STALL detected: ${stallMs}ms")
                        appendLine("  screen=$lastScreen")
                        appendLine("  overlay=$lastOverlay")
                        appendLine("  lastKey=$lastKeyEvent")
                        appendLine("  lastFocus=$lastFocusedItem")
                        appendLine("  keyRate=${keyEventCount.get()}/s")
                        appendLine("  focusRate=${focusChangeCount.get()}/s")
                        appendLine("  zapRate=${zapChannelCount.get()}/s")
                        appendLine("  guideRebuilds=${guideRebuildCount.get()}/s")
                    })
                    // Also dump to logcat at ERROR level for adb visibility
                    Log.e(TAG, "STALL ${stallMs}ms screen=$lastScreen overlay=$lastOverlay lastKey=$lastKeyEvent")
                }
            } catch (_: InterruptedException) { break }
        }
    }
}
