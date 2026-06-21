package com.torve.presentation.device

import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import com.torve.data.device.DeviceActivateDto
import com.torve.data.device.DeviceListDto
import com.torve.data.device.ManagedDeviceDto
import com.torve.data.device.resolvedActiveDeviceCount
import com.torve.data.device.resolvedDeviceCapOverride
import com.torve.data.device.resolvedDeviceBlockReason
import com.torve.data.device.resolvedHasPremiumEntitlement
import com.torve.data.device.resolvedIsDeviceActivated
import com.torve.data.device.resolvedDeviceLimit
import com.torve.data.device.resolvedDeviceLimitActiveDevices
import com.torve.data.device.resolvedDeviceLimitCapReached
import com.torve.data.device.resolvedStaleDevicesPruned
import com.torve.data.device.resolvedSwapsRemaining
import com.torve.data.device.resolvedUsablePremiumAccess
import com.torve.presentation.error.UserFacingError
import com.torve.presentation.error.backendReasonToUserFacingError
import com.torve.presentation.error.toUserFacingError
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
    /** User-facing error key from [UserFacingError.messageKey]. Resolve via string resources. */
    val errorKey: String? = null,

    // Access state
    val hasEntitlement: Boolean = false,
    val premiumAccess: Boolean = false,
    val reason: String = "",
    val accessStateLoaded: Boolean = false,

    // Current device
    val currentDeviceId: String = "",
    val currentDeviceName: String = "",
    val currentDeviceActive: Boolean = false,

    // Device limit
    val activeDeviceCount: Int = 0,
    val activeDeviceCountKnown: Boolean = false,
    val maxActiveDevices: Int = 0,
    val deviceLimitKnown: Boolean = false,
    val deviceCapOverride: Int? = null,
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
) {
    val isAtOrOverDeviceLimit: Boolean
        get() = activeDeviceCountKnown && deviceLimitKnown && activeDeviceCount >= maxActiveDevices

    val effectiveCapReached: Boolean
        get() = capReached || isAtOrOverDeviceLimit

    val deviceUsageText: String
        get() = if (activeDeviceCountKnown && deviceLimitKnown) {
            "$activeDeviceCount / $maxActiveDevices"
        } else {
            "Loading..."
        }

    val deviceLimitDebugText: String
        get() = if (deviceLimitKnown) maxActiveDevices.toString() else "unknown"

    val deviceCapOverrideDebugText: String
        get() = deviceCapOverride?.toString() ?: "null"
}

