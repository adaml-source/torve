package com.torve.android.tv.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** Expanded rails position focus in their measure pass. Native bring-into-view
 * must not start a competing scroll using the previous poster's geometry. */
@OptIn(ExperimentalFoundationApi::class)
internal object TvAnchoredPreviewBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
