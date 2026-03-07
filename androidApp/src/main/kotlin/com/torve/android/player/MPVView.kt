package com.torve.android.player

import android.content.Context
import android.util.AttributeSet
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

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        MPVLib.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // MPV handles resize internally via property observers
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MPVLib.detachSurface()
    }
}
