package com.torve.domain.repository

import com.torve.domain.model.ShelfConfig

interface ShelfConfigRepository {
    suspend fun getAllConfigs(): List<ShelfConfig>
    suspend fun getConfig(shelfId: String): ShelfConfig?
    suspend fun upsertConfig(config: ShelfConfig)
}
