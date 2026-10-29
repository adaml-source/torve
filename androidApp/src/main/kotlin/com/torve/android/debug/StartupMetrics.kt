package com.torve.android.debug

import android.content.Context
import android.os.SystemClock
import com.torve.android.BuildConfig
import java.io.File

/** Stage/input timings only, enabled solely in the non-debuggable QA variant. */
internal object StartupMetrics {
    private var file: File? = null
    private var firstPosterRecorded = false
    private var firstHomeLayoutRecorded = false

    @Synchronized fun homeContentLaidOut() {
        if (!BuildConfig.STARTUP_METRICS || firstHomeLayoutRecorded) return
        firstHomeLayoutRecorded = true
        record("STARTUP home_content_laid_out")
    }

    @Synchronized fun posterReady() {
        if (!BuildConfig.STARTUP_METRICS || firstPosterRecorded) return
        firstPosterRecorded = true
        record("STARTUP first_poster_ready")
    }

    @Synchronized fun init(context: Context) {
        if (!BuildConfig.STARTUP_METRICS) return
        file = context.getExternalFilesDir(null)?.let { File(it, "startup-metrics.log") }
        file?.let { if (it.length() > 128 * 1024) it.writeText("") }
        record("STARTUP attachBaseContext")
    }

    @Synchronized fun record(message: String) {
        if (!BuildConfig.STARTUP_METRICS ||
            (!message.startsWith("STARTUP ") && !message.startsWith("INPUT "))) return
        // No titles, credentials or provider responses are recorded here.
        runCatching { file?.appendText("${SystemClock.elapsedRealtime()} $message\n") }
    }
}
