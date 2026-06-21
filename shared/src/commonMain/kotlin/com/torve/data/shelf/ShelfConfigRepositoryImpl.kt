package com.torve.data.shelf

import com.torve.data.auth.UserIdProvider
import com.torve.db.TorveDatabase
import com.torve.domain.model.ShelfConfig
import com.torve.domain.repository.ShelfConfigRepository

class ShelfConfigRepositoryImpl(
    private val database: TorveDatabase,
    private val userIdProvider: UserIdProvider,
) : ShelfConfigRepository {

    override suspend fun getAllConfigs(): List<ShelfConfig> {
        return database.torveQueries.getAllShelfConfigs(userId = userIdProvider.currentUserId())
            .executeAsList().map {
                ShelfConfig(
                    shelfId = it.shelf_id,
                    isVisible = it.is_visible == 1L,
                    sortOrder = it.sort_order.toInt(),
                )
            }
    }

    override suspend fun getConfig(shelfId: String): ShelfConfig? {
        return database.torveQueries.getShelfConfig(
            userId = userIdProvider.currentUserId(),
            shelfId = shelfId,
        ).executeAsOneOrNull()?.let {
            ShelfConfig(
                shelfId = it.shelf_id,
                isVisible = it.is_visible == 1L,
                sortOrder = it.sort_order.toInt(),
            )
        }
    }

    override suspend fun upsertConfig(config: ShelfConfig) {
        database.torveQueries.upsertShelfConfig(
            user_id = userIdProvider.currentUserId(),
            shelf_id = config.shelfId,
            is_visible = if (config.isVisible) 1L else 0L,
            sort_order = config.sortOrder.toLong(),
        )
    }
}
