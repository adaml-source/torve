package com.torve.data.mdblist

import com.torve.domain.model.MdbListItem
import com.torve.domain.model.MdbListShelfConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.toMediaItem
import com.torve.domain.repository.PreferencesRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MdbListRepository(
    private val api: MdbListApi,
    private val prefsRepo: PreferencesRepository,
) {
    companion object {
        private const val KEY_SHELVES = "mdblist_shelves"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getSavedLists(): List<MdbListShelfConfig> {
        val raw = prefsRepo.getString(KEY_SHELVES) ?: return emptyList()
        return try {
            json.decodeFromString<List<MdbListShelfConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addList(listId: Int, name: String, apiKey: String): MdbListShelfConfig {
        val current = getSavedLists().toMutableList()
        val existing = current.find { it.listId == listId }
        if (existing != null) return existing

        val info = try { api.getListInfo(listId, apiKey) } catch (_: Exception) { null }
        val displayName = info?.name?.ifBlank { name } ?: name
        val maxOrder = current.maxOfOrNull { it.order } ?: -1
        val config = MdbListShelfConfig(
            listId = listId,
            name = displayName,
            enabled = true,
            order = maxOrder + 1,
        )
        current.add(config)
        saveLists(current)
        return config
    }

    suspend fun removeList(listId: Int) {
        val current = getSavedLists().toMutableList()
        current.removeAll { it.listId == listId }
        saveLists(current)
    }

    suspend fun toggleList(listId: Int, enabled: Boolean) {
        val current = getSavedLists().toMutableList()
        val idx = current.indexOfFirst { it.listId == listId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            saveLists(current)
        }
    }

    suspend fun fetchListContent(listId: Int, apiKey: String, limit: Int = 20): List<MediaItem> {
        val items: List<MdbListItem> = api.getListItems(listId, apiKey, limit = limit)
        return items.map { it.toMediaItem() }
    }

    private suspend fun saveLists(lists: List<MdbListShelfConfig>) {
        prefsRepo.setString(KEY_SHELVES, json.encodeToString(lists))
    }
}
