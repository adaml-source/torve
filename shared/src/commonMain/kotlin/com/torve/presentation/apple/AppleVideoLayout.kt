package com.torve.presentation.apple

data class AppleVideoRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** Picture-format overrides change display proportions; only Fill may crop. */
object AppleVideoLayout {
    fun fit(viewWidth: Double, viewHeight: Double, aspectRatio: Double): AppleVideoRect {
        val width = viewWidth.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val height = viewHeight.takeIf { it.isFinite() && it > 0 } ?: 0.0
        if (!aspectRatio.isFinite() || aspectRatio <= 0 || width == 0.0 || height == 0.0) {
            return AppleVideoRect(0.0, 0.0, width, height)
        }
        val fittedWidth = minOf(width, height * aspectRatio)
        val fittedHeight = fittedWidth / aspectRatio
        return AppleVideoRect((width - fittedWidth) / 2, (height - fittedHeight) / 2, fittedWidth, fittedHeight)
    }
}
