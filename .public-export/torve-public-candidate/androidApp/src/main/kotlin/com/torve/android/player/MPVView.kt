package com.torve.android.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * SurfaceView for mpv video rendering.
 * Attaches/detaches the surface to MPVLib when the surface is created/destroyed.
 */
class MPVView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    var onSurfaceAttachedStateChanged: ((Boolean) -> Unit)? = null

    private var lastBindingToken: Int? = null
    private var pendingBindingToken: Int? = null
    private var pendingBindingReason: String? = null
    private var surfaceAttached = false

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        setZOrderMediaOverlay(false)
    }

    fun bindSurface(bindingToken: Int, reason: String) {
        pendingBindingToken = bindingToken
        pendingBindingReason = reason
        val surface = holder.surface
        if (!surface.isValid) {
            Log.d(TAG, "Deferring mpv SurfaceView bind until surface is valid reason=$reason bindId=$bindingToken")
            return
        }
        val needsRebind = !surfaceAttached || lastBindingToken != bindingToken
        if (!needsRebind) {
            onSurfaceAttachedStateChanged?.invoke(true)
            return
        }

        if (surfaceAttached) {
            runCatching { MPVLib.detachSurface() }
        }
        Log.d(TAG, "Binding mpv SurfaceView surface reason=$reason bindId=$bindingToken size=${width}x${height}")
        MPVLib.attachSurface(surface)
        surfaceAttached = true
        lastBindingToken = bindingToken
        onSurfaceAttachedStateChanged?.invoke(true)
    }

    fun releaseSurface(reason: String) {
        if (surfaceAttached) {
            Log.d(TAG, "Releasing mpv SurfaceView surface reason=$reason bindId=${lastBindingToken ?: -1}")
            runCatching { MPVLib.detachSurface() }
        }
        surfaceAttached = false
        lastBindingToken = null
        onSurfaceAttachedStateChanged?.invoke(false)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created ${width}x${height}")
        val bindingToken = pendingBindingToken
        if (bindingToken != null) {
            bindSurface(bindingToken, pendingBindingReason ?: "surface_created")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed ${width}x$height format=$format")
        pendingBindingToken?.let { bindSurface(it, pendingBindingReason ?: "surface_changed") }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseSurface("surface_destroyed")
    }

    private companion object {
        private const val TAG = "MPVView"
    }
}