class DeviceGovernanceViewModel(
    private val authClient: AuthClient,
    private val deviceApi: DeviceApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DeviceGovernanceUiState())
    val state: StateFlow<DeviceGovernanceUiState> = _state.asStateFlow()

    /**
     * Fetch access state from backend. Called on app startup.
     * Returns true if this device has account access.
     */
    fun fetchAccessState() {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    errorKey = null,
                    accessStateLoaded = false,
                    activeDeviceCountKnown = false,
                    activeDeviceCount = 0,
                    deviceLimitKnown = false,
                    maxActiveDevices = 0,
                    deviceCapOverride = null,
                    capReached = false,
                )
            }
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
                _state.update { it.copy(isLoading = false, errorKey = e.toUserFacingError().messageKey) }
            }
        }
    }

    /**
     * Fetch the list of managed devices.
     */
    fun fetchDevices() {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    errorKey = null,
                    accessStateLoaded = false,
                    activeDeviceCountKnown = false,
                    activeDeviceCount = 0,
                    deviceLimitKnown = false,
                    maxActiveDevices = 0,
                    deviceCapOverride = null,
                    capReached = false,
                )
            }
            try {
                val token = authClient.getValidAccessToken() ?: run {
                    _state.update { it.copy(isLoading = false, errorKey = UserFacingError.NOT_LOGGED_IN.messageKey) }
                    return@launch
                }
                val list = deviceApi.getDevices(token)
                applyDeviceList(list)
                val access = deviceApi.getAccessState(token)
                if (access != null) {
                    applyAccessState(access)
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorKey = UserFacingError.DEVICE_FETCH_FAILED.messageKey) }
            }
        }
    }

    /**
     * Remove a device by ID. On success: optimistic local removal, then
     * sequential re-fetch from backend for accurate state.
     */
    fun removeDevice(deviceId: String) {
        scope.launch {
            _state.update { it.copy(isRemoving = true, errorKey = null, removeSuccess = false) }
            try {
                val token = authClient.getValidAccessToken() ?: run {
                    _state.update { it.copy(isRemoving = false, errorKey = UserFacingError.NOT_LOGGED_IN.messageKey) }
                    return@launch
                }
                val result = deviceApi.removeDevice(token, deviceId)

                if (!result.removed) {
                    _state.update {
                        it.copy(
                            isRemoving = false,
                            errorKey = backendReasonToUserFacingError(result.reason).messageKey,
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
                        activeDeviceCount = if (current.activeDeviceCountKnown) {
                            (current.activeDeviceCount - 1).coerceAtLeast(0)
                        } else {
                            current.activeDeviceCount
                        },
                        currentDeviceActive = if (selfRevoke) false else current.currentDeviceActive,
                        premiumAccess = if (selfRevoke) false else current.premiumAccess,
                    )
                }

                // Auto-activate current device after removal (skip for self-revoke)
                if (!selfRevoke) {
                    runCatching {
                        val activateToken = authClient.getValidAccessToken() ?: return@runCatching
                        val activateResult = deviceApi.activateCurrent(activateToken)
                        val activateLimit = activateResult.resolvedDeviceLimit()
                        _state.update {
                            it.copy(
                                premiumAccess = activateResult.activated,
                                currentDeviceActive = activateResult.activated,
                                activeDeviceCount = activateResult.active_device_count,
                                activeDeviceCountKnown = true,
                                maxActiveDevices = activateLimit ?: it.maxActiveDevices,
                                deviceLimitKnown = activateLimit != null || it.deviceLimitKnown,
                                deviceCapOverride = activateResult.device_cap_override ?: it.deviceCapOverride,
                                swapsRemaining = activateResult.swaps_remaining,
                            )
                        }
                    }
                }

                // Re-fetch device list from backend for accurate data
                runCatching {
                    val refreshToken = authClient.getValidAccessToken() ?: return@runCatching
                    val list = deviceApi.getDevices(refreshToken)
                    applyDeviceList(list)
                }
                fetchAccessState()
            } catch (e: Exception) {
                _state.update { it.copy(isRemoving = false, errorKey = UserFacingError.DEVICE_REMOVE_FAILED.messageKey) }
            }
        }
    }

    /**
     * Explicitly activate the current device.
     */
    fun activateCurrentDevice() {
        scope.launch {
            _state.update { it.copy(isActivating = true, errorKey = null, activateSuccess = false) }
            try {
                val token = authClient.getValidAccessToken() ?: return@launch
                val result = deviceApi.activateCurrent(token)
                val resultLimit = result.resolvedDeviceLimit()
                _state.update {
                    it.copy(
                        isActivating = false,
                        activateSuccess = result.activated,
                        premiumAccess = result.activated,
                        currentDeviceActive = result.activated,
                        activeDeviceCount = result.active_device_count,
                        activeDeviceCountKnown = true,
                        maxActiveDevices = resultLimit ?: it.maxActiveDevices,
                        deviceLimitKnown = resultLimit != null || it.deviceLimitKnown,
                        deviceCapOverride = result.device_cap_override ?: it.deviceCapOverride,
                        swapsRemaining = result.swaps_remaining,
                        capReached = !result.activated && result.reason == "device_cap_reached",
                        showDeviceLimitReached = !result.activated && result.reason == "device_cap_reached",
                        reason = result.reason,
                    )
                }
                fetchAccessState()
            } catch (e: Exception) {
                _state.update { it.copy(isActivating = false, errorKey = UserFacingError.DEVICE_ACTIVATE_FAILED.messageKey) }
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
                _state.update { it.copy(errorKey = UserFacingError.DEVICE_RENAME_FAILED.messageKey) }
            }
        }
    }

    fun dismissDeviceLimitReached() {
        _state.update { it.copy(showDeviceLimitReached = false) }
    }

    fun clearError() {
        _state.update { it.copy(errorKey = null) }
    }

    private fun applyAccessState(access: AccessStateDto) {
        val hasEntitlement = access.resolvedHasPremiumEntitlement()
        val isDeviceActivated = access.resolvedIsDeviceActivated()
        val premiumAccess = access.resolvedUsablePremiumAccess()
        val reason = access.resolvedDeviceBlockReason().orEmpty()
        val activeCount = access.resolvedActiveDeviceCount()
        val deviceLimit = access.resolvedDeviceLimit()
        val capReached = access.resolvedDeviceLimitCapReached()
        val activeDevices = access.resolvedDeviceLimitActiveDevices()
        _state.update {
            it.copy(
                isLoading = false,
                hasEntitlement = hasEntitlement,
                premiumAccess = premiumAccess,
                reason = reason,
                accessStateLoaded = true,
                currentDeviceId = access.device?.id ?: it.currentDeviceId,
                currentDeviceName = access.device?.name ?: it.currentDeviceName,
                currentDeviceActive = isDeviceActivated,
                activeDeviceCount = activeCount ?: it.activeDeviceCount,
                activeDeviceCountKnown = activeCount != null || it.activeDeviceCountKnown,
                maxActiveDevices = deviceLimit ?: it.maxActiveDevices,
                deviceLimitKnown = deviceLimit != null || it.deviceLimitKnown,
                deviceCapOverride = access.resolvedDeviceCapOverride(),
                capReached = capReached ?: (reason == "device_cap_reached"),
                swapsRemaining = access.resolvedSwapsRemaining() ?: it.swapsRemaining,
                staleDevicesPruned = access.resolvedStaleDevicesPruned() ?: it.staleDevicesPruned,
                // Pre-load active devices from access-state for immediate display
                devices = activeDevices.ifEmpty { it.devices },
                showDeviceLimitReached = hasEntitlement && !premiumAccess && reason == "device_cap_reached",
            )
        }
    }

    private fun applyDeviceList(list: DeviceListDto) {
        val listLimit = list.max_active.takeIf { it > 0 }
        _state.update { current ->
            val keepAccessStateCount = current.accessStateLoaded && current.activeDeviceCountKnown
            val activeCount = if (keepAccessStateCount) current.activeDeviceCount else list.active_count
            val limit = if (current.deviceLimitKnown) current.maxActiveDevices else listLimit ?: current.maxActiveDevices
            current.copy(
                isLoading = false,
                devices = list.devices,
                activeDeviceCount = activeCount,
                activeDeviceCountKnown = true,
                maxActiveDevices = limit,
                deviceLimitKnown = current.deviceLimitKnown || listLimit != null,
                swapsRemaining = list.swaps_remaining,
            )
        }
    }
}

private fun DeviceActivateDto.resolvedDeviceLimit(): Int? =
    device_limit?.takeIf { it > 0 } ?: max_devices?.takeIf { it > 0 }
