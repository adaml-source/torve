package com.torve.presentation.mdblist

import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.MdbListRepository
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MdbListInfo
import com.torve.domain.model.MdbListShelfConfig
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import com.torve.data.auth.withPersonalDataScope

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
    val ownLists: List<MdbListInfo> = emptyList(),
    val isSaving: Boolean = false,
    val activeTab: MdbListTab = MdbListTab.POPULAR,
)

class MdbListViewModel(
    private val mdbListApi: MdbListApi,
    private val mdbListRepo: MdbListRepository,
    private val prefsRepo: PreferencesRepository,
    private val integrationSecretStore: IntegrationSecretStore,
    private val ids: com.torve.data.auth.UserIdProvider,
    private val refreshNotifier: com.torve.presentation.settings.SettingsRefreshNotifier,
) {
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(MdbListUiState())
    val state: StateFlow<MdbListUiState> = _state.asStateFlow()

    companion object {
        private const val KEY_MDBLIST_API_KEY = "mdblist_api_key"
    }

    init {
        loadSaved()
    }

    fun reload() { generation++; _state.value = MdbListUiState(); loadSaved() }
    fun close() { scope.cancel() }

    fun saveApiKey(value: String) {
        if (value.isBlank()) return
        generation++; val request = generation; val owner = ids.currentDataUserIdOrNull()
        _state.update { it.copy(isSaving = true, error = null) }
        scope.launch {
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@launch
            try { ids.withPersonalDataScope {
            integrationSecretStore.put(IntegrationSecretKey.MDBLIST_API_KEY, value.trim())
            prefsRepo.remove(KEY_MDBLIST_API_KEY)
            val saved = mdbListRepo.getSavedLists()
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@withPersonalDataScope
            _state.value = MdbListUiState(apiKey = value.trim(), savedLists = saved)
            refreshNotifier.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
            if (value.isNotBlank()) loadTopLists()
        } } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(isSaving = false, error = "Could not save the MDBList connection. Try again.") }
        } }
    }

    private fun loadSaved() {
        val request = generation; val owner = ids.currentDataUserIdOrNull()
        scope.launch {
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@launch
            try { ids.withPersonalDataScope {
            val apiKey = integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefsRepo.getString(KEY_MDBLIST_API_KEY)
                ?: ""
            val saved = mdbListRepo.getSavedLists()
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@withPersonalDataScope
            _state.update { it.copy(apiKey = apiKey, savedLists = saved) }
            if (apiKey.isNotBlank()) {
                loadTopLists()
            }
        } } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(error = "Could not load the MDBList connection. Try again.") }
        } }
    }

    /** Reloading also discards results belonging to the previous service key. */
    fun refreshApiKey() = reload()

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

        val request = generation; val owner = ids.currentDataUserIdOrNull()
        scope.launch {
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@launch
            ids.withPersonalDataScope {
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                val searchResults = mdbListApi.searchLists(query, apiKey)
                if (request == generation && owner == ids.currentDataUserIdOrNull() && _state.value.apiKey == apiKey && _state.value.searchQuery.trim() == query) _state.update { it.copy(searchResults = searchResults, isSearching = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(isSearching = false, error = com.torve.presentation.error.UserFacingError.SEARCH_FAILED.messageKey) }
            }
        } }
    }

    fun addList(listId: Int, name: String) {
        val apiKey = _state.value.apiKey
        if (apiKey.isNotBlank()) mutate { mdbListRepo.addList(listId, name, apiKey) }
    }

    fun removeList(listId: Int) = mutate { mdbListRepo.removeList(listId) }

    fun toggleList(listId: Int, enabled: Boolean) = mutate { mdbListRepo.toggleList(listId, enabled) }

    private fun mutate(action: suspend () -> Unit) {
        if (_state.value.isSaving) return
        val request = generation; val owner = ids.currentDataUserIdOrNull()
        _state.update { it.copy(isSaving = true, error = null) }
        scope.launch {
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@launch
            ids.withPersonalDataScope {
            try {
                action()
                val saved = mdbListRepo.getSavedLists()
                if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(savedLists = saved) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(error = "Could not update this MDBList. Try again.") }
            } finally { if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(isSaving = false) } }
        } }
    }

    fun loadTopLists() {
        val apiKey = _state.value.apiKey
        if (apiKey.isBlank()) return

        val request = generation; val owner = ids.currentDataUserIdOrNull()
        scope.launch {
            if (request != generation || owner != ids.currentDataUserIdOrNull()) return@launch
            ids.withPersonalDataScope {
            _state.update { it.copy(isLoadingTop = true) }
            try {
                val own = mdbListApi.getUserLists(apiKey)
                if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(ownLists = own) }
                val top = mdbListApi.getTopLists(apiKey, limit = 20)
                if (request == generation && owner == ids.currentDataUserIdOrNull() && _state.value.apiKey == apiKey) _state.update { it.copy(ownLists = own, topLists = top, isLoadingTop = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (request == generation && owner == ids.currentDataUserIdOrNull()) _state.update { it.copy(isLoadingTop = false, error = "Could not load MDBList lists. Check your API key and try again.") }
            }
        } }
    }
}
