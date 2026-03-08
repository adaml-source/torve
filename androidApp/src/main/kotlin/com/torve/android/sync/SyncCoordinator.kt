package com.torve.android.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.torve.android.sync.lan.LanEventEnvelope
import com.torve.android.sync.lan.LanPairClaimRequest
import com.torve.android.sync.lan.LanPairClaimResponse
import com.torve.android.sync.lan.LanResolvedService
import com.torve.android.sync.lan.LanStatusResponse
import com.torve.android.sync.lan.LanSyncDiscovery
import com.torve.android.sync.lan.LanSyncHttpClient
import com.torve.android.sync.lan.LanSyncServer
import com.torve.android.sync.model.SyncDeviceDto
import com.torve.android.sync.model.SyncInboundEvent
import com.torve.android.sync.model.SyncPairingCodeResponse
import com.torve.android.sync.model.SyncPlaybackIntentPayload
import com.torve.android.sync.model.SyncSearchPushPayload
import com.torve.android.sync.model.SyncSettingsPushPayload
import com.torve.android.sync.storage.EncryptedTokenStore
import com.torve.android.sync.storage.InstallationIdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

data class SyncAccountState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val userId: String? = null,
    val userEmail: String? = null,
    val profileName: String? = null,
    val hasPin: Boolean = false,
    val deviceId: String? = null,
    val devices: List<SyncDeviceDto> = emptyList(),
    val pairingCode: SyncPairingCodeResponse? = null,
    val pairingStatus: String? = null,
    val wsStatus: String = "local-only",
    val recentEvents: List<String> = emptyList(),
    val error: String? = null,
)

