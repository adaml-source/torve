package com.streamvault.domain.repository

import com.streamvault.domain.model.ShelfConfig

interface ShelfConfigRepository {
    suspend fun getAllConfigs(): List<ShelfConfig>
    suspend fun getConfig(shelfId: String): ShelfConfig?
    suspend fun upsertConfig(config: ShelfConfig)
}
