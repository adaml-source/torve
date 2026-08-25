package com.torve.android.tv.focus

/**
 * Optional rail destinations attach only while the rail owns focus. This keeps
 * asynchronous account/settings hydration from modifying the focus graph while
 * the user is navigating a content surface.
 */
internal fun deferredOptionalDestinationVisibility(
    configured: Boolean,
    currentlyVisible: Boolean,
    railOwnsFocus: Boolean,
): Boolean = when {
    !configured -> false
    currentlyVisible -> true
    railOwnsFocus -> true
    else -> false
}

