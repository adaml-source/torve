package com.torve.presentation.device

import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import com.torve.data.device.DeviceListDto
import com.torve.data.device.ManagedDeviceDto
import com.torve.data.device.resolvedDeviceBlockReason
import com.torve.data.device.resolvedHasPremiumEntitlement
import com.torve.data.device.resolvedIsDeviceActivated
import com.torve.data.device.resolvedUsablePremiumAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceGovernanceUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Access state
    val hasEntitlement: Boolean = false,
    val premiumAccess: Boolean = false,
    val reason: String = "",

    // Current device
    val currentDeviceId: String = "",
    val currentDeviceName: String = "",
    val currentDeviceActive: Boolean = false,

    // Device limit
    val activeDeviceCount: Int = 0,
    val maxActiveDevices: Int = 5,
    val capReached: Boolean = false,
    val swapsRemaining: Int = 3,
    val staleDevicesPruned: Int = 0,

    // Device list
    val devices: List<ManagedDeviceDto> = emptyList(),

    // Action states
    val isRemoving: Boolean = false,
    val isActivating: Boolean = false,
    val removeSuccess: Boolean = false,
    val activateSuccess: Boolean = false,

    // Show device limit reached screen
    val showDeviceLimitReached: Boolean = false,
)

class DeviceGovernanceViewModel(
    private val authClient: AuthClient,
    private val deviceApi: DeviceApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DeviceGovernanceUiState())
    val state: StateFlow<DeviceGovernanceUiState> = _state.asStateFlow()

    /**
     * Fetch access state from backend. Called on app startup.
     * Returns true if this device has premium access.
     */
    fun fetchAccessState() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val token = authClient.getValidAccessToken() ?: run {
                    _state.update { it.copy(isLoading = false, reason = "not_logged_in") }
                    return@launch
                }
                val access = deviceApi.getAccessState(token)
                if (access != null) {
                    applyAccessState(access)
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Failed to check access: ${e.message}") }
            }
        }
    }

    /**
     * Fetch the list of managed devices.
     */
    fun fetchDevices() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val token = authClient.getValidAccessToken() ?: run {
                    _state.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }
                val list = deviceApi.getDevices(token)
                _state.update {
                    it.copy(
                        isLoading = false,
                        devices = list.devices,
                        activeDeviceCount = list.active_count,
                        maxActiveDevices = list.max_active,
                        swapsRemaining = list.swaps_remaining,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Remove a device by ID. On success: optimistic local removal, then
     * sequential re-fetch from backend for accurate state.
     */
    fun removeDevice(deviceId: String) {
        scope.launch {
            _state.update { it.copy(isRemoving = true, error = null, removeSuccess = false) }
            try {
                val token = authClient.getValidAccessToken() ?: run {
                    _state.update { it.copy(isRemoving = false, error = "Not logged in") }
                    return@launch
                }
                val result = deviceApi.removeDevice(token, deviceId)

                if (!result.removed) {
                    _state.update {
                        it.copy(
                            isRemoving = false,
                            error = when (result.reason) {
                                "swap_limit_reached" -> "You've reached the device swap limit. Try again later."
                                "already_removed" -> "This device was already removed."
                                else -> "Could not remove device."
                            },
                            swapsRemaining = result.swaps_remaining,
                        )
                    }
                    return@launch
                }

                val selfRevoke = deviceId == _state.value.currentDeviceId

                // Optimistic local removal — device disappears immediately
                _state.update { current ->
                    current.copy(
                        isRemoving = false,
                        removeSuccess = true,
                        swapsRemaining = result.swaps_remaining,
                        devices = current.devices.filter { it.id != deviceId },
                        activeDeviceCount = (current.activeDeviceCount - 1).coerceAtLeast(0),
                        currentDeviceActive = if (selfRevoke) false else current.currentDeviceActive,
                        premiumAccess = if (selfRevoke) false else current.premiumAccess,
                    )
                }

                // Auto-activate current device after removal (skip for self-revoke)
                if (!selfRevoke) {
                    runCatching {
                        val activateToken = authClient.getValidAccessToken() ?: return@runCatching
                        val activateResult = deviceApi.activateCurrent(activateToken)
                        _state.update {
                            it.copy(
                                premiumAccess = activateResult.activated,
                                currentDeviceActive = activateResult.activated,
                                activeDeviceCount = activateResult.active_device_count,
                                swapsRemaining = activateResult.swaps_remaining,
                            )
                        }
                    }
                }

                // Re-fetch device list from backend for accurate data
                runCatching {
                    val refreshToken = authClient.getValidAccessToken() ?: return@runCatching
                    val list = deviceApi.getDevices(refreshToken)
                    _state.update {
                        it.copy(
                            devices = list.devices,
                            activeDeviceCount = list.active_count,
                            maxActiveDevices = list.max_active,
                            swapsRemaining = list.swaps_remaining,
                        )
                    }
                }
                fetchAccessState()
            } catch (e: Exception) {
                _state.update { it.copy(isRemoving = false, error = e.message) }
            }
        }
    }

    /**
     * Explicitly activate the current device.
     */
    fun activateCurrentDevice() {
        scope.launch {
            _state.update { it.copy(isActivating = true, error = null, activateSuccess = false) }
            try {
                val token = authClient.getValidAccessToken() ?: return@launch
                val result = deviceApi.activateCurrent(token)
                _state.update {
                    it.copy(
                        isActivating = false,
                        activateSuccess = result.activated,
                        premiumAccess = result.activated,
                        currentDeviceActive = result.activated,
                        activeDeviceCount = result.active_device_count,
                        swapsRemaining = result.swaps_remaining,
                        capReached = !result.activated && result.reason == "device_cap_reached",
                        showDeviceLimitReached = !result.activated && result.reason == "device_cap_reached",
                        reason = result.reason,
                    )
                }
                fetchAccessState()
            } catch (e: Exception) {
                _state.update { it.copy(isActivating = false, error = e.message) }
            }
        }
    }

    /**
     * Rename a device.
     */
    fun renameDevice(deviceId: String, newName: String) {
        scope.launch {
            try {
                val token = authClient.getValidAccessToken() ?: return@launch
                deviceApi.renameDevice(token, deviceId, newName)
                fetchDevices()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissDeviceLimitReached() {
        _state.update { it.copy(showDeviceLimitReached = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun applyAccessState(access: AccessStateDto) {
        val hasEntitlement = access.resolvedHasPremiumEntitlement()
        val isDeviceActivated = access.resolvedIsDeviceActivated()
        val premiumAccess = access.resolvedUsablePremiumAccess()
        val reason = access.resolvedDeviceBlockReason().orEmpty()
        _state.update {
            it.copy(
                isLoading = false,
                hasEntitlement = hasEntitlement,
                premiumAccess = premiumAccess,
                reason = reason,
                currentDeviceId = access.device?.id ?: it.currentDeviceId,
                currentDeviceName = access.device?.name ?: it.currentDeviceName,
                currentDeviceActive = isDeviceActivated,
                activeDeviceCount = access.device?.active_device_count ?: it.activeDeviceCount,
                maxActiveDevices = access.device?.max_active_devices ?: it.maxActiveDevices,
                capReached = access.device_limit?.cap_reached ?: it.capReached,
                swapsRemaining = access.device_limit?.swaps_remaining ?: it.swapsRemaining,
                staleDevicesPruned = access.device_limit?.stale_devices_pruned ?: it.staleDevicesPruned,
                // Pre-load active devices from access-state for immediate display
                devices = access.device_limit?.active_devices?.ifEmpty { it.devices } ?: it.devices,
                showDeviceLimitReached = hasEntitlement && !premiumAccess && reason == "device_cap_reached",
            )
        }
    }
}
