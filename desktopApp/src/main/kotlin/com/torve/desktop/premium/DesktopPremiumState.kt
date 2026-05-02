package com.torve.desktop.premium

import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import com.torve.data.device.resolvedUsablePremiumAccess
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
 * Process-wide premium-access state holder for the desktop app.
 *
 * The single source of truth is `GET /me/access-state` from the
 * backend; this class polls it at the cadences spec'd in the
 * Issue 2 brief:
 *  - At sign-in / app start ([refreshNow])
 *  - On window focus regained ([refreshNow])
 *  - After a successful purchase verification ([refreshNow])
 *  - Every 60s while we're signed in (the polling job)
 *
 * Premium gates throughout the UI subscribe to [hasPremium] (a
 * StateFlow<Boolean>) - flipping false routes the action to the
 * upgrade screen rather than executing.
 */
class DesktopPremiumState(
    private val deviceApi: DeviceApi,
    private val authClient: AuthClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _hasPremium = MutableStateFlow(false)
    val hasPremium: StateFlow<Boolean> = _hasPremium.asStateFlow()

    private val _accessState = MutableStateFlow<AccessStateDto?>(null)
    /** Full access-state response so UI can show device-block-reason hints, etc. */
    val accessState: StateFlow<AccessStateDto?> = _accessState.asStateFlow()

    @Volatile
    private var pollJob: Job? = null

    @Volatile
    private var aggressiveJob: Job? = null

    /**
     * Force a refresh now - call after sign-in completes, after purchase
     * verification, or when the user manually requests a re-check.
     * No-op when no access token is available (signed out).
     */
    fun refreshNow() {
        scope.launch {
            val token = runCatching { authClient.getValidAccessToken() }.getOrNull()
            if (token.isNullOrBlank()) {
                _hasPremium.value = false
                _accessState.value = null
                return@launch
            }
            val state = runCatching { deviceApi.getAccessState(token) }.getOrNull()
            _accessState.value = state
            // Use `resolvedUsablePremiumAccess` (entitlement + device
            // activated) rather than just has_premium_access so an
            // entitlement-but-not-activated user still sees the
            // device-limit screen instead of full access.
            _hasPremium.value = state?.resolvedUsablePremiumAccess() ?: false
            println("PREMIUM_STATE refresh hasPremium=${_hasPremium.value} accessTier=${state?.access_tier}")
        }
    }

    /**
     * Begin the 60s polling cadence. Idempotent; cancels and replaces
     * any existing job. Stop with [stopPolling] on sign-out.
     */
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
        aggressiveJob?.cancel()
        aggressiveJob = null
    }

    /**
     * Poll every 3s for the next [windowSeconds] seconds, then stop.
     * Used right after the user clicks Upgrade so the moment the
     * backend records the entitlement, gating clears without waiting
     * up to 60s for the regular poll. Idempotent - replaces any prior
     * aggressive window.
     */
    fun pollAggressivelyFor(windowSeconds: Int = 300) {
        aggressiveJob?.cancel()
        aggressiveJob = scope.launch {
            val deadline = System.currentTimeMillis() + windowSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                refreshNow()
                if (_hasPremium.value) {
                    println("PREMIUM_STATE aggressive poll → premium granted, stopping")
                    break
                }
                delay(3_000L)
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
