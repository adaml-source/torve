package com.torve.domain.model

data class ShelfConfig(
    val shelfId: String,
    val isVisible: Boolean = true,
    val sortOrder: Int = 0,
)
