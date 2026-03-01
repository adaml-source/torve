package com.streamvault.android.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.streamvault.android.sync.model.SyncAuthResponse
import com.streamvault.android.sync.model.SyncInboundEvent
import com.streamvault.android.sync.model.SyncDeviceDto
import com.streamvault.android.sync.model.SyncDeviceRegistration
import com.streamvault.android.sync.model.SyncLoginRequest
import com.streamvault.android.sync.model.SyncPairingCodeRequest
import com.streamvault.android.sync.model.SyncPlaybackIntentPayload
import com.streamvault.android.sync.model.SyncPlaybackIntentRequest
import com.streamvault.android.sync.model.SyncPairingCodeResponse
import com.streamvault.android.sync.model.SyncPairingStatusRequest
import com.streamvault.android.sync.model.SyncRegisterRequest
import com.streamvault.android.sync.model.SyncSearchPushPayload
import com.streamvault.android.sync.model.SyncSearchPushRequest
import com.streamvault.android.sync.model.SyncWatchStateReportRequest
import com.streamvault.android.sync.network.TorveSyncApiClient
import com.streamvault.android.sync.realtime.SyncRealtimeEvent
import com.streamvault.android.sync.realtime.SyncWebSocketManager
import com.streamvault.android.sync.storage.EncryptedTokenStore
import com.streamvault.android.sync.storage.InstallationIdStore
import com.streamvault.android.sync.storage.SyncStoredSession
import com.streamvault.data.network.HttpClientFactory
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement

data class SyncAccountState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val userId: String? = null,
    val userEmail: String? = null,
    val deviceId: String? = null,
    val devices: List<SyncDeviceDto> = emptyList(),
    val pairingCode: SyncPairingCodeResponse? = null,
    val pairingStatus: String? = null,
    val wsStatus: String = "disconnected",
    val recentEvents: List<String> = emptyList(),
    val error: String? = null,
)

