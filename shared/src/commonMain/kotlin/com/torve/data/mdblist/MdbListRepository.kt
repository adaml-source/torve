package com.torve.data.mdblist

import com.torve.domain.model.MdbListItem
import com.torve.domain.model.MdbListShelfConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.home.HomeLayoutSourceSelector
import com.torve.domain.model.toMediaItem
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MdbListRepository(
    private val api: MdbListApi,
    private val prefsRepo: PreferencesRepository,
    private val notifier: com.torve.presentation.settings.SettingsRefreshNotifier? = null,
) {
    companion object {
        private const val KEY_SHELVES = "mdblist_shelves"
    }

    private val writeLock = Mutex()
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

    suspend fun addList(listId: Int, name: String, apiKey: String): MdbListShelfConfig = writeLock.withLock {
        val current = getSavedLists().toMutableList()
        val existing = current.find { it.listId == listId }
        if (existing != null) return@withLock existing

        require(listId > 0)
        val info = api.getListInfo(listId, apiKey)
        val displayName = info.name.ifBlank { name }
        val maxOrder = current.maxOfOrNull { it.order } ?: -1
        val config = MdbListShelfConfig(
            listId = listId,
            name = displayName,
            enabled = true,
            order = maxOrder + 1,
        )
        current.add(config)
        enableHomeSection()
        saveLists(current)
        config
    }

    suspend fun removeList(listId: Int) = writeLock.withLock {
        val current = getSavedLists().toMutableList()
        current.removeAll { it.listId == listId }
        saveLists(current)
    }

    suspend fun toggleList(listId: Int, enabled: Boolean) = writeLock.withLock {
        val current = getSavedLists().toMutableList()
        val idx = current.indexOfFirst { it.listId == listId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            if (enabled) enableHomeSection()
            saveLists(current)
        }
    }

    suspend fun fetchListContent(listId: Int, apiKey: String, limit: Int = 20): List<MediaItem> {
        val items: List<MdbListItem> = api.getListItems(listId, apiKey, limit = limit)
        return items.map { it.toMediaItem() }
    }

    private suspend fun saveLists(lists: List<MdbListShelfConfig>) {
        prefsRepo.setString(KEY_SHELVES, json.encodeToString(lists))
        notifier?.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
    }

    /** An explicit Add/enable action must also make the containing Home section visible. */
    private suspend fun enableHomeSection() {
        val key = HomeLayoutSourceSelector(prefsRepo).sectionConfigsKey()
        val raw = prefsRepo.getString(key)
        val configs = raw?.let { json.decodeFromString<List<HomeSectionConfig>>(it) }.orEmpty()
        val section = HomeSection.MDBLIST_SHELVES
        val existing = configs.firstOrNull { it.section == section }
        if (existing?.enabled == true) return
        val next = if (existing == null) configs + HomeSectionConfig(section, true, section.defaultOrder)
            else configs.map { if (it.section == section) it.copy(enabled = true) else it }
        prefsRepo.setString(key, json.encodeToString(next))
    }
}
