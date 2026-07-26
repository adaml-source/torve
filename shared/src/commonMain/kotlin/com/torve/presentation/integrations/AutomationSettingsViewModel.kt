package com.torve.presentation.integrations

import com.torve.data.integrations.AutomationAdminClient
import com.torve.data.integrations.AutomationConnectionResult
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationInstanceRole
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.normalizeAutomationServerUrl
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class AutomationSettingsUiState(
    val instances: List<AutomationInstance> = emptyList(),
    val editingId: String? = null,
    val serviceType: AutomationServiceType = AutomationServiceType.SONARR,
    val role: AutomationInstanceRole = AutomationInstanceRole.STANDARD,
    val name: String = "",
    val serverUrl: String = "",
    val apiKey: String = "",
    val isDefault: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class AutomationSettingsViewModel(
    private val repository: AutomationInstanceRepository,
    private val adminClient: AutomationAdminClient,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(AutomationSettingsUiState())
    val state: StateFlow<AutomationSettingsUiState> = _state.asStateFlow()

    init { reload() }

    fun selectService(type: AutomationServiceType) = _state.update {
        it.copy(serviceType = type, error = null, message = null)
    }

    fun selectRole(role: AutomationInstanceRole) = _state.update {
        it.copy(role = role, error = null, message = null)
    }

    fun updateName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun updateServerUrl(value: String) = _state.update { it.copy(serverUrl = value, error = null) }
    fun updateApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null) }
    fun setDefault(value: Boolean) = _state.update { it.copy(isDefault = value, error = null) }

    fun edit(instance: AutomationInstance) {
        _state.update {
            it.copy(
                editingId = instance.id,
                serviceType = instance.serviceType,
                role = instance.role,
                name = instance.name,
                serverUrl = instance.serverUrl,
                apiKey = "",
                isDefault = instance.isDefault,
                message = "Leave API key empty to keep the encrypted key",
                error = null,
            )
        }
    }

    fun cancelEdit() = _state.update { current -> freshDraft(current.instances) }

    fun saveAndTest() {
        if (_state.value.isBusy) return
        val draft = _state.value
        val normalizedUrl = normalizeAutomationServerUrl(draft.serverUrl)
        if (draft.name.isBlank() || normalizedUrl == null) {
            _state.update { it.copy(error = "Enter a name and an http(s) server URL") }
            return
        }
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, message = null) }
            val id = draft.editingId ?: buildInstanceId(draft.serviceType)
            val instance = AutomationInstance(
                id = id,
                serviceType = draft.serviceType,
                name = draft.name,
                serverUrl = normalizedUrl,
                role = draft.role,
                isDefault = draft.isDefault,
            )
            val existingInstance = draft.editingId?.let {
                draft.instances.firstOrNull { instance -> instance.id == it }
            }
            val serviceChanged = existingInstance?.serviceType != null &&
                existingInstance.serviceType != draft.serviceType
            val existingKey = existingInstance
                ?.takeUnless { serviceChanged }
                ?.let { runCatching { repository.apiKey(it) }.getOrNull() }
            val apiKey = draft.apiKey.trim().takeIf { it.isNotEmpty() } ?: existingKey
            if (serviceChanged && draft.apiKey.isBlank()) {
                _state.update {
                    it.copy(isBusy = false, error = "Enter a new API key when changing the service type")
                }
                return@launch
            }
            if (draft.serviceType != AutomationServiceType.TDARR && apiKey.isNullOrBlank()) {
                _state.update { it.copy(isBusy = false, error = "Enter the API key") }
                return@launch
            }

            when (val result = runCatching {
                adminClient.testConnection(instance, apiKey.orEmpty())
            }.getOrDefault(AutomationConnectionResult.Unreachable)) {
                is AutomationConnectionResult.Connected -> {
                    val instances = persist(instance, draft.apiKey.takeIf { it.isNotBlank() })
                        ?: return@launch
                    _state.value = freshDraft(
                        instances,
                        message = buildString {
                            append("Connected and saved")
                            result.version?.takeIf { it.isNotBlank() }?.let { append(" (v$it)") }
                        },
                    )
                }
                AutomationConnectionResult.Unsupported -> {
                    val instances = persist(instance, draft.apiKey.takeIf { it.isNotBlank() })
                        ?: return@launch
                    _state.value = freshDraft(
                        instances,
                        message = "Saved on this device; connection is not verified for this service",
                    )
                }
                AutomationConnectionResult.Unauthorized ->
                    _state.update { it.copy(isBusy = false, error = "API key was rejected; existing settings were kept") }
                AutomationConnectionResult.Unreachable ->
                    _state.update { it.copy(isBusy = false, error = "Server could not be reached; existing settings were kept") }
            }
        }
    }

    fun remove(instance: AutomationInstance) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, message = null) }
            runCatching {
                repository.remove(instance.id)
                repository.list()
            }.onSuccess { instances ->
                _state.value = freshDraft(instances, message = "${instance.name} removed")
                notifyProviderHealthRefresh()
            }.onFailure {
                _state.update { it.copy(isBusy = false, error = "Could not remove this connection") }
            }
        }
    }

    fun reload() {
        scope.launch {
            runCatching { repository.list() }
                .onSuccess { instances ->
                    _state.update { current -> current.copy(instances = instances) }
                }
                .onFailure {
                    _state.update { current -> current.copy(error = "Could not load saved connections") }
                }
        }
    }

    private suspend fun persist(instance: AutomationInstance, newApiKey: String?): List<AutomationInstance>? =
        runCatching {
            repository.save(instance, newApiKey)
            repository.list()
        }.onSuccess {
            notifyProviderHealthRefresh()
        }.getOrElse {
            _state.update { current ->
                current.copy(isBusy = false, error = "Could not save this connection; existing settings were kept")
            }
            null
        }

    private fun notifyProviderHealthRefresh() {
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
    }

    private fun freshDraft(
        instances: List<AutomationInstance>,
        message: String? = null,
    ) = AutomationSettingsUiState(instances = instances, message = message)

    private fun buildInstanceId(type: AutomationServiceType): String =
        "${type.name.lowercase()}_${Clock.System.now().toEpochMilliseconds()}"
}
