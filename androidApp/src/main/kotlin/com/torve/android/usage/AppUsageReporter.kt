package com.torve.android.usage

import android.os.Build
import android.os.SystemClock
import com.torve.android.BuildConfig
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.data.auth.AuthClient
import com.torve.data.device.DeviceActivityApi
import com.torve.data.device.DeviceActivityReport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

/** Counts visible, signed-in use. Hidden activities and anonymous browsing are excluded. */
class AppUsageReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val auth: AuthClient by lazy { getKoin().get() }
    private val api: DeviceActivityApi by lazy { getKoin().get() }
    private var task: Job? = null
    private var owner: String? = null
    private var session = UUID.randomUUID().toString()
    private var sequence = 0
    private var foreground = 0L
    private var playback = 0L
    private var previous = 0L
    private var ticks = 0

    fun start() {
        if (task?.isActive == true) return
        reset(); previous = SystemClock.elapsedRealtime(); send()
        task = scope.launch {
            while (true) {
                delay(1000); tick()
                if (++ticks >= 30) { ticks = 0; send() }
            }
        }
    }

    fun stop() {
        if (task == null) return
        tick(); send(); task?.cancel(); task = null
    }

    private fun reset() {
        owner = auth.authUserFlow.value?.id
        session = UUID.randomUUID().toString(); sequence = 0; foreground = 0; playback = 0; ticks = 0
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (owner != auth.authUserFlow.value?.id) { reset(); previous = now; send(); return }
        if (owner != null) {
            val elapsed = (now - previous).coerceIn(0, 5000)
            foreground += elapsed
            if (ActivePlaybackState.isPlaying) playback += elapsed
        }
        previous = now
    }

    private fun send() {
        val uid = owner ?: return
        val device = auth.currentDeviceRegistration()
        val platform = when {
            device.device_type == "tv" && Build.MANUFACTURER.equals("Amazon", ignoreCase = true) -> "firetv"
            device.device_type == "tv" -> "android-tv"
            else -> "android"
        }
        val body = DeviceActivityReport(device.installation_id, platform, session, sequence++, foreground, playback,
            Build.MANUFACTURER.take(80), Build.MODEL.take(120), "Android ${Build.VERSION.RELEASE}".take(80),
            device.app_version, BuildConfig.VERSION_CODE.toString())
        scope.launch { api.report(uid, body) }
    }
}
