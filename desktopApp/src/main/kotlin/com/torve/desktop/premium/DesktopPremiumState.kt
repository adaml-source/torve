package com.torve.desktop.premium

import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Compatibility holder for desktop account access.
 *
 * Torve no longer has paid desktop access gates. [hasPremium] remains
 * for older UI call sites and always means free product availability,
 * not subscription, entitlement, license, or donation state.
 */
class DesktopPremiumState(
    private val deviceApi: DeviceApi,
    private val authClient: AuthClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _hasPremium = MutableStateFlow(true)
    val hasPremium: StateFlow<Boolean> = _hasPremium.asStateFlow()

    private val _accessState = MutableStateFlow<AccessStateDto?>(null)
    val accessState: StateFlow<AccessStateDto?> = _accessState.asStateFlow()

    @Volatile
    private var pollJob: Job? = null

    fun refreshNow() {
        scope.launch {
            val token = runCatching { authClient.getValidAccessToken() }.getOrNull()
            if (token.isNullOrBlank()) {
                _hasPremium.value = true
                _accessState.value = null
                return@launch
            }
            _accessState.value = runCatching { deviceApi.getAccessState(token) }.getOrNull()
            _hasPremium.value = true
        }
    }

    fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refreshNow()
                delay(60_000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    @Deprecated("Desktop access is free; retained for source compatibility.")
    fun pollAggressivelyFor(windowSeconds: Int = 300) {
        _hasPremium.value = true
    }

    fun shutdown() {
        scope.cancel()
    }
}
