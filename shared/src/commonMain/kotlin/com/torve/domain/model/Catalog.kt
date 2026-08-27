package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ShelfType {
    POSTER,
    LANDSCAPE,
    WIDE;
}

@Serializable
data class CatalogShelf(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
    val type: ShelfType = ShelfType.POSTER,
    /** Optional context item that owns this collection but is not one of its results. */
    val sourceItem: MediaItem? = null,
)
