package com.torve.android.ui.components

import com.torve.domain.model.MediaItem

fun mediaItemLazyKey(item: MediaItem, index: Int): String {
    val stableId = item.tmdbId?.toString().takeUnless { it.isNullOrBlank() } ?: item.id
    return "${stableId}_${item.type}_$index"
}