class SyncCoordinator(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installationIdStore = InstallationIdStore(context)
    private val tokenStore = EncryptedTokenStore(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lanHttpClient = LanSyncHttpClient(json)
    private val peerLock = Any()
    private val endpointByDeviceId = mutableMapOf<String, LanResolvedService>()
    private val serviceNameByDeviceId = mutableMapOf<String, String>()
    private val serviceByName = mutableMapOf<String, LanResolvedService>()
    @Volatile
    private var lanReady: Boolean = false

    private val lanServer = LanSyncServer(
        json = json,
        selfDeviceProvider = { buildSelfDevice(getOrCreateSelfDeviceId()) },
        onPairClaim = { handleInboundPairClaim(it) },
        onInboundEvent = { handleInboundLanEvent(it) },
    )
    private val lanDiscovery = LanSyncDiscovery(
        context = context,
        selfServiceNameHint = localServiceNameHint(),
        onServiceResolved = { service -> onServiceResolved(service) },
        onServiceLost = { serviceName -> onServiceLost(serviceName) },
        onError = { message -> onLanError(message) },
    )

    private val _state = MutableStateFlow(buildInitialState())
    val state = _state.asStateFlow()
    private val _inboundEvents = MutableSharedFlow<SyncInboundEvent>(extraBufferCapacity = 32)
    val inboundEvents: SharedFlow<SyncInboundEvent> = _inboundEvents.asSharedFlow()

    init {
        startLanTransport()
        scope.launch { loadDevices() }
    }

    fun installationId(): String = installationIdStore.getOrCreateInstallationId()

    fun createLocalProfile(profileName: String, pin: String?) {
        val normalizedName = profileName.trim()
        if (normalizedName.isEmpty()) {
            _state.value = _state.value.copy(error = "Profile name is required.")
            return
        }
        if (!pin.isNullOrBlank() && !pin.matches(Regex("^[0-9]{4,8}$"))) {
            _state.value = _state.value.copy(error = "PIN must be 4 to 8 digits.")
            return
        }
        val hashedPin = pin?.takeIf { it.isNotBlank() }?.let { hashPin(it) }
        prefs.edit()
            .putString(KEY_PROFILE_NAME, normalizedName)
            .putString(KEY_PROFILE_PIN_HASH, hashedPin)
            .apply()
        val selfDeviceId = getOrCreateSelfDeviceId()
        val updatedDevices = ensureSelfDevice(readDevices(), selfDeviceId)
        persistDevices(updatedDevices)
        _state.value = _state.value.copy(
            isLoading = false,
            isAuthenticated = true,
            userId = localUserId(),
            userEmail = normalizedName,
            profileName = normalizedName,
            hasPin = hashedPin != null,
            deviceId = selfDeviceId,
            devices = updatedDevices,
            wsStatus = onlineTransportStatus(),
            error = null,
            pairingStatus = "Local profile ready. Pair TVs on this Wi-Fi network.",
        )
    }

    fun clearLocalProfile() {
        val selfDeviceId = getOrCreateSelfDeviceId()
        val selfDevice = ensureSelfDevice(emptyList(), selfDeviceId)
        persistDevices(selfDevice)
        prefs.edit()
            .remove(KEY_PROFILE_NAME)
            .remove(KEY_PROFILE_PIN_HASH)
            .remove(KEY_PENDING_PAIR_CODE)
            .remove(KEY_PENDING_PAIR_EXPIRES_AT)
            .apply()
        tokenStore.clear()
        _state.value = _state.value.copy(
            isLoading = false,
            isAuthenticated = false,
            userId = null,
            userEmail = null,
            profileName = null,
            hasPin = false,
            deviceId = selfDeviceId,
            devices = selfDevice,
            pairingCode = null,
            pairingStatus = "Local mode active.",
            wsStatus = onlineTransportStatus(),
            error = null,
        )
    }

    fun login(email: String, password: String) {
        val fallback = email.trim().substringBefore("@").ifBlank { "Torve User" }
        val maybePin = password.takeIf { it.matches(Regex("^[0-9]{4,8}$")) }
        createLocalProfile(fallback, maybePin)
    }

    fun register(email: String, password: String) {
        login(email, password)
    }

    fun logout() {
        clearLocalProfile()
    }

    suspend fun loadDevices() {
        val selfDeviceId = getOrCreateSelfDeviceId()
        val devices = ensureSelfDevice(readDevices(), selfDeviceId)
        persistDevices(devices)
        _state.value = _state.value.copy(
            isLoading = false,
            devices = devices,
            deviceId = selfDeviceId,
            wsStatus = onlineTransportStatus(),
            error = null,
        )
    }

    fun refreshDevices() {
        scope.launch {
            loadDevices()
            refreshKnownPeers()
        }
    }

    fun revokeDevice(deviceId: String) {
        val selfId = _state.value.deviceId
        if (deviceId == selfId) {
            _state.value = _state.value.copy(error = "This device cannot revoke itself.")
            return
        }
        val updated = _state.value.devices.map { device ->
            if (device.id == deviceId) {
                device.copy(revokedAt = utcNow(), lastSeenAt = utcNow())
            } else {
                device
            }
        }
        synchronized(peerLock) {
            endpointByDeviceId.remove(deviceId)
            serviceNameByDeviceId.remove(deviceId)
        }
        persistDevices(updated)
        _state.value = _state.value.copy(devices = updated, error = null)
    }

    fun removeDevice(deviceId: String) {
        val selfId = _state.value.deviceId
        if (deviceId == selfId) {
            _state.value = _state.value.copy(error = "Cannot remove the current device.")
            return
        }
        val updated = _state.value.devices.filter { it.id != deviceId }
        synchronized(peerLock) {
            endpointByDeviceId.remove(deviceId)
            serviceNameByDeviceId.remove(deviceId)
        }
        persistDevices(updated)
        _state.value = _state.value.copy(devices = updated, error = null)
    }

    fun targetDevices(includeSelf: Boolean = false): List<SyncDeviceDto> {
        val selfId = _state.value.deviceId
        val reachable = synchronized(peerLock) { endpointByDeviceId.keys.toSet() }
        return _state.value.devices.filter { device ->
            val notRevoked = device.revokedAt == null
            val isSelf = device.id == selfId
            val isReachable = device.id == selfId || reachable.contains(device.id)
            notRevoked && isReachable && (includeSelf || !isSelf)
        }
    }

    suspend fun sendSearchPush(
        targetDeviceId: String,
        query: String,
        filters: Map<String, String> = emptyMap(),
    ): Result<String> {
        if (query.isBlank()) return Result.failure(IllegalArgumentException("Query is blank"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Create a local profile first."))
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired."))
        val eventId = UUID.randomUUID().toString()
        appendRecentEvent("search_push:$eventId:${target.deviceName}:${query.trim()}")
        if (target.id == _state.value.deviceId) {
            _inboundEvents.tryEmit(
                SyncInboundEvent.SearchPush(
                    query = query.trim(),
                    filters = filters,
                    issuedByDeviceId = _state.value.deviceId,
                ),
            )
            return Result.success(eventId)
        }
        val endpoint = synchronized(peerLock) { endpointByDeviceId[target.id] }
            ?: return Result.failure(IllegalStateException("Target TV is not reachable on local Wi-Fi."))
        val event = LanEventEnvelope(
            eventId = eventId,
            eventType = EVENT_SEARCH_PUSH,
            sourceDeviceId = _state.value.deviceId.orEmpty(),
            targetDeviceId = target.id,
            payload = json.encodeToJsonElement(
                SyncSearchPushPayload(
                    query = query.trim(),
                    filters = filters,
                    issuedByDeviceId = _state.value.deviceId,
                ),
            ),
        )
        val result = lanHttpClient.sendEvent(endpoint, event).getOrElse { return Result.failure(it) }
        return if (result.status == "ok") {
            Result.success(eventId)
        } else {
            Result.failure(IllegalStateException(result.message ?: "Failed to send search push."))
        }
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
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Create a local profile first."))
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired."))
        val eventId = UUID.randomUUID().toString()
        appendRecentEvent("playback_intent:$eventId:${target.deviceName}:$contentId@$positionMs")
        if (target.id == _state.value.deviceId) {
            _inboundEvents.tryEmit(
                SyncInboundEvent.PlaybackIntent(
                    contentId = contentId.trim(),
                    providerTarget = providerTarget,
                    positionMs = positionMs.coerceAtLeast(0L),
                    mediaType = mediaType,
                    audio = audio,
                    subtitles = subtitles,
                    issuedByDeviceId = _state.value.deviceId,
                ),
            )
            return Result.success(eventId)
        }
        val endpoint = synchronized(peerLock) { endpointByDeviceId[target.id] }
            ?: return Result.failure(IllegalStateException("Target TV is not reachable on local Wi-Fi."))
        val event = LanEventEnvelope(
            eventId = eventId,
            eventType = EVENT_PLAYBACK_INTENT,
            sourceDeviceId = _state.value.deviceId.orEmpty(),
            targetDeviceId = target.id,
            payload = json.encodeToJsonElement(
                SyncPlaybackIntentPayload(
                    contentId = contentId.trim(),
                    providerTarget = providerTarget,
                    positionMs = positionMs.coerceAtLeast(0L),
                    mediaType = mediaType,
                    audio = audio,
                    subtitles = subtitles,
                    issuedByDeviceId = _state.value.deviceId,
                ),
            ),
        )
        val result = lanHttpClient.sendEvent(endpoint, event).getOrElse { return Result.failure(it) }
        return if (result.status == "ok") {
            Result.success(eventId)
        } else {
            Result.failure(IllegalStateException(result.message ?: "Failed to transfer playback."))
        }
    }

    suspend fun sendSettingsPush(
        targetDeviceId: String,
        categories: List<String>,
        payloadJson: String,
    ): Result<String> {
        if (categories.isEmpty()) return Result.failure(IllegalArgumentException("No categories selected"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Create a local profile first."))
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired."))
        val eventId = UUID.randomUUID().toString()
        appendRecentEvent("settings_push:$eventId:${target.deviceName}:${categories.joinToString(",")}")
        if (target.id == _state.value.deviceId) {
            _inboundEvents.tryEmit(
                SyncInboundEvent.SettingsPush(
                    categories = categories,
                    payloadJson = payloadJson,
                    issuedByDeviceId = _state.value.deviceId,
                ),
            )
            return Result.success(eventId)
        }
        val endpoint = synchronized(peerLock) { endpointByDeviceId[target.id] }
            ?: return Result.failure(IllegalStateException("Target TV is not reachable on local Wi-Fi."))
        val event = LanEventEnvelope(
            eventId = eventId,
            eventType = EVENT_SETTINGS_PUSH,
            sourceDeviceId = _state.value.deviceId.orEmpty(),
            targetDeviceId = target.id,
            payload = json.encodeToJsonElement(
                SyncSettingsPushPayload(
                    categories = categories,
                    payloadJson = payloadJson,
                    issuedByDeviceId = _state.value.deviceId,
                ),
            ),
        )
        val result = lanHttpClient.sendEvent(endpoint, event).getOrElse { return Result.failure(it) }
        return if (result.status == "ok") {
            Result.success(eventId)
        } else {
            Result.failure(IllegalStateException(result.message ?: "Failed to push settings."))
        }
    }

    suspend fun reportWatchState(
        contentId: String,
        provider: String,
        positionMs: Long,
    ): Result<Unit> {
        if (contentId.isBlank()) return Result.failure(IllegalArgumentException("Content id is blank"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Create a local profile first."))
        appendRecentEvent("watch_state:${contentId.trim()}:${provider}:${positionMs.coerceAtLeast(0L)}")
        return Result.success(Unit)
    }

    fun claimPairingCode(code: String) {
        val normalized = normalizePairingCode(code)
        if (normalized.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a valid pairing code.")
            return
        }
        if (!_state.value.isAuthenticated) {
            _state.value = _state.value.copy(error = "Create a local profile before pairing TVs.")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val discovered = synchronized(peerLock) { serviceByName.values.toList() }
            if (discovered.isEmpty()) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "No Torve TVs discovered on local Wi-Fi.",
                )
                return@launch
            }
            val selfDevice = buildSelfDevice(getOrCreateSelfDeviceId())
            val request = LanPairClaimRequest(
                code = normalized,
                sourceDevice = selfDevice,
            )
            var pairedDevice: SyncDeviceDto? = null
            var pairedService: LanResolvedService? = null
            for (service in discovered) {
                val response = lanHttpClient.claimPairingCode(service, request).getOrNull() ?: continue
                if (response.status == "paired" && response.device != null) {
                    pairedDevice = response.device
                    pairedService = service
                    break
                }
            }
            if (pairedDevice == null || pairedService == null) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Pairing code not found on local Wi-Fi.",
                )
                return@launch
            }
            synchronized(peerLock) {
                endpointByDeviceId[pairedDevice.id] = pairedService
                serviceNameByDeviceId[pairedDevice.id] = pairedService.serviceName
            }
            val updated = upsertDevice(_state.value.devices, pairedDevice.copy(lastSeenAt = utcNow(), revokedAt = null))
            persistDevices(updated)
            _state.value = _state.value.copy(
                isLoading = false,
                devices = updated,
                error = null,
                pairingStatus = "Paired ${pairedDevice.deviceName} on local Wi-Fi.",
                wsStatus = onlineTransportStatus(),
            )
            appendRecentEvent("pair_claimed:${pairedDevice.deviceName}")
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun startTvPairingFlow() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        val code = generatePairingCode()
        val expiresAt = utcAt(System.currentTimeMillis() + 10 * 60 * 1_000L)
        prefs.edit()
            .putString(KEY_PENDING_PAIR_CODE, code)
            .putString(KEY_PENDING_PAIR_EXPIRES_AT, expiresAt)
            .apply()
        _state.value = _state.value.copy(
            isLoading = false,
            pairingCode = SyncPairingCodeResponse(code = code, expiresAt = expiresAt),
            pairingStatus = "Open Torve on your phone and enter this code to pair over local Wi-Fi.",
            wsStatus = onlineTransportStatus(),
        )
    }

    private fun startLanTransport() {
        scope.launch {
            runCatching {
                lanServer.startIfNeeded()
                lanDiscovery.start(lanServer.port)
                lanReady = true
                _state.value = _state.value.copy(wsStatus = onlineTransportStatus(), error = null)
            }.onFailure { error ->
                lanReady = false
                _state.value = _state.value.copy(
                    wsStatus = "local-lan-error",
                    error = "Local sync transport failed: ${error.message}",
                )
                Log.e(TAG, "LAN transport start failed", error)
            }
        }
    }

    private fun onServiceResolved(service: LanResolvedService) {
        synchronized(peerLock) {
            serviceByName[service.serviceName] = service
        }
        scope.launch {
            val hello = lanHttpClient.fetchHello(service).getOrNull() ?: return@launch
            val remote = hello.device
            val selfId = getOrCreateSelfDeviceId()
            if (remote.id == selfId) return@launch
            synchronized(peerLock) {
                endpointByDeviceId[remote.id] = service
                serviceNameByDeviceId[remote.id] = service.serviceName
            }
            val devices = _state.value.devices
            if (devices.any { it.id == remote.id }) {
                val updated = devices.map { existing ->
                    if (existing.id == remote.id) {
                        existing.copy(
                            installationId = remote.installationId,
                            deviceName = remote.deviceName,
                            deviceType = remote.deviceType,
                            platform = remote.platform,
                            lastSeenAt = utcNow(),
                            revokedAt = null,
                        )
                    } else {
                        existing
                    }
                }
                persistDevices(updated)
                _state.value = _state.value.copy(
                    devices = updated,
                    wsStatus = onlineTransportStatus(),
                )
            }
        }
    }

    private fun onServiceLost(serviceName: String) {
        val lostDeviceIds = mutableListOf<String>()
        synchronized(peerLock) {
            serviceByName.remove(serviceName)
            val iterator = serviceNameByDeviceId.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value == serviceName) {
                    endpointByDeviceId.remove(entry.key)
                    lostDeviceIds += entry.key
                    iterator.remove()
                }
            }
        }
        if (lostDeviceIds.isNotEmpty()) {
            appendRecentEvent("peer_offline:${lostDeviceIds.joinToString(",")}")
        }
    }

    private fun onLanError(message: String) {
        _state.value = _state.value.copy(error = message, wsStatus = onlineTransportStatus())
    }

    private suspend fun refreshKnownPeers() {
        val discovered = synchronized(peerLock) { serviceByName.values.toList() }
        discovered.forEach { service ->
            val hello = lanHttpClient.fetchHello(service).getOrNull() ?: return@forEach
            val remote = hello.device
            if (remote.id == getOrCreateSelfDeviceId()) return@forEach
            synchronized(peerLock) {
                endpointByDeviceId[remote.id] = service
                serviceNameByDeviceId[remote.id] = service.serviceName
            }
        }
        _state.value = _state.value.copy(wsStatus = onlineTransportStatus())
    }

    private fun handleInboundPairClaim(request: LanPairClaimRequest): LanPairClaimResponse {
        val active = _state.value.pairingCode ?: return LanPairClaimResponse(
            status = "invalid_code",
            message = "No active pairing code.",
        )
        if (isPairingExpired(active.expiresAt)) {
            _state.value = _state.value.copy(
                pairingCode = null,
                pairingStatus = "Pairing code expired. Generate a new code.",
            )
            return LanPairClaimResponse(
                status = "expired",
                message = "Pairing code expired.",
            )
        }
        if (normalizePairingCode(request.code) != normalizePairingCode(active.code)) {
            return LanPairClaimResponse(
                status = "invalid_code",
                message = "Pairing code mismatch.",
            )
        }
        val source = request.sourceDevice.copy(lastSeenAt = utcNow(), revokedAt = null)
        val updated = upsertDevice(_state.value.devices, source)
        persistDevices(updated)
        prefs.edit()
            .remove(KEY_PENDING_PAIR_CODE)
            .remove(KEY_PENDING_PAIR_EXPIRES_AT)
            .apply()
        _state.value = _state.value.copy(
            devices = updated,
            pairingCode = null,
            pairingStatus = "Paired with ${source.deviceName}.",
            wsStatus = onlineTransportStatus(),
            error = null,
        )
        appendRecentEvent("pair_accepted:${source.deviceName}")
        return LanPairClaimResponse(
            status = "paired",
            device = buildSelfDevice(getOrCreateSelfDeviceId()),
            message = "paired",
        )
    }

    private fun handleInboundLanEvent(event: LanEventEnvelope): LanStatusResponse {
        val selfId = _state.value.deviceId ?: getOrCreateSelfDeviceId()
        if (event.targetDeviceId != selfId) {
            return LanStatusResponse(status = "ignored", message = "Not target device.")
        }
        return runCatching {
            when (event.eventType) {
                EVENT_SEARCH_PUSH -> {
                    val payload = json.decodeFromJsonElement<SyncSearchPushPayload>(event.payload)
                    _inboundEvents.tryEmit(
                        SyncInboundEvent.SearchPush(
                            query = payload.query,
                            filters = payload.filters,
                            issuedByDeviceId = payload.issuedByDeviceId ?: event.sourceDeviceId,
                        ),
                    )
                    appendRecentEvent("search_received:${event.eventId}:${payload.query}")
                }

                EVENT_PLAYBACK_INTENT -> {
                    val payload = json.decodeFromJsonElement<SyncPlaybackIntentPayload>(event.payload)
                    _inboundEvents.tryEmit(
                        SyncInboundEvent.PlaybackIntent(
                            contentId = payload.contentId,
                            providerTarget = payload.providerTarget,
                            positionMs = payload.positionMs,
                            mediaType = payload.mediaType,
                            audio = payload.audio,
                            subtitles = payload.subtitles,
                            issuedByDeviceId = payload.issuedByDeviceId ?: event.sourceDeviceId,
                        ),
                    )
                    appendRecentEvent("playback_received:${event.eventId}:${payload.contentId}")
                }

                EVENT_SETTINGS_PUSH -> {
                    val payload = json.decodeFromJsonElement<SyncSettingsPushPayload>(event.payload)
                    _inboundEvents.tryEmit(
                        SyncInboundEvent.SettingsPush(
                            categories = payload.categories,
                            payloadJson = payload.payloadJson,
                            issuedByDeviceId = payload.issuedByDeviceId ?: event.sourceDeviceId,
                        ),
                    )
                    appendRecentEvent("settings_received:${event.eventId}:${payload.categories.joinToString(",")}")
                }

                else -> {
                    return LanStatusResponse(
                        status = "bad_request",
                        message = "Unsupported event type ${event.eventType}.",
                    )
                }
            }
            LanStatusResponse(status = "ok")
        }.getOrElse { error ->
            LanStatusResponse(status = "error", message = error.message ?: "Failed to decode event.")
        }
    }

    private fun buildInitialState(): SyncAccountState {
        migrateLegacySession()
        val profileName = prefs.getString(KEY_PROFILE_NAME, null)?.trim().takeUnless { it.isNullOrBlank() }
        val hasPin = !prefs.getString(KEY_PROFILE_PIN_HASH, null).isNullOrBlank()
        val selfDeviceId = getOrCreateSelfDeviceId()
        val devices = ensureSelfDevice(readDevices(), selfDeviceId)
        persistDevices(devices)
        val pendingCode = prefs.getString(KEY_PENDING_PAIR_CODE, null)
        val pendingExpires = prefs.getString(KEY_PENDING_PAIR_EXPIRES_AT, null)
        val pendingPairing = if (!pendingCode.isNullOrBlank() && !pendingExpires.isNullOrBlank() && !isPairingExpired(pendingExpires)) {
            SyncPairingCodeResponse(code = pendingCode, expiresAt = pendingExpires)
        } else {
            prefs.edit().remove(KEY_PENDING_PAIR_CODE).remove(KEY_PENDING_PAIR_EXPIRES_AT).apply()
            null
        }
        val authenticated = profileName != null
        return SyncAccountState(
            isLoading = false,
            isAuthenticated = authenticated,
            userId = if (authenticated) localUserId() else null,
            userEmail = profileName,
            profileName = profileName,
            hasPin = hasPin,
            deviceId = selfDeviceId,
            devices = devices,
            pairingCode = pendingPairing,
            pairingStatus = when {
                pendingPairing != null -> "Pairing code ${pendingPairing.code}"
                authenticated -> null
                else -> "Local mode active."
            },
            wsStatus = onlineTransportStatus(),
        )
    }

    private fun migrateLegacySession() {
        val existingName = prefs.getString(KEY_PROFILE_NAME, null)
        if (!existingName.isNullOrBlank()) return
        val legacy = tokenStore.loadSession() ?: return
        val migratedName = legacy.email.substringBefore("@").ifBlank { legacy.email }
        prefs.edit()
            .putString(KEY_PROFILE_NAME, migratedName)
            .putString(KEY_SELF_DEVICE_ID, legacy.deviceId)
            .apply()
        tokenStore.clear()
    }

    private fun getOrCreateSelfDeviceId(): String {
        val existing = prefs.getString(KEY_SELF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "self-${installationId()}"
        prefs.edit().putString(KEY_SELF_DEVICE_ID, generated).apply()
        return generated
    }

    private fun readDevices(): List<SyncDeviceDto> {
        val raw = prefs.getString(KEY_DEVICES_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SyncDeviceDto>>(raw) }.getOrDefault(emptyList())
    }

    private fun persistDevices(devices: List<SyncDeviceDto>) {
        prefs.edit()
            .putString(KEY_DEVICES_JSON, json.encodeToString(devices))
            .apply()
    }

    private fun ensureSelfDevice(devices: List<SyncDeviceDto>, selfDeviceId: String): List<SyncDeviceDto> {
        val self = buildSelfDevice(selfDeviceId)
        return if (devices.any { it.id == selfDeviceId }) {
            devices.map { existing ->
                if (existing.id == selfDeviceId) {
                    existing.copy(
                        installationId = self.installationId,
                        deviceName = self.deviceName,
                        deviceType = self.deviceType,
                        platform = self.platform,
                        lastSeenAt = utcNow(),
                    )
                } else {
                    existing
                }
            }
        } else {
            listOf(self) + devices
        }
    }

    private fun upsertDevice(devices: List<SyncDeviceDto>, candidate: SyncDeviceDto): List<SyncDeviceDto> {
        return if (devices.any { it.id == candidate.id }) {
            devices.map { if (it.id == candidate.id) candidate else it }
        } else {
            devices + candidate
        }
    }

    private fun buildSelfDevice(selfDeviceId: String): SyncDeviceDto {
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val type = if (isTv) "tv" else "mobile"
        val platform = if (isTv) "android_tv" else "android"
        return SyncDeviceDto(
            id = selfDeviceId,
            installationId = installationId(),
            deviceName = Build.MODEL ?: if (isTv) "Torve TV" else "Android Device",
            deviceType = type,
            platform = platform,
            lastSeenAt = utcNow(),
            revokedAt = null,
        )
    }

    private fun appendRecentEvent(entry: String) {
        val history = listOf("${utcNow()}:$entry") + _state.value.recentEvents.take(19)
        _state.value = _state.value.copy(
            recentEvents = history,
            error = null,
            wsStatus = onlineTransportStatus(),
        )
    }

    private fun normalizePairingCode(raw: String): String {
        return raw.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(6)
    }

    private fun generatePairingCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString {
            repeat(6) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }

    private fun isPairingExpired(expiresAtIso: String): Boolean {
        return runCatching {
            parseUtc(expiresAtIso) < System.currentTimeMillis()
        }.getOrDefault(true)
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun localUserId(): String = "local-${installationId()}"

    private fun localServiceNameHint(): String {
        val suffix = installationId().replace("-", "").takeLast(8)
        return "Torve-$suffix"
    }

    private fun onlineTransportStatus(): String {
        return if (lanReady) "local-lan-online" else "local-only"
    }

    private fun utcNow(): String = utcAt(System.currentTimeMillis())

    private fun utcAt(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMs))
    }

    private fun parseUtc(iso: String): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.parse(iso)?.time ?: 0L
    }

    private companion object {
        const val TAG = "SyncCoordinator"
        const val EVENT_SEARCH_PUSH = "SEARCH_PUSH"
        const val EVENT_PLAYBACK_INTENT = "PLAYBACK_INTENT"
        const val EVENT_SETTINGS_PUSH = "SETTINGS_PUSH"

        const val PREFS_NAME = "torve_sync_local_state"
        const val KEY_PROFILE_NAME = "local_profile_name"
        const val KEY_PROFILE_PIN_HASH = "local_profile_pin_hash"
        const val KEY_SELF_DEVICE_ID = "self_device_id"
        const val KEY_DEVICES_JSON = "paired_devices_json"
        const val KEY_PENDING_PAIR_CODE = "pending_pair_code"
        const val KEY_PENDING_PAIR_EXPIRES_AT = "pending_pair_expires_at"
    }
}
