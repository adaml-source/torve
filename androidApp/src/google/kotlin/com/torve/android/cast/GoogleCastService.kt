package com.torve.android.cast

import android.content.Context
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.torve.android.player.CastManager

class GoogleCastService(private val context: Context) : CastService {

    private val castManager: CastManager? = try {
        CastManager(context)
    } catch (_: Throwable) {
        null
    }

    override val isAvailable: Boolean = try {
        CastContext.getSharedInstance(context)
        true
    } catch (_: Throwable) {
        false
    }

    override val isCasting: Boolean get() = castManager?.isCasting == true

    override fun requestCast(url: String, title: String, posterUrl: String?) {
        castManager?.requestCast(url, title, posterUrl)
    }

    override fun showCastDialog() {
        try {
            val castContext = CastContext.getSharedInstance(context)
            val routeSelector = castContext.mergedSelector
            if (routeSelector != null) {
                val mediaRouteButton = MediaRouteButton(context)
                CastButtonFactory.setUpMediaRouteButton(context, mediaRouteButton)
                mediaRouteButton.performClick()
            }
        } catch (_: Throwable) { }
    }

    override fun release() {
        castManager?.release()
    }
}
