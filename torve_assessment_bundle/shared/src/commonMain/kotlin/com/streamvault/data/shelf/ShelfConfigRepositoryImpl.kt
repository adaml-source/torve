package com.streamvault.data.shelf

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.ShelfConfig
import com.streamvault.domain.repository.ShelfConfigRepository

class ShelfConfigRepositoryImpl(
    private val database: StreamVaultDatabase,
) : ShelfConfigRepository {

    override suspend fun getAllConfigs(): List<ShelfConfig> {
        return database.streamVaultQueries.getAllShelfConfigs().executeAsList().map {
            ShelfConfig(
                shelfId = it.shelf_id,
                isVisible = it.is_visible == 1L,
                sortOrder = it.sort_order.toInt(),
            )
        }
    }

    override suspend fun getConfig(shelfId: String): ShelfConfig? {
        return database.streamVaultQueries.getShelfConfig(shelfId).executeAsOneOrNull()?.let {
            ShelfConfig(
                shelfId = it.shelf_id,
                isVisible = it.is_visible == 1L,
                sortOrder = it.sort_order.toInt(),
            )
        }
    }

    override suspend fun upsertConfig(config: ShelfConfig) {
        database.streamVaultQueries.upsertShelfConfig(
            shelf_id = config.shelfId,
            is_visible = if (config.isVisible) 1L else 0L,
            sort_order = config.sortOrder.toLong(),
        )
    }
}
