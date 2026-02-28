package com.streamvault.presentation.mdblist

import com.streamvault.data.mdblist.MdbListApi
import com.streamvault.data.mdblist.MdbListRepository
import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.model.MdbListInfo
import com.streamvault.domain.model.MdbListShelfConfig
import com.streamvault.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MdbListTab { POPULAR, SEARCH }

data class MdbListUiState(
    val apiKey: String = "",
    val savedLists: List<MdbListShelfConfig> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MdbListInfo> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val topLists: List<MdbListInfo> = emptyList(),
    val isLoadingTop: Boolean = false,
    val activeTab: MdbListTab = MdbListTab.POPULAR,
)

class MdbListViewModel(
    private val mdbListApi: MdbListApi,
    private val mdbListRepo: MdbListRepository,
    private val prefsRepo: PreferencesRepository,
    private val integrationSecretStore: IntegrationSecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(MdbListUiState())
    val state: StateFlow<MdbListUiState> = _state.asStateFlow()

    companion object {
        private const val KEY_MDBLIST_API_KEY = "mdblist_api_key"
    }

    init {
        loadSaved()
    }

    private fun loadSaved() {
        scope.launch {
            val apiKey = integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefsRepo.getString(KEY_MDBLIST_API_KEY)
                ?: ""
            val saved = mdbListRepo.getSavedLists()
            _state.update { it.copy(apiKey = apiKey, savedLists = saved) }
            if (apiKey.isNotBlank()) {
                loadTopLists()
            }
        }
    }

    /** Refresh API key from secure store (call after key is saved in settings). */
    fun refreshApiKey() {
        scope.launch {
            val apiKey = integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefsRepo.getString(KEY_MDBLIST_API_KEY)
                ?: ""
            _state.update { it.copy(apiKey = apiKey) }
            if (apiKey.isNotBlank() && _state.value.topLists.isEmpty()) {
                loadTopLists()
            }
        }
    }

    fun setActiveTab(tab: MdbListTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun search() {
        val query = _state.value.searchQuery.trim()
        val apiKey = _state.value.apiKey
        if (query.isBlank() || apiKey.isBlank()) return

        scope.launch {
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                val searchResults = mdbListApi.searchLists(query, apiKey)
                _state.update { it.copy(searchResults = searchResults, isSearching = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isSearching = false, error = e.message) }
            }
        }
    }

    fun addList(listId: Int, name: String) {
        val apiKey = _state.value.apiKey
        if (apiKey.isBlank()) return

        scope.launch {
            try {
                mdbListRepo.addList(listId, name, apiKey)
                _state.update { it.copy(savedLists = mdbListRepo.getSavedLists()) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun removeList(listId: Int) {
        scope.launch {
            mdbListRepo.removeList(listId)
            _state.update { it.copy(savedLists = mdbListRepo.getSavedLists()) }
        }
    }

    fun toggleList(listId: Int, enabled: Boolean) {
        scope.launch {
            mdbListRepo.toggleList(listId, enabled)
            _state.update { it.copy(savedLists = mdbListRepo.getSavedLists()) }
        }
    }

    fun loadTopLists() {
        val apiKey = _state.value.apiKey
        if (apiKey.isBlank()) return

        scope.launch {
            _state.update { it.copy(isLoadingTop = true) }
            try {
                val top = mdbListApi.getTopLists(apiKey, limit = 20)
                _state.update { it.copy(topLists = top, isLoadingTop = false) }
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingTop = false) }
            }
        }
    }
}
