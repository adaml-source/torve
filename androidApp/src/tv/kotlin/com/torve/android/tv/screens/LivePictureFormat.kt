package com.torve.android.tv.screens

import androidx.media3.ui.AspectRatioFrameLayout

internal data class LivePictureRenderConfiguration(
    val containerAspectRatio: Float?,
    val exoResizeMode: Int,
    val mpvAspectRatioOverride: Float?,
    val mpvPanscan: Float,
)

private fun forcedLivePictureRatio(ratio: Float) = LivePictureRenderConfiguration(
    containerAspectRatio = ratio,
    // FILL deliberately applies the requested presentation ratio inside the
    // explicitly sized container. FIT would preserve the source ratio instead.
    exoResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
    mpvAspectRatioOverride = ratio,
    mpvPanscan = 0f,
)

/** One authoritative mapping from the user-visible mode to both live player renderers. */
internal enum class LivePictureFormat(
    val key: String,
    val label: String,
    val renderConfiguration: LivePictureRenderConfiguration,
) {
    SOURCE(
        key = "source",
        label = "Source",
        renderConfiguration = LivePictureRenderConfiguration(
            containerAspectRatio = null,
            exoResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            mpvAspectRatioOverride = null,
            mpvPanscan = 0f,
        ),
    ),
    FILL(
        key = "fullscreen",
        label = "Fill",
        renderConfiguration = LivePictureRenderConfiguration(
            containerAspectRatio = null,
            exoResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            mpvAspectRatioOverride = null,
            mpvPanscan = 1f,
        ),
    ),
    RATIO_16_9(
        key = "16_9",
        label = "16:9",
        renderConfiguration = forcedLivePictureRatio(16f / 9f),
    ),
    RATIO_4_3(
        key = "4_3",
        label = "4:3",
        renderConfiguration = forcedLivePictureRatio(4f / 3f),
    ),
    RATIO_21_9(
        key = "21_9",
        label = "21:9",
        renderConfiguration = forcedLivePictureRatio(21f / 9f),
    ),
    ;

    companion object {
        fun fromKey(key: String): LivePictureFormat = when (key) {
            // The removed Original option was byte-for-byte identical to Source.
            "original" -> SOURCE
            else -> entries.firstOrNull { it.key == key } ?: SOURCE
        }

    }
}
