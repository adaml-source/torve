package com.torve.android.player

import android.util.Log
import android.view.SurfaceView
import androidx.media3.exoplayer.ExoPlayer
import com.torve.android.R

fun SurfaceView.bindExoPlayerSurface(player: ExoPlayer?, reason: String) {
    val previous = getTag(R.id.torve_exo_surface_player) as? ExoPlayer
    if (previous === player) return
    if (previous != null) {
        runCatching { previous.clearVideoSurfaceView(this) }
            .onFailure { Log.w(TAG, "Ignoring stale Exo surface clear during $reason", it) }
    }
    setTag(R.id.torve_exo_surface_player, player)
    if (player != null) {
        runCatching { player.setVideoSurfaceView(this) }
            .onFailure { Log.w(TAG, "Ignoring stale Exo surface bind during $reason", it) }
    }
}

fun SurfaceView.clearExoPlayerSurface(reason: String) {
    val previous = getTag(R.id.torve_exo_surface_player) as? ExoPlayer
    if (previous != null) {
        runCatching { previous.clearVideoSurfaceView(this) }
            .onFailure { Log.w(TAG, "Ignoring stale Exo surface release during $reason", it) }
    }
    setTag(R.id.torve_exo_surface_player, null)
}

private const val TAG = "ExoSurfaceViewBinding"
