package com.torve.data.catalog

import com.torve.domain.model.CatalogGroup
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CatalogGroupRepository(private val prefs: PreferencesRepository, private val notifier: SettingsRefreshNotifier) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val _groups = MutableStateFlow<List<CatalogGroup>>(emptyList())
    val groups = _groups.asStateFlow()
    init { scope.launch { notifier.events.collect { refresh() } }; scope.launch { refresh() } }
    suspend fun refresh() {
        _groups.value = prefs.getString(KEY)?.let { runCatching { json.decodeFromString<List<CatalogGroup>>(it) }.getOrNull() }.orEmpty()
    }
    suspend fun save(group: CatalogGroup) = mutex.withLock {
        require(group.name.trim().isNotEmpty()) { "Enter a group name" }
        require(group.catalogIds.isNotEmpty()) { "Select at least one catalog" }
        refresh()
        val next = _groups.value.filterNot { it.id == group.id } + group.copy(name = group.name.trim().take(60), catalogIds = group.catalogIds.distinct())
        prefs.setString(KEY, json.encodeToString(next)); _groups.value = next
        notifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
    }
    suspend fun delete(id: String) = mutex.withLock {
        refresh()
        val next = _groups.value.filterNot { it.id == id }
        prefs.setString(KEY, json.encodeToString(next)); _groups.value = next
        notifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
    }
    companion object { const val KEY = "catalog_groups_v1" }
}
