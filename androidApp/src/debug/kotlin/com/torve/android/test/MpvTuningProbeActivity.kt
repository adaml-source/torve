package com.torve.android.test

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.torve.android.player.MPVLib
import com.torve.android.player.MPVPlayerEngine
import com.torve.android.player.MPVRuntimeOverrides
import com.torve.android.player.MPVTextureView
import com.torve.android.ui.system.configureTorveEdgeToEdge
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import java.io.File
import kotlin.math.roundToInt

class MpvTuningProbeActivity : ComponentActivity() {
    private var engine: MPVPlayerEngine? = null
    private var textureView: MPVTextureView? = null
    private var startedAtMs = 0L
    private var firstAdvanceMs: Long? = null
    private var latestState = PlayerState()
    private var maxPositionMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTorveEdgeToEdge()
        val videoSyncMode = intent.getStringExtra(EXTRA_VIDEO_SYNC_MODE) ?: DEFAULT_VIDEO_SYNC_MODE
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
        MPVRuntimeOverrides.liveVideoSyncModeOverride = videoSyncMode

        val fixtureFile = extractFixture(FIXTURE_ASSET_PATH)
        val view = MPVTextureView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener { finish() }
        }
        textureView = view
        setContentView(view)

        val mpvEngine = MPVPlayerEngine(this).also { player ->
            player.initialize()
            player.addListener(
                object : PlayerListener {
                    override fun onStateChanged(state: PlayerState) {
                        latestState = state
                        if (state.positionMs > maxPositionMs) {
                            maxPositionMs = state.positionMs
                        }
                        if (firstAdvanceMs == null && state.positionMs >= 1_000L) {
                            firstAdvanceMs = SystemClock.elapsedRealtime() - startedAtMs
                        }
                    }

                    override fun onError(message: String) {
                        Log.i(TAG, "probe_error mode=$videoSyncMode message=$message")
                    }
                },
            )
        }
        engine = mpvEngine

        view.post {
            val bindingToken = 1
            view.bindSurface(bindingToken, "probe_$videoSyncMode")
            startedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "probe_start mode=$videoSyncMode file=${fixtureFile.name} durationMs=$durationMs")
            mpvEngine.play(fixtureFile.absolutePath)
        }

        view.postDelayed(
            {
                logSummary(videoSyncMode, durationMs)
                finish()
            },
            durationMs,
        )
    }

    override fun onDestroy() {
        textureView?.releaseSurface("probe_destroy")
        engine?.release()
        engine = null
        textureView = null
        MPVRuntimeOverrides.liveVideoSyncModeOverride = null
        super.onDestroy()
    }

    private fun extractFixture(assetPath: String): File {
        val target = File(cacheDir, assetPath.substringAfterLast('/'))
        assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private fun logSummary(videoSyncMode: String, durationMs: Long) {
        val mistimed = runCatching { MPVLib.getPropertyInt("mistimed-frame-count") }.getOrNull()
        val dropped = runCatching { MPVLib.getPropertyInt("vo-drop-frame-count") }.getOrNull()
        val delayed = runCatching { MPVLib.getPropertyInt("vo-delayed-frame-count") }.getOrNull()
        val avsyncMs = runCatching { MPVLib.getPropertyDouble("total-avsync-change") }
            .getOrNull()
            ?.times(1000.0)
            ?.roundToInt()
        val vfFps = runCatching { MPVLib.getPropertyDouble("estimated-vf-fps") }.getOrNull()
        val displayFps = runCatching { MPVLib.getPropertyDouble("display-fps") }.getOrNull()
        Log.i(
            TAG,
            "probe_summary mode=$videoSyncMode durationMs=$durationMs firstAdvanceMs=${firstAdvanceMs ?: -1} " +
                "maxPositionMs=$maxPositionMs mistimed=${mistimed ?: -1} dropped=${dropped ?: -1} " +
                "delayed=${delayed ?: -1} avsyncMs=${avsyncMs ?: -1} vfFps=${vfFps ?: -1.0} " +
                "displayFps=${displayFps ?: -1.0} buffering=${latestState.isBuffering} playing=${latestState.isPlaying}",
        )
    }

    private companion object {
        private const val TAG = "MpvTuningProbe"
        private const val EXTRA_VIDEO_SYNC_MODE = "video_sync_mode"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val DEFAULT_VIDEO_SYNC_MODE = "display-resample"
        private const val DEFAULT_DURATION_MS = 12_000L
        private const val FIXTURE_ASSET_PATH = "fixtures/mpv_tuning_25fps.mp4"
    }
}