class SyncCoordinator(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installationIdStore = InstallationIdStore(context)
    private val tokenStore = EncryptedTokenStore(context)
    private val api = TorveSyncApiClient(HttpClientFactory.create())
    private val websocketManager = SyncWebSocketManager()

    private val _state = MutableStateFlow(
        SyncAccountState(
            isAuthenticated = tokenStore.getAccessToken()?.isNotBlank() == true,
            userId = tokenStore.getUserId(),
            userEmail = tokenStore.loadSession()?.email,
            deviceId = tokenStore.getDeviceId(),
        ),
    )
    val state: StateFlow<SyncAccountState> = _state.asStateFlow()
    private val _inboundEvents = MutableSharedFlow<SyncInboundEvent>(extraBufferCapacity = 32)
    val inboundEvents: SharedFlow<SyncInboundEvent> = _inboundEvents.asSharedFlow()

    init {
        observeRealtimeEvents()
        if (_state.value.isAuthenticated) {
            startRealtime()
            scope.launch { loadDevices() }
        }
    }

    fun installationId(): String = installationIdStore.getOrCreateInstallationId()

    fun login(email: String, password: String) {
        scope.launch {
            mutateLoading()
            runCatching {
                val response = api.login(
                    SyncLoginRequest(
                        email = email.trim(),
                        password = password,
                        device = currentDeviceRegistration(),
                    ),
                )
                onAuthSuccess(response)
                loadDevices()
            }.onFailure { onError(it) }
        }
    }

    fun register(email: String, password: String) {
        scope.launch {
            mutateLoading()
            runCatching {
                val response = api.register(
                    SyncRegisterRequest(
                        email = email.trim(),
                        password = password,
                        device = currentDeviceRegistration(),
                    ),
                )
                onAuthSuccess(response)
                loadDevices()
            }.onFailure { onError(it) }
        }
    }

    fun logout() {
        scope.launch {
            val accessToken = tokenStore.getAccessToken()
            val refreshToken = tokenStore.getRefreshToken()
            if (!accessToken.isNullOrBlank()) {
                runCatching { api.logout(accessToken, refreshToken) }
            }
            websocketManager.stop()
            tokenStore.clear()
            _state.value = SyncAccountState()
        }
    }

    suspend fun loadDevices() {
        val accessToken = ensureAccessToken() ?: return
        runCatching {
            val devices = api.getDevices(accessToken)
            _state.value = _state.value.copy(
                isLoading = false,
                isAuthenticated = true,
                devices = devices,
                error = null,
            )
        }.onFailure { onError(it) }
    }

    fun refreshDevices() {
        scope.launch { loadDevices() }
    }

    fun revokeDevice(deviceId: String) {
        scope.launch {
            val accessToken = ensureAccessToken() ?: return@launch
            runCatching {
                api.revokeDevice(accessToken, deviceId)
                loadDevices()
            }.onFailure { onError(it) }
        }
    }

    fun targetDevices(includeSelf: Boolean = false): List<SyncDeviceDto> {
        val selfId = _state.value.deviceId
        return _state.value.devices.filter { device ->
            val notRevoked = device.revokedAt == null
            val isSelf = device.id == selfId
            notRevoked && (includeSelf || !isSelf)
        }
    }

    suspend fun sendSearchPush(
        targetDeviceId: String,
        query: String,
        filters: Map<String, String> = emptyMap(),
    ): Result<String> {
        if (query.isBlank()) return Result.failure(IllegalArgumentException("Query is blank"))
        val accessToken = ensureAccessToken() ?: return Result.failure(IllegalStateException("Not authenticated"))
        return runCatching {
            val response = api.sendSearchPush(
                accessToken = accessToken,
                payload = SyncSearchPushRequest(
                    targetDeviceId = targetDeviceId,
                    payload = SyncSearchPushPayload(
                        query = query.trim(),
                        filters = filters,
                    ),
                ),
            )
            response.eventId
        }.onFailure { onError(it) }
    }

    suspend fun sendPlaybackIntent(
        targetDeviceId: String,
        contentId: String,
        providerTarget: String,
        positionMs: Long,
        mediaType: String? = null,
        audio: String? = null,
        subtitles: String? = null,
    ): Result<String> {
        if (contentId.isBlank()) return Result.failure(IllegalArgumentException("Content id is blank"))
        val accessToken = ensureAccessToken() ?: return Result.failure(IllegalStateException("Not authenticated"))
        return runCatching {
            val response = api.sendPlaybackIntent(
                accessToken = accessToken,
                payload = SyncPlaybackIntentRequest(
                    targetDeviceId = targetDeviceId,
                    payload = SyncPlaybackIntentPayload(
                        contentId = contentId.trim(),
                        providerTarget = providerTarget,
                        positionMs = positionMs.coerceAtLeast(0L),
                        mediaType = mediaType,
                        audio = audio,
                        subtitles = subtitles,
                    ),
                ),
            )
            response.eventId
        }.onFailure { onError(it) }
    }

    suspend fun reportWatchState(
        contentId: String,
        provider: String,
        positionMs: Long,
    ): Result<Unit> {
        if (contentId.isBlank()) return Result.failure(IllegalArgumentException("Content id is blank"))
        val accessToken = ensureAccessToken() ?: return Result.failure(IllegalStateException("Not authenticated"))
        return runCatching {
            api.reportWatchState(
                accessToken = accessToken,
                payload = SyncWatchStateReportRequest(
                    contentId = contentId.trim(),
                    provider = provider,
                    positionMs = positionMs.coerceAtLeast(0L),
                ),
            )
            Unit
        }
    }

    fun claimPairingCode(code: String) {
        scope.launch {
            val accessToken = ensureAccessToken() ?: return@launch
            mutateLoading()
            runCatching {
                api.claimPairingCode(accessToken, code.trim())
                loadDevices()
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = null,
                    pairingStatus = "Pairing code claimed",
                )
            }.onFailure { onError(it) }
        }
    }

    fun startTvPairingFlow() {
        scope.launch {
            mutateLoading()
            runCatching {
                val pairingCode = api.createPairingCode(
                    SyncPairingCodeRequest(
                        installationId = installationId(),
                        deviceName = Build.MODEL ?: "Torve TV",
                        deviceType = "tv",
                        platform = "android",
                    ),
                )
                _state.value = _state.value.copy(
                    isLoading = false,
                    pairingCode = pairingCode,
                    pairingStatus = "pending",
                    error = null,
                )
                waitForTvPairingClaim(pairingCode.code)
            }.onFailure { onError(it) }
        }
    }

    private suspend fun waitForTvPairingClaim(code: String) {
        repeat(60) {
            delay(3_000L)
            val response = runCatching {
                api.checkPairingStatus(
                    SyncPairingStatusRequest(
                        code = code,
                        installationId = installationId(),
                    ),
                )
            }.getOrNull() ?: return

            if (response.status == "claimed" && response.tokens != null && response.pairedDevice != null) {
                val pairedUserId = response.user?.id ?: tokenStore.getUserId().orEmpty().ifBlank { "paired_user" }
                val pairedEmail = response.user?.email ?: tokenStore.loadSession()?.email.orEmpty().ifBlank { "paired@torve.local" }
                tokenStore.saveSession(
                    SyncStoredSession(
                        accessToken = response.tokens.accessToken,
                        refreshToken = response.tokens.refreshToken,
                        deviceId = response.pairedDevice.id,
                        userId = pairedUserId,
                        email = pairedEmail,
                    ),
                )
                _state.value = _state.value.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    userId = pairedUserId,
                    userEmail = pairedEmail,
                    deviceId = response.pairedDevice.id,
                    pairingStatus = "claimed",
                    error = null,
                )
                startRealtime()
                loadDevices()
                return
            }
            if (response.status == "expired") {
                _state.value = _state.value.copy(
                    isLoading = false,
                    pairingStatus = "expired",
                    error = "Pairing code expired. Generate a new code.",
                )
                return
            }
        }
    }

    private fun onAuthSuccess(response: SyncAuthResponse) {
        tokenStore.saveSession(
            SyncStoredSession(
                accessToken = response.tokens.accessToken,
                refreshToken = response.tokens.refreshToken,
                deviceId = response.device.id,
                userId = response.user.id,
                email = response.user.email,
            ),
        )
        _state.value = _state.value.copy(
            isLoading = false,
            isAuthenticated = true,
            userId = response.user.id,
            userEmail = response.user.email,
            deviceId = response.device.id,
            error = null,
        )
        startRealtime()
    }

    private suspend fun ensureAccessToken(): String? {
        val accessToken = tokenStore.getAccessToken()
        if (!accessToken.isNullOrBlank()) return accessToken
        val refreshToken = tokenStore.getRefreshToken() ?: return null
        return runCatching {
            val refreshed = api.refresh(refreshToken)
            onAuthSuccess(refreshed)
            refreshed.tokens.accessToken
        }.getOrNull()
    }

    private fun startRealtime() {
        websocketManager.start(
            accessTokenProvider = { tokenStore.getAccessToken() },
            deviceIdProvider = { tokenStore.getDeviceId() },
        )
    }

    private fun observeRealtimeEvents() {
        scope.launch {
            websocketManager.events.collectLatest { event ->
                val previous = _state.value.recentEvents.take(19)
                when (event) {
                    SyncRealtimeEvent.Connecting -> {
                        _state.value = _state.value.copy(wsStatus = "connecting")
                    }
                    SyncRealtimeEvent.Connected -> {
                        _state.value = _state.value.copy(wsStatus = "connected")
                    }
                    SyncRealtimeEvent.Disconnected -> {
                        _state.value = _state.value.copy(wsStatus = "disconnected")
                    }
                    is SyncRealtimeEvent.Error -> {
                        _state.value = _state.value.copy(
                            wsStatus = "error",
                            recentEvents = listOf("error:${event.message}") + previous,
                        )
                    }
                    is SyncRealtimeEvent.Message -> {
                        decodeInboundEvent(event)?.let { decoded ->
                            _inboundEvents.tryEmit(decoded)
                        }
                        _state.value = _state.value.copy(
                            recentEvents = listOf("${event.eventType}#${event.eventId}:${event.payload}") + previous,
                        )
                    }
                }
            }
        }
    }

    private fun mutateLoading() {
        _state.value = _state.value.copy(isLoading = true, error = null)
    }

    private fun onError(t: Throwable) {
        val message = when (t) {
            is ClientRequestException -> "Request failed: ${t.response.status.value}"
            is ServerResponseException -> "Server error: ${t.response.status.value}"
            else -> t.message ?: "Unknown error"
        }
        _state.value = _state.value.copy(
            isLoading = false,
            error = message,
        )
    }

    private fun currentDeviceRegistration(): SyncDeviceRegistration {
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val deviceType = if (isTv) "tv" else "mobile"
        return SyncDeviceRegistration(
            installationId = installationId(),
            deviceName = Build.MODEL ?: "Android Device",
            deviceType = deviceType,
            platform = "android",
        )
    }

    private fun decodeInboundEvent(message: SyncRealtimeEvent.Message): SyncInboundEvent? {
        val payload = message.payload
        return runCatching {
            when (message.eventType) {
                "SEARCH_PUSH" -> {
                    val decoded = HttpClientFactory.json.decodeFromJsonElement<SyncSearchPushPayload>(payload)
                    SyncInboundEvent.SearchPush(
                        query = decoded.query,
                        filters = decoded.filters,
                        issuedByDeviceId = decoded.issuedByDeviceId,
                    )
                }

                "PLAYBACK_INTENT" -> {
                    val decoded = HttpClientFactory.json.decodeFromJsonElement<SyncPlaybackIntentPayload>(payload)
                    SyncInboundEvent.PlaybackIntent(
                        contentId = decoded.contentId,
                        providerTarget = decoded.providerTarget,
                        positionMs = decoded.positionMs,
                        mediaType = decoded.mediaType,
                        audio = decoded.audio,
                        subtitles = decoded.subtitles,
                        issuedByDeviceId = decoded.issuedByDeviceId,
                    )
                }

                else -> null
            }
        }.getOrNull()
    }
}
