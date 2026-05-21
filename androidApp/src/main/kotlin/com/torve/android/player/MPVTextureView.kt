package com.torve.android.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * TextureView variant for TV mpv playback.
 * Fire TV handles Compose + TextureView more reliably than SurfaceView for libmpv video.
 */
class MPVTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    var onSurfaceAttachedStateChanged: ((Boolean) -> Unit)? = null

    private var boundSurface: Surface? = null
    private var lastBindingToken: Int? = null
    private var surfaceAttached = false
    private var pendingBindingToken: Int? = null
    private var pendingBindingReason: String? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun bindSurface(bindingToken: Int, reason: String) {
        pendingBindingToken = bindingToken
        pendingBindingReason = reason
        val texture = surfaceTexture ?: run {
            Log.d(TAG, "Deferring mpv TextureView bind until surface is available reason=$reason bindId=$bindingToken")
            return
        }
        val surface = boundSurface?.takeIf { it.isValid } ?: Surface(texture).also { boundSurface = it }
        val needsRebind = !surfaceAttached || lastBindingToken != bindingToken
        if (!needsRebind) {
            onSurfaceAttachedStateChanged?.invoke(true)
            return
        }

        if (surfaceAttached) {
            runCatching { MPVLib.detachSurface() }
        }
        Log.d(TAG, "Binding mpv TextureView surface reason=$reason bindId=$bindingToken size=${width}x${height}")
        MPVLib.attachSurface(surface)
        surfaceAttached = true
        lastBindingToken = bindingToken
        onSurfaceAttachedStateChanged?.invoke(true)
    }

    fun releaseSurface(reason: String) {
        if (surfaceAttached) {
            Log.d(TAG, "Releasing mpv TextureView surface reason=$reason bindId=${lastBindingToken ?: -1}")
            runCatching { MPVLib.detachSurface() }
        }
        surfaceAttached = false
        lastBindingToken = null
        boundSurface?.release()
        boundSurface = null
        onSurfaceAttachedStateChanged?.invoke(false)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "Texture available ${width}x$height")
        val bindingToken = pendingBindingToken
        if (bindingToken != null) {
            bindSurface(bindingToken, pendingBindingReason ?: "texture_available")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "Texture resized ${width}x$height")
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releaseSurface("texture_destroyed")
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private companion object {
        private const val TAG = "MPVTextureView"
    }
}
