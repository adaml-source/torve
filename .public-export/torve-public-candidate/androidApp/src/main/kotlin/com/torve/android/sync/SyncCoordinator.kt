package com.torve.android.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.torve.android.premium.PremiumAccessDeniedException
import com.torve.android.premium.PremiumActionGate
import com.torve.android.premium.PremiumFeature
import com.torve.android.sync.lan.LanEventEnvelope
import com.torve.android.sync.lan.LanPairClaimRequest
import com.torve.android.sync.lan.LanPairClaimResponse
import com.torve.android.sync.lan.LanPairConfirmRequest
import com.torve.android.sync.lan.LanResolvedService
import com.torve.android.sync.lan.LanStatusResponse
import com.torve.android.sync.lan.LanSyncDiscovery
import com.torve.android.sync.lan.LanSyncHttpClient
import com.torve.android.sync.lan.LanChannelConfirmRequest
import com.torve.android.sync.lan.LanChannelInitiateRequest
import com.torve.android.sync.lan.LanChannelInitiateResponse
import com.torve.android.sync.lan.LanSyncServer
import com.torve.android.sync.lan.SecureTransferCrypto
import com.torve.android.sync.lan.SecureTransferEnvelope
import com.torve.android.sync.model.SyncDeviceDto
import com.torve.android.sync.model.SyncInboundEvent
import com.torve.android.sync.model.SyncPairingCodeResponse
import com.torve.android.sync.model.SyncPlaybackIntentPayload
import com.torve.android.sync.model.SyncSearchPushPayload
import com.torve.android.sync.model.SecureChannelState
import com.torve.android.sync.model.PairingRole
import com.torve.android.sync.model.SyncWatchStateLatestResponse
import com.torve.android.sync.model.SyncWatchStateReportRequest
import com.torve.android.sync.network.TorveSyncApiClient
import com.torve.android.sync.storage.InstallationIdStore
import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.pairing.PairedDeviceDto
import com.torve.data.pairing.PairingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class SyncAccountState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val userId: String? = null,
    val userEmail: String? = null,
    val profileName: String? = null,
    val hasPin: Boolean = false,
    val deviceId: String? = null,
    val devices: List<SyncDeviceDto> = emptyList(),
    val pairingCodeFlowSupported: Boolean = true,
    val pairingCode: SyncPairingCodeResponse? = null,
    val pairingStatus: String? = null,
    val wsStatus: String = "local-lan-offline",
    val recentEvents: List<String> = emptyList(),
    val reachableInstallationIds: Set<String> = emptySet(),
    val secureChannelStates: Map<String, SecureChannelState> = emptyMap(),
    val setupOwnerIds: Set<String> = emptySet(),
    val error: String? = null,
    val blockedFeature: PremiumFeature? = null,
)

class SyncCoordinator(
    private val context: Context,
    private val premiumActionGate: PremiumActionGate,
    private val authClient: AuthClient,
    private val pairingApi: PairingApi,
    private val secureStorage: com.torve.domain.security.SecureStorage? = null,
    private val torveSyncApiClient: TorveSyncApiClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installationIdStore = InstallationIdStore(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lanHttpClient = LanSyncHttpClient(json)
    private val peerLock = Any()
    private val endpointByInstallationId = mutableMapOf<String, LanResolvedService>()
    private val serviceNameByInstallationId = mutableMapOf<String, String>()
    private val serviceByName = mutableMapOf<String, LanResolvedService>()
    @Volatile private var lanReady: Boolean = false
    /** Per-pairing shared secrets for secure transfer. Key = installationId of peer. */
    private val pairingSecretByInstallationId = mutableMapOf<String, String>()
    /** Maps stale backend installation IDs to actual LAN-discovered IDs (for bridging). */
    private val lanInstallationIdByBackendId = mutableMapOf<String, String>()
    /** Replay protection: seen nonces per pairing. Bounded to last 100. */
    private val seenNonces = mutableSetOf<String>()
    /** Pairing code currently active on this TV (for KDF binding on inbound claim). */
    @Volatile private var activePairingCodeForKdf: String? = null
    /** Pending confirmation state on TV side — awaiting phone_confirm before committing. */
    private data class PendingPairConfirmation(
        val phoneInstallationId: String,
        val derivedSecret: String,
        val transcript: String,
        val createdAtMs: Long = System.currentTimeMillis(),
    )
    @Volatile private var pendingPairConfirmation: PendingPairConfirmation? = null
    /** Pending confirmation state for channel bootstrap — awaiting phone_confirm. */
    private data class PendingChannelConfirmation(
        val phoneInstallationId: String,
        val derivedSecret: String,
        val transcript: String,
        val backendPairingId: String,
        val createdAtMs: Long = System.currentTimeMillis(),
    )
    @Volatile private var pendingChannelConfirmation: PendingChannelConfirmation? = null

    private val lanServer = LanSyncServer(
        json = json,
        selfDeviceProvider = { buildSelfDevice() },
        onPairClaim = { handleInboundPairClaim(it) },
        onPairConfirm = { handleInboundPairConfirm(it) },
        onInboundEvent = { handleInboundLanEvent(it) },
        onChannelInitiate = { handleInboundChannelInitiate(it) },
        onChannelConfirm = { handleInboundChannelConfirm(it) },
    )
    private val lanDiscovery = LanSyncDiscovery(
        context = context,
        selfServiceNameHint = localServiceNameHint(),
        onServiceResolved = { onServiceResolved(it) },
        onServiceLost = { onServiceLost(it) },
        onError = { onLanError(it) },
    )

    private val _state = MutableStateFlow(SyncAccountState(isLoading = true))
    val state = _state.asStateFlow()
    private val _inboundEvents = MutableSharedFlow<SyncInboundEvent>(extraBufferCapacity = 32)
    val inboundEvents: SharedFlow<SyncInboundEvent> = _inboundEvents.asSharedFlow()

    init {
        scope.launch {
            _state.value = buildInitialState()
            restorePersistedPairingSecrets()
            runCatching { loadDevices() }.onFailure {
                // Pairing device load failures are non-blocking — they only affect
                // the pairings UI, not account settings sync or general app function.
                Log.w(TAG, "Initial pairing device load failed (non-blocking): ${it.message}")
                _state.value = _state.value.copy(isLoading = false)
            }
        }
        startLanTransport()
    }

    fun installationId(): String = installationIdStore.getOrCreateInstallationId()

    suspend fun loadDevices() {
        val authUser = authClient.getAuthenticatedUser()
        val token = authClient.getValidAccessToken()
        val pendingPairing = readPendingPairing()
        if (authUser == null || token.isNullOrBlank()) {
            clearPendingPairing()
            _state.value = _state.value.copy(
                isLoading = false,
                isAuthenticated = false,
                userId = null,
                userEmail = null,
                profileName = null,
                deviceId = selfLanDeviceId(),
                devices = emptyList(),
                pairingCodeFlowSupported = false,
                pairingCode = null,
                pairingStatus = "Sign in to pair devices.",
                wsStatus = onlineTransportStatus(),
                error = null,
                blockedFeature = null,
            )
            return
        }
        refreshKnownPeers()
        // Set authenticated state before pairing fetch so a parse/network error
        // does not leave the user appearing signed-out on the pairings screen.
        _state.value = _state.value.copy(
            isAuthenticated = true,
            userId = authUser.id,
            userEmail = authUser.email,
            profileName = authUser.displayName ?: authUser.email,
            deviceId = selfLanDeviceId(),
        )
        val pairings = runCatching { pairingApi.listPairings(token).pairings.map { it.toSyncDevice() } }
            .onFailure { Log.w(TAG, "loadDevices: listPairings failed — ${it.message}") }
            .getOrDefault(emptyList())
        Log.d(TAG, "loadDevices: ${pairings.size} pairings loaded")
        // Code-based pairing is assumed available when authenticated.
        // If the server truly lacks these endpoints, createPairingCode / claimPairingCode
        // will return 404 → PairingUnsupportedException → pairingCodeFlowSupported set to false.
        // The old OPTIONS probe was unreliable (405 from FastAPI, Ktor expectSuccess throws)
        // and blocked the entire pairing UI.
        _state.value = _state.value.copy(
            isLoading = false,
            devices = pairings,
            pairingCodeFlowSupported = true,
            pairingCode = pendingPairing,
            pairingStatus = pairingStatusFor(pairings, pendingPairing, true),
            wsStatus = onlineTransportStatus(),
            reachableInstallationIds = currentReachableIds(),
            secureChannelStates = computeSecureChannelStates(),
            error = null,
            blockedFeature = null,
        )
        // Re-check already-discovered LAN peers for installation ID bridging,
        // since onServiceResolved may have fired before the device list was loaded.
        refreshKnownPeers()
        _state.value = _state.value.copy(secureChannelStates = computeSecureChannelStates())
    }

    fun refreshDevices() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, blockedFeature = null)
            // Restart NSD discovery to pick up services with updated ports after app restarts.
            lanDiscovery.restartDiscovery()
            delay(1500)
            runCatching { loadDevices() }.onFailure {
                _state.value = _state.value.copy(isLoading = false, error = "Could not load paired devices. Please try again.", blockedFeature = null)
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        scope.launch {
            val token = authClient.getValidAccessToken()
            val target = _state.value.devices.firstOrNull { it.id == deviceId }
            if (token.isNullOrBlank() || target?.pairingId.isNullOrBlank()) {
                _state.value = _state.value.copy(error = "Pairing record not available.", blockedFeature = null)
                return@launch
            }
            _state.value = _state.value.copy(isLoading = true, error = null, blockedFeature = null)
            runCatching { pairingApi.revokePairing(token, target.pairingId!!) }
                .onSuccess {
                    synchronized(peerLock) {
                        endpointByInstallationId.remove(target.installationId)
                        serviceNameByInstallationId.remove(target.installationId)
                        pairingSecretByInstallationId.remove(target.installationId)
                    }
                    clearPersistedPairingSecret(target.installationId)
                    appendRecentEvent("pair_revoke:${target.deviceName}")
                    loadDevices()
                }
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false, error = "Could not remove pairing. Please try again.", blockedFeature = null)
                }
        }
    }

    fun removeDevice(deviceId: String) = revokeDevice(deviceId)

    fun targetDevices(includeSelf: Boolean = false): List<SyncDeviceDto> {
        val reachable = synchronized(peerLock) { endpointByInstallationId.keys.toSet() }
        return _state.value.devices.filter { it.revokedAt == null && (includeSelf || it.id != _state.value.deviceId) && reachable.contains(it.installationId) }
    }

    /**
     * Resolve the LAN endpoint for a target device, retrying once after refreshing
     * known peers if the initial lookup fails. This handles the common case where
     * mDNS discovery found the service but the hello fetch hasn't populated the map yet.
     */
    private suspend fun resolveEndpoint(installationId: String): LanResolvedService? {
        synchronized(peerLock) { endpointByInstallationId[installationId] }?.let { return it }
        // Endpoint not found — retry pending NSD resolves and refresh discovered peers.
        Log.d(TAG, "resolveEndpoint: not found for $installationId, retrying pending resolves")
        lanDiscovery.retryPendingResolves()
        delay(1000)
        refreshKnownPeers()
        synchronized(peerLock) { endpointByInstallationId[installationId] }?.let { return it }
        // Still not found — restart NSD discovery (it can miss services), wait for resolves, then try once more.
        Log.d(TAG, "resolveEndpoint: still not found, restarting discovery")
        lanDiscovery.restartDiscovery()
        delay(3000)
        lanDiscovery.retryPendingResolves()
        delay(1000)
        refreshKnownPeers()
        return synchronized(peerLock) { endpointByInstallationId[installationId] }
    }

    suspend fun sendSearchPush(targetDeviceId: String, query: String, filters: Map<String, String> = emptyMap()): Result<String> {
        if (query.isBlank()) return Result.failure(IllegalArgumentException("Query is blank"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Sign in before sending search to another device."))
        val access = premiumActionGate.evaluate(PremiumFeature.TV_PHONE_CONTINUATION)
        if (!access.allowed) {
            markPremiumBlocked(access.feature, access.message)
            return Result.failure(PremiumAccessDeniedException(access.feature, access.message))
        }
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired."))
        val endpoint = resolveEndpoint(target.installationId)
            ?: return Result.failure(IllegalStateException("Target TV is not reachable on local Wi-Fi. Make sure the TV app is open and both devices are on the same network."))
        val eventId = UUID.randomUUID().toString()
        appendRecentEvent("search_push:$eventId:${target.deviceName}:${query.trim()}")
        val event = LanEventEnvelope(
            eventId = eventId,
            eventType = EVENT_SEARCH_PUSH,
            sourceDeviceId = _state.value.deviceId.orEmpty(),
            targetDeviceId = target.installationId,
            payload = json.encodeToJsonElement(SyncSearchPushPayload(query = query.trim(), filters = filters, issuedByDeviceId = _state.value.deviceId)),
        )
        val result = lanHttpClient.sendEvent(endpoint, event).getOrElse { return Result.failure(it) }
        return if (result.status == "ok") Result.success(eventId) else Result.failure(IllegalStateException(result.message ?: "Failed to send search push."))
    }

    suspend fun sendPlaybackIntent(targetDeviceId: String, contentId: String, providerTarget: String, positionMs: Long, mediaType: String? = null, audio: String? = null, subtitles: String? = null): Result<String> {
        if (contentId.isBlank()) return Result.failure(IllegalArgumentException("Content id is blank"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Sign in before transferring playback."))
        val access = premiumActionGate.evaluate(PremiumFeature.TV_PHONE_CONTINUATION)
        if (!access.allowed) {
            markPremiumBlocked(access.feature, access.message)
            return Result.failure(PremiumAccessDeniedException(access.feature, access.message))
        }
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired."))
        val endpoint = resolveEndpoint(target.installationId)
            ?: return Result.failure(IllegalStateException("Target TV is not reachable on local Wi-Fi. Make sure the TV app is open and both devices are on the same network."))
        val eventId = UUID.randomUUID().toString()
        appendRecentEvent("playback_intent:$eventId:${target.deviceName}:$contentId@$positionMs")
        val event = LanEventEnvelope(
            eventId = eventId,
            eventType = EVENT_PLAYBACK_INTENT,
            sourceDeviceId = _state.value.deviceId.orEmpty(),
            targetDeviceId = target.installationId,
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
        return if (result.status == "ok") Result.success(eventId) else Result.failure(IllegalStateException(result.message ?: "Failed to transfer playback."))
    }

    /**
     * Send a setup bundle to a specific paired device via encrypted LAN transfer.
     * Uses AES-256-GCM with a per-pairing shared secret established during pairing.
     * Secrets travel device-to-device, never through the backend.
     */
    suspend fun sendSettingsPush(targetDeviceId: String, categories: List<String>, payloadJson: String): Result<String> {
        if (payloadJson.isBlank()) return Result.failure(IllegalArgumentException("Payload is empty"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Sign in before sending setup to another device."))
        val access = premiumActionGate.evaluate(PremiumFeature.DEVICE_SYNC)
        if (!access.allowed) {
            markPremiumBlocked(access.feature, access.message)
            return Result.failure(PremiumAccessDeniedException(access.feature, access.message))
        }
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired or has been revoked."))
        val endpoint = resolveEndpoint(target.installationId)
            ?: return Result.failure(IllegalStateException("Target device is not reachable on local Wi-Fi. Make sure the TV app is open and both devices are on the same network."))

        val senderInstallationId = installationId()
        // Look up the per-pairing shared secret for encrypted transfer.
        // Also check the LAN-discovered installation ID (bridge scenario).
        val pairingSecret = synchronized(peerLock) {
            pairingSecretByInstallationId[target.installationId]
                ?: lanInstallationIdByBackendId[target.installationId]?.let { pairingSecretByInstallationId[it] }
        }
            ?: return Result.failure(IllegalStateException("Secure channel not established. Tap 'Establish Secure Channel' to set up encrypted transfer for this device."))

        val resolvedTargetInstallationId = synchronized(peerLock) { lanInstallationIdByBackendId[target.installationId] } ?: target.installationId
        val pairingId = target.pairingId ?: target.id
        val issuedAt = System.currentTimeMillis()
        val expiresAt = issuedAt + TRANSFER_VALIDITY_MS
        val nonce = UUID.randomUUID().toString()

        val key = SecureTransferCrypto.deriveKey(pairingSecret, senderInstallationId, resolvedTargetInstallationId)
        val aad = SecureTransferCrypto.buildAad(pairingId, senderInstallationId, resolvedTargetInstallationId, issuedAt)
        val ciphertext = SecureTransferCrypto.encrypt(payloadJson, key, aad)

        val envelope = SecureTransferEnvelope(
            pairingId = pairingId,
            senderInstallationId = senderInstallationId,
            targetInstallationId = resolvedTargetInstallationId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            nonce = nonce,
            ciphertext = ciphertext,
        )

        val eventId = UUID.randomUUID().toString()
        appendRecentEvent("setup_transfer:$eventId:${target.deviceName}")
        val event = LanEventEnvelope(
            eventId = eventId,
            eventType = EVENT_SECURE_SETUP_TRANSFER,
            sourceDeviceId = senderInstallationId,
            targetDeviceId = resolvedTargetInstallationId,
            payload = json.encodeToJsonElement(SecureTransferEnvelope.serializer(), envelope),
        )
        val result = lanHttpClient.sendEvent(endpoint, event).getOrElse { return Result.failure(it) }
        return if (result.status == "ok") Result.success(eventId) else Result.failure(IllegalStateException(result.message ?: "Failed to send setup."))
    }

    /**
     * Establish a secure channel with an already-paired device.
     * Runs ECDH + mutual-MAC handshake over LAN, persists the shared secret.
     * Requires: backend pairing exists, LAN endpoint reachable, no existing secret needed.
     */
    suspend fun bootstrapSecureChannel(targetDeviceId: String): Result<Unit> {
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Sign in before establishing secure channel."))
        val target = _state.value.devices.firstOrNull { it.id == targetDeviceId && it.revokedAt == null }
            ?: return Result.failure(IllegalStateException("Target device is not paired or has been revoked."))
        val endpoint = resolveEndpoint(target.installationId)
            ?: return Result.failure(IllegalStateException("Target device is not reachable on local Wi-Fi."))
        val backendPairingId = target.pairingId ?: target.id

        // Step 1: Generate ephemeral ECDH key pair
        val (phonePubKey, phonePrivKey) = SecureTransferCrypto.generateEphemeralKeyPair()

        // Step 2: Send channel initiate request
        val initiateRequest = LanChannelInitiateRequest(
            phoneInstallationId = installationId(),
            ecdhPublicKey = phonePubKey,
            backendPairingId = backendPairingId,
        )
        val initiateResponse = lanHttpClient.sendChannelInitiate(endpoint, initiateRequest)
            .getOrElse { return Result.failure(IllegalStateException("Failed to initiate secure channel: ${it.message}")) }

        if (initiateResponse.status != "ok" || initiateResponse.ecdhPublicKey == null || initiateResponse.tvConfirm == null || initiateResponse.tvInstallationId == null) {
            return Result.failure(IllegalStateException(initiateResponse.message ?: "Channel initiate rejected by target device."))
        }

        val tvInstallationId = initiateResponse.tvInstallationId

        // Step 3: Derive shared secret
        val secret = runCatching {
            SecureTransferCrypto.deriveChannelBootstrapSecret(
                myPrivateKeyBase64 = phonePrivKey,
                peerPublicKeyBase64 = initiateResponse.ecdhPublicKey,
                backendPairingId = backendPairingId,
                initiatorInstallationId = installationId(),
                responderInstallationId = tvInstallationId,
            )
        }.getOrElse { return Result.failure(IllegalStateException("Key derivation failed: ${it.message}")) }

        // Step 4: Build transcript and verify TV's confirmation MAC
        val transcript = SecureTransferCrypto.buildChannelBootstrapTranscript(
            backendPairingId = backendPairingId,
            initiatorInstallationId = installationId(),
            responderInstallationId = tvInstallationId,
            initiatorPubKeyBase64 = phonePubKey,
            responderPubKeyBase64 = initiateResponse.ecdhPublicKey,
        )
        val tvValid = SecureTransferCrypto.verifyConfirmation(
            derivedSecretBase64 = secret,
            role = "tv-confirm",
            transcript = transcript,
            expectedMac = initiateResponse.tvConfirm,
        )
        if (!tvValid) {
            Log.w(TAG, "bootstrapSecureChannel: TV confirmation MAC verification failed — possible MITM")
            return Result.failure(IllegalStateException("Security verification failed. Could not establish secure channel."))
        }

        // Step 5: Compute phone confirmation MAC and send it
        val phoneConfirm = SecureTransferCrypto.computeConfirmation(secret, "phone-confirm", transcript)
        val confirmResult = lanHttpClient.sendChannelConfirm(
            endpoint,
            LanChannelConfirmRequest(phoneConfirm = phoneConfirm, phoneInstallationId = installationId()),
        ).getOrElse { return Result.failure(IllegalStateException("Failed to confirm secure channel: ${it.message}")) }

        if (confirmResult.status != "ok") {
            return Result.failure(IllegalStateException(confirmResult.message ?: "TV rejected secure channel confirmation."))
        }

        // Step 6: Both sides confirmed — commit the pairing secret
        synchronized(peerLock) {
            pairingSecretByInstallationId[tvInstallationId] = secret
            // Also store under the backend's installation ID if different (for lookup in sendSettingsPush)
            if (target.installationId != tvInstallationId) {
                lanInstallationIdByBackendId[target.installationId] = tvInstallationId
            }
        }
        scope.launch { persistPairingSecret(tvInstallationId, secret) }
        Log.d(TAG, "bootstrapSecureChannel: secure channel established with ${target.deviceName}")
        _state.value = _state.value.copy(secureChannelStates = computeSecureChannelStates())
        return Result.success(Unit)
    }

    suspend fun reportWatchState(contentId: String, provider: String, positionMs: Long): Result<Unit> {
        if (contentId.isBlank()) return Result.failure(IllegalArgumentException("Content id is blank"))
        if (!_state.value.isAuthenticated) return Result.failure(IllegalStateException("Sign in before syncing watch state."))
        val access = premiumActionGate.evaluate(PremiumFeature.DEVICE_SYNC)
        if (!access.allowed) {
            markPremiumBlocked(access.feature, access.message)
            return Result.failure(PremiumAccessDeniedException(access.feature, access.message))
        }
        val safePosition = positionMs.coerceAtLeast(0L)
        appendRecentEvent("watch_state:${contentId.trim()}:${provider}:$safePosition")
        // Best-effort POST to /watch_state/report. A failed write falls back
        // silently — the local SQLDelight DB is still authoritative for the
        // current device, and the next successful write will overwrite.
        val api = torveSyncApiClient ?: return Result.success(Unit)
        val accessToken = authClient.getValidAccessToken() ?: return Result.success(Unit)
        return try {
            api.reportWatchState(
                accessToken = accessToken,
                payload = SyncWatchStateReportRequest(
                    contentId = contentId.trim(),
                    provider = provider,
                    positionMs = safePosition,
                ),
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.d(TAG, "reportWatchState remote write failed: ${e::class.simpleName}")
            Result.success(Unit)
        }
    }

    /**
     * Cross-device resume read. Returns the newest watch-state report the
     * backend has for this content, or `null` if the backend has never seen
     * this content for this user. Callers merge this with their local
     * SQLDelight row and pick whichever has the greater timestamp.
     */
    suspend fun getLatestWatchState(contentId: String): SyncWatchStateLatestResponse? {
        if (contentId.isBlank()) return null
        if (!_state.value.isAuthenticated) return null
        val api = torveSyncApiClient ?: return null
        val accessToken = authClient.getValidAccessToken() ?: return null
        return try {
            api.getLatestWatchState(accessToken = accessToken, contentId = contentId.trim())
        } catch (e: Exception) {
            Log.d(TAG, "getLatestWatchState failed: ${e::class.simpleName}")
            null
        }
    }

    fun claimPairingCode(code: String) {
        val normalized = normalizePairingCode(code)
        if (normalized.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a valid pairing code.")
            return
        }
        scope.launch {
            val token = authClient.getValidAccessToken()
            if (token.isNullOrBlank()) {
                _state.value = _state.value.copy(error = "Sign in before pairing devices.")
                return@launch
            }
            if (!_state.value.pairingCodeFlowSupported) {
                _state.value = _state.value.copy(error = "Code-based pairing is not available on this server.")
                return@launch
            }
            val access = premiumActionGate.evaluate(PremiumFeature.PHONE_PAIRING)
            if (!access.allowed) {
                markPremiumBlocked(access.feature, access.message)
                return@launch
            }
            _state.value = _state.value.copy(isLoading = true, error = null, blockedFeature = null)
            val discovered = synchronized(peerLock) { serviceByName.values.toList() }
            var pairedLanDevice: SyncDeviceDto? = null
            var pairedService: LanResolvedService? = null
            if (discovered.isNotEmpty()) {
                for (service in discovered) {
                    // Fresh ephemeral ECDH key pair per attempt — never reuse across targets
                    val (phonePubKey, phonePrivKey) = SecureTransferCrypto.generateEphemeralKeyPair()
                    val request = LanPairClaimRequest(code = normalized, sourceDevice = buildSelfDevice(), ecdhPublicKey = phonePubKey)
                    val response = lanHttpClient.claimPairingCode(service, request).getOrNull() ?: continue
                    if (response.status == "paired" && response.device != null && response.ecdhPublicKey != null && response.tvConfirm != null) {
                        val tvInstallationId = response.device.installationId
                        // Step 1: Derive shared secret
                        val secret = runCatching {
                            SecureTransferCrypto.deriveSharedSecret(
                                myPrivateKeyBase64 = phonePrivKey,
                                peerPublicKeyBase64 = response.ecdhPublicKey,
                                pairingCode = normalized,
                                senderInstallationId = installationId(),
                                targetInstallationId = tvInstallationId,
                            )
                        }.getOrNull() ?: continue

                        // Step 2: Build canonical transcript (must match TV's)
                        val transcript = SecureTransferCrypto.buildTranscript(
                            pairingCode = normalized,
                            phoneInstallationId = installationId(),
                            tvInstallationId = tvInstallationId,
                            phonePubKeyBase64 = phonePubKey,
                            tvPubKeyBase64 = response.ecdhPublicKey,
                        )

                        // Step 3: Verify TV's confirmation MAC
                        val tvValid = SecureTransferCrypto.verifyConfirmation(
                            derivedSecretBase64 = secret,
                            role = "tv-confirm",
                            transcript = transcript,
                            expectedMac = response.tvConfirm,
                        )
                        if (!tvValid) {
                            Log.w(TAG, "TV confirmation MAC verification failed — possible MITM")
                            continue
                        }

                        // Step 4: Compute phone's confirmation MAC and send it
                        val phoneConfirm = SecureTransferCrypto.computeConfirmation(secret, "phone-confirm", transcript)
                        val confirmResult = lanHttpClient.sendPairConfirm(
                            service,
                            LanPairConfirmRequest(phoneConfirm = phoneConfirm, phoneInstallationId = installationId()),
                        ).getOrNull()
                        if (confirmResult?.status != "ok") {
                            Log.w(TAG, "TV rejected phone confirmation: ${confirmResult?.message}")
                            continue
                        }

                        // Step 5: Both confirmed — commit the pairing secret
                        pairedLanDevice = response.device
                        pairedService = service
                        synchronized(peerLock) {
                            pairingSecretByInstallationId[tvInstallationId] = secret
                        }
                        scope.launch { persistPairingSecret(tvInstallationId, secret) }
                        break
                    }
                }
            }
            Log.d(TAG, "claimPairingCode: calling backend with device=${authDeviceRegistration().device_id}")
            runCatching { pairingApi.claimPairingCode(token, normalized, authDeviceRegistration()) }
                .onSuccess {
                    Log.d(TAG, "claimPairingCode: success")
                    if (pairedLanDevice != null && pairedService != null) {
                        synchronized(peerLock) {
                            endpointByInstallationId[pairedLanDevice!!.installationId] = pairedService!!
                            serviceNameByInstallationId[pairedLanDevice!!.installationId] = pairedService!!.serviceName
                        }
                    }
                    appendRecentEvent("pair_claimed:$normalized")
                    loadDevices()
                }
                .onFailure {
                    Log.w(TAG, "claimPairingCode: failed — ${it.javaClass.simpleName}: ${it.message}")
                    val pairingSupported = if (it is com.torve.data.pairing.PairingUnsupportedException) false else _state.value.pairingCodeFlowSupported
                    _state.value = _state.value.copy(
                        isLoading = false,
                        pairingCodeFlowSupported = pairingSupported,
                        error = "Could not complete pairing. Please try again.",
                        blockedFeature = null,
                    )
                }
        }
    }

    /**
     * Claim a TV-sign-in code from the new `/pairing/signin/claim`
     * endpoint. Distinct from [claimPairingCode] which targets the
     * legacy premium-gated phone↔TV remote-control flow on
     * `/pairing/claim`. Codes generated by the TV's QR-sign-in screen
     * live in a separate `pairing_signin_codes` table on the backend, so
     * routing them to the legacy claim path would 404.
     *
     * On 409 the phone's account is at its active-device cap;
     * [onDeviceLimitReached] fires so the caller can route the user to
     * the standard manage-devices UX (same as a regular login that hits
     * the cap).
     */
    fun claimTvSigninCode(code: String, onDeviceLimitReached: () -> Unit = {}) {
        val normalized = normalizePairingCode(code)
        if (normalized.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a valid TV sign-in code.")
            return
        }
        scope.launch {
            val token = authClient.getValidAccessToken()
            if (token.isNullOrBlank()) {
                _state.value = _state.value.copy(error = "Sign in before claiming a TV code.")
                return@launch
            }
            _state.value = _state.value.copy(isLoading = true, error = null, blockedFeature = null)
            runCatching { pairingApi.claimSigninCode(token, normalized) }
                .onSuccess {
                    Log.d(TAG, "claimTvSigninCode: success")
                    appendRecentEvent("tv_signin_claimed:$normalized")
                    _state.value = _state.value.copy(
                        isLoading = false,
                        pairingStatus = "TV signed in.",
                        error = null,
                    )
                    loadDevices()
                }
                .onFailure { t ->
                    Log.w(TAG, "claimTvSigninCode: failed — ${t::class.simpleName}: ${t.message}")
                    if (t is com.torve.data.pairing.PairingDeviceLimitException) {
                        _state.value = _state.value.copy(isLoading = false, error = null)
                        // Navigation from a Ktor response callback resumes on
                        // Dispatchers.Default; NavController.navigate must be
                        // called on Main or it crashes with "setCurrentState
                        // must be called on the main thread". Marshal explicitly.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onDeviceLimitReached()
                        }
                        return@onFailure
                    }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Could not sign in the TV. Please try again.",
                    )
                }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearBlockedFeature() {
        _state.value = _state.value.copy(blockedFeature = null)
    }

    fun startTvPairingFlow() {
        scope.launch {
            val token = authClient.getValidAccessToken()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "startTvPairingFlow: auth unavailable; user not signed in")
                _state.value = _state.value.copy(error = "Sign in before generating a pairing code.")
                return@launch
            }
            // Ensure server device ID is available (populated during login/refresh).
            // If missing, force a token refresh to populate it from the auth response.
            var serverDeviceId = authClient.getServerDeviceId()
            if (serverDeviceId == null) {
                Log.d(TAG, "startTvPairingFlow: server device ID not cached, forcing auth refresh")
                val refreshResult = authClient.refreshTokens()
                Log.d(TAG, "startTvPairingFlow: refresh result success=${refreshResult.success} error=${refreshResult.error}")
                serverDeviceId = authClient.getServerDeviceId()
                Log.d(TAG, "startTvPairingFlow: server device ID after refresh=$serverDeviceId")
            } else {
                Log.d(TAG, "startTvPairingFlow: server device ID from cache=$serverDeviceId")
            }
            if (!_state.value.pairingCodeFlowSupported) {
                Log.w(TAG, "startTvPairingFlow: pairingCodeFlowSupported is false")
                _state.value = _state.value.copy(error = "Code-based pairing is not available on this server.")
                return@launch
            }
            val access = premiumActionGate.evaluate(PremiumFeature.PHONE_PAIRING)
            if (!access.allowed) {
                Log.w(TAG, "startTvPairingFlow: premium blocked — ${access.message}")
                markPremiumBlocked(access.feature, access.message)
                return@launch
            }
            Log.d(TAG, "startTvPairingFlow: requesting pairing code from backend (deviceId=${authClient.getServerDeviceId()})")
            _state.value = _state.value.copy(isLoading = true, error = null, blockedFeature = null)
            runCatching {
                pairingApi.createPairingCode(accessToken = token, device = authDeviceRegistration())
            }.onSuccess { response ->
                Log.d(TAG, "startTvPairingFlow: code generated successfully")
                persistPendingPairing(response.code, response.expiresAt)
                activePairingCodeForKdf = normalizePairingCode(response.code)
                _state.value = _state.value.copy(
                    isLoading = false,
                    pairingCode = SyncPairingCodeResponse(code = response.code, expiresAt = response.expiresAt),
                    pairingStatus = "Open Torve on your phone or tablet and enter this code to pair with this TV.",
                    wsStatus = onlineTransportStatus(),
                    error = null,
                    blockedFeature = null,
                )
            }.onFailure {
                Log.w(TAG, "startTvPairingFlow: failed — ${it.javaClass.simpleName}: ${it.message}")
                val pairingSupported = if (it is com.torve.data.pairing.PairingUnsupportedException) false else _state.value.pairingCodeFlowSupported
                _state.value = _state.value.copy(
                    isLoading = false,
                    pairingCodeFlowSupported = pairingSupported,
                    error = "Could not generate pairing code. Please try again.",
                    blockedFeature = null,
                )
            }
        }
    }

    private fun startLanTransport() {
        scope.launch {
            runCatching {
                lanServer.startIfNeeded()
                lanDiscovery.start(lanServer.port)
                lanReady = true
                _state.value = _state.value.copy(wsStatus = onlineTransportStatus(), error = null, blockedFeature = null)
            }.onFailure { error ->
                lanReady = false
                _state.value = _state.value.copy(wsStatus = onlineTransportStatus())
                Log.w(TAG, "LAN pairing transport unavailable (non-blocking): ${error.message}")
            }
        }
    }

    private fun onServiceResolved(service: LanResolvedService) {
        synchronized(peerLock) { serviceByName[service.serviceName] = service }
        scope.launch {
            val hello = lanHttpClient.fetchHello(service).getOrElse {
                Log.w(TAG, "fetchHello failed for ${service.serviceName} (${service.host}:${service.port}): ${it.message}")
                // Remove stale service so NSD can re-discover it with the correct port.
                synchronized(peerLock) { serviceByName.remove(service.serviceName) }
                return@launch
            }
            val remote = hello.device
            Log.d(TAG, "onServiceResolved: ${service.serviceName} → installationId=${remote.installationId} name=${remote.deviceName} type=${remote.deviceType}")
            if (remote.installationId == installationId()) return@launch
            synchronized(peerLock) {
                endpointByInstallationId[remote.installationId] = service
                serviceNameByInstallationId[remote.installationId] = service.serviceName
            }

            // If the LAN-discovered installation ID doesn't match any backend device,
            // try to correlate by device name + type. This handles cases where a device's
            // local installation ID was regenerated (e.g., app data cleared) but the backend
            // still has the old record. Register the endpoint under the backend's ID too.
            val devices = _state.value.devices
            val directMatch = devices.any { it.installationId == remote.installationId }
            if (!directMatch) {
                Log.d(TAG, "onServiceResolved: no direct match for LAN id=${remote.installationId} name=${remote.deviceName} type=${remote.deviceType}. Backend devices: ${devices.map { "id=${it.installationId}|name=${it.deviceName}|type=${it.deviceType}|revoked=${it.revokedAt}" }}")
                // Try to bridge: find an unmatched backend device that could be this LAN peer.
                // Match criteria (in order): exact name+type, then just type, then any single unmatched device.
                val unreachableDevices = devices.filter {
                    it.revokedAt == null && !synchronized(peerLock) { endpointByInstallationId.containsKey(it.installationId) }
                }
                val candidate = unreachableDevices.firstOrNull { it.deviceName == remote.deviceName && it.deviceType == remote.deviceType }
                    ?: unreachableDevices.firstOrNull { it.deviceType == remote.deviceType }
                    ?: unreachableDevices.singleOrNull()
                if (candidate != null) {
                    Log.d(TAG, "onServiceResolved: bridging stale backend installationId ${candidate.installationId} → LAN installationId ${remote.installationId}")
                    synchronized(peerLock) {
                        endpointByInstallationId[candidate.installationId] = service
                        serviceNameByInstallationId[candidate.installationId] = service.serviceName
                        lanInstallationIdByBackendId[candidate.installationId] = remote.installationId
                    }
                }
            }

            val reachable = currentReachableIds()
            if (directMatch) {
                _state.value = _state.value.copy(
                    devices = devices.map { device ->
                        if (device.installationId == remote.installationId) {
                            device.copy(deviceName = remote.deviceName, deviceType = remote.deviceType, platform = remote.platform, lastSeenAt = utcNow())
                        } else {
                            device
                        }
                    },
                    wsStatus = onlineTransportStatus(),
                    reachableInstallationIds = reachable,
                    secureChannelStates = computeSecureChannelStates(),
                )
            } else {
                _state.value = _state.value.copy(
                    wsStatus = onlineTransportStatus(),
                    reachableInstallationIds = reachable,
                    secureChannelStates = computeSecureChannelStates(),
                )
            }
        }
    }

    private fun onServiceLost(serviceName: String) {
        val lostInstallations = mutableListOf<String>()
        synchronized(peerLock) {
            serviceByName.remove(serviceName)
            val iterator = serviceNameByInstallationId.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value == serviceName) {
                    endpointByInstallationId.remove(entry.key)
                    lostInstallations += entry.key
                    iterator.remove()
                }
            }
        }
        if (lostInstallations.isNotEmpty()) {
            _state.value = _state.value.copy(reachableInstallationIds = currentReachableIds(), secureChannelStates = computeSecureChannelStates())
            appendRecentEvent("peer_offline:${lostInstallations.joinToString(",")}")
        }
    }

    private fun onLanError(message: String) {
        android.util.Log.w(TAG, "LAN error: $message")
        _state.value = _state.value.copy(error = "Network discovery encountered an issue.", wsStatus = onlineTransportStatus(), reachableInstallationIds = currentReachableIds(), secureChannelStates = computeSecureChannelStates())
    }

    private suspend fun refreshKnownPeers() {
        val discovered = synchronized(peerLock) { serviceByName.values.toList() }
        discovered.forEach { service ->
            val hello = lanHttpClient.fetchHello(service).getOrNull() ?: return@forEach
            val remote = hello.device
            if (remote.installationId == installationId()) return@forEach
            synchronized(peerLock) {
                endpointByInstallationId[remote.installationId] = service
                serviceNameByInstallationId[remote.installationId] = service.serviceName
            }
            // Bridge stale backend installation IDs (same logic as onServiceResolved).
            val devices = _state.value.devices
            if (devices.none { it.installationId == remote.installationId }) {
                val unreachable = devices.filter {
                    it.revokedAt == null && !synchronized(peerLock) { endpointByInstallationId.containsKey(it.installationId) }
                }
                val candidate = unreachable.firstOrNull { it.deviceName == remote.deviceName && it.deviceType == remote.deviceType }
                    ?: unreachable.firstOrNull { it.deviceType == remote.deviceType }
                    ?: unreachable.singleOrNull()
                if (candidate != null) {
                    Log.d(TAG, "refreshKnownPeers: bridging stale backend installationId ${candidate.installationId} → LAN ${remote.installationId}")
                    synchronized(peerLock) {
                        endpointByInstallationId[candidate.installationId] = service
                        serviceNameByInstallationId[candidate.installationId] = service.serviceName
                        lanInstallationIdByBackendId[candidate.installationId] = remote.installationId
                    }
                }
            }
        }
        _state.value = _state.value.copy(wsStatus = onlineTransportStatus(), reachableInstallationIds = currentReachableIds(), secureChannelStates = computeSecureChannelStates())
    }

    private fun handleInboundPairClaim(request: LanPairClaimRequest): LanPairClaimResponse {
        val premiumDecision = runCatching { kotlinx.coroutines.runBlocking { premiumActionGate.evaluate(PremiumFeature.PHONE_PAIRING) } }
            .getOrElse { return LanPairClaimResponse(status = "error", message = it.message ?: "Failed to validate account access.") }
        if (!premiumDecision.allowed) {
            markPremiumBlocked(premiumDecision.feature, premiumDecision.message)
            return LanPairClaimResponse(status = "premium_required", message = premiumDecision.message)
        }
        val active = _state.value.pairingCode ?: return LanPairClaimResponse(status = "invalid_code", message = "No active pairing code.")
        if (isPairingExpired(active.expiresAt)) {
            clearPendingPairing()
            _state.value = _state.value.copy(pairingCode = null, pairingStatus = "Pairing code expired. Generate a new one.")
            return LanPairClaimResponse(status = "expired", message = "Pairing code expired.")
        }
        if (normalizePairingCode(request.code) != normalizePairingCode(active.code)) {
            return LanPairClaimResponse(status = "invalid_code", message = "Pairing code mismatch.")
        }
        val pairingCode = activePairingCodeForKdf ?: normalizePairingCode(active.code)
        clearPendingPairing()
        activePairingCodeForKdf = null

        if (request.ecdhPublicKey == null) {
            return LanPairClaimResponse(status = "error", message = "ECDH public key required for secure pairing.")
        }

        // ECDH key agreement: TV generates its own ephemeral key pair.
        val (tvPub, tvPriv) = SecureTransferCrypto.generateEphemeralKeyPair()
        val secret = runCatching {
            SecureTransferCrypto.deriveSharedSecret(
                myPrivateKeyBase64 = tvPriv,
                peerPublicKeyBase64 = request.ecdhPublicKey,
                pairingCode = pairingCode,
                senderInstallationId = request.sourceDevice.installationId,
                targetInstallationId = installationId(),
            )
        }.getOrElse {
            return LanPairClaimResponse(status = "error", message = "Key agreement failed.")
        }

        // Build canonical transcript for mutual confirmation
        val transcript = SecureTransferCrypto.buildTranscript(
            pairingCode = pairingCode,
            phoneInstallationId = request.sourceDevice.installationId,
            tvInstallationId = installationId(),
            phonePubKeyBase64 = request.ecdhPublicKey,
            tvPubKeyBase64 = tvPub,
        )

        // Compute TV's confirmation MAC — proves we derived the correct secret
        val tvConfirm = SecureTransferCrypto.computeConfirmation(secret, "tv-confirm", transcript)

        // Store pending confirmation — secret is NOT persisted until phone confirms back
        pendingPairConfirmation = PendingPairConfirmation(
            phoneInstallationId = request.sourceDevice.installationId,
            derivedSecret = secret,
            transcript = transcript,
        )

        _state.value = _state.value.copy(
            pairingCode = null,
            pairingStatus = "Verifying pairing security…",
            wsStatus = onlineTransportStatus(),
            error = null,
            blockedFeature = null,
        )
        return LanPairClaimResponse(status = "paired", device = buildSelfDevice(), message = "paired", ecdhPublicKey = tvPub, tvConfirm = tvConfirm)
    }

    /**
     * Handle phone's confirmation MAC. This is step 3 of mutual authentication.
     * TV verifies phone_confirm, then commits the pairing secret.
     *
     * Uses synchronized(peerLock) for atomic consume — ensures only one concurrent
     * confirm request can observe and act on the pending state.
     */
    private fun handleInboundPairConfirm(request: LanPairConfirmRequest): LanStatusResponse {
        // Atomically snapshot and consume the pending confirmation to prevent
        // TOCTOU races: only one thread can ever observe a non-null pending state.
        val pending = synchronized(peerLock) {
            val p = pendingPairConfirmation ?: return LanStatusResponse(status = "rejected", message = "No pending pairing confirmation.")
            pendingPairConfirmation = null
            p
        }

        // Reject expired pending confirmations (max 60 seconds)
        val ageMs = System.currentTimeMillis() - pending.createdAtMs
        if (ageMs > PENDING_CONFIRM_MAX_AGE_MS) {
            Log.w(TAG, "Pair confirmation rejected: pending state expired")
            return LanStatusResponse(status = "rejected", message = "Pairing confirmation expired. Please re-pair.")
        }

        if (request.phoneInstallationId != pending.phoneInstallationId) {
            Log.w(TAG, "Pair confirmation rejected: installation ID mismatch")
            return LanStatusResponse(status = "rejected", message = "Installation ID mismatch.")
        }

        // Verify phone's confirmation MAC
        val valid = SecureTransferCrypto.verifyConfirmation(
            derivedSecretBase64 = pending.derivedSecret,
            role = "phone-confirm",
            transcript = pending.transcript,
            expectedMac = request.phoneConfirm,
        )
        if (!valid) {
            Log.w(TAG, "Pair confirmation failed: phone MAC invalid")
            return LanStatusResponse(status = "rejected", message = "Confirmation verification failed. Possible MITM attack.")
        }

        // Both sides confirmed — commit the pairing secret
        synchronized(peerLock) {
            pairingSecretByInstallationId[pending.phoneInstallationId] = pending.derivedSecret
        }
        scope.launch { persistPairingSecret(pending.phoneInstallationId, pending.derivedSecret) }

        _state.value = _state.value.copy(
            pairingStatus = "Pairing confirmed securely.",
            wsStatus = onlineTransportStatus(),
            error = null,
            blockedFeature = null,
        )
        appendRecentEvent("pair_confirmed:${pending.phoneInstallationId}")
        return LanStatusResponse(status = "ok")
    }

    private fun handleInboundChannelInitiate(request: LanChannelInitiateRequest): LanChannelInitiateResponse {
        if (!_state.value.isAuthenticated) {
            return LanChannelInitiateResponse(status = "rejected", message = "Not authenticated.")
        }
        // Verify this TV is the intended target: the backendPairingId should reference a valid pairing.
        // The TV's device list contains OTHER devices (phones), not itself.
        // We verify the phone is a known peer by installationId, device name, or just that
        // the TV has active pairings. The mutual MAC exchange in the confirm step provides
        // the actual cryptographic authentication — both sides must share the same backendPairingId.
        val devices = _state.value.devices
        val knownPeer = devices.any { it.revokedAt == null } && request.phoneInstallationId.isNotBlank() && request.backendPairingId.isNotBlank()
        if (!knownPeer) {
            return LanChannelInitiateResponse(status = "rejected", message = "No active pairings or invalid request.")
        }

        // Generate TV's ephemeral ECDH key pair
        val (tvPubKey, tvPrivKey) = SecureTransferCrypto.generateEphemeralKeyPair()

        // Derive shared secret
        val secret = runCatching {
            SecureTransferCrypto.deriveChannelBootstrapSecret(
                myPrivateKeyBase64 = tvPrivKey,
                peerPublicKeyBase64 = request.ecdhPublicKey,
                backendPairingId = request.backendPairingId,
                initiatorInstallationId = request.phoneInstallationId,
                responderInstallationId = installationId(),
            )
        }.getOrElse {
            return LanChannelInitiateResponse(status = "error", message = "Key derivation failed.")
        }

        // Build transcript and compute TV confirmation MAC
        val transcript = SecureTransferCrypto.buildChannelBootstrapTranscript(
            backendPairingId = request.backendPairingId,
            initiatorInstallationId = request.phoneInstallationId,
            responderInstallationId = installationId(),
            initiatorPubKeyBase64 = request.ecdhPublicKey,
            responderPubKeyBase64 = tvPubKey,
        )
        val tvConfirm = SecureTransferCrypto.computeConfirmation(secret, "tv-confirm", transcript)

        // Store pending confirmation — await phone_confirm before committing
        pendingChannelConfirmation = PendingChannelConfirmation(
            phoneInstallationId = request.phoneInstallationId,
            derivedSecret = secret,
            transcript = transcript,
            backendPairingId = request.backendPairingId,
        )

        return LanChannelInitiateResponse(
            status = "ok",
            ecdhPublicKey = tvPubKey,
            tvConfirm = tvConfirm,
            tvInstallationId = installationId(),
        )
    }

    private fun handleInboundChannelConfirm(request: LanChannelConfirmRequest): LanStatusResponse {
        val pending = pendingChannelConfirmation
            ?: return LanStatusResponse(status = "error", message = "No pending channel bootstrap.")

        if (request.phoneInstallationId != pending.phoneInstallationId) {
            return LanStatusResponse(status = "rejected", message = "Installation ID mismatch.")
        }

        // Check expiry (30 seconds)
        if (System.currentTimeMillis() - pending.createdAtMs > 30_000) {
            pendingChannelConfirmation = null
            return LanStatusResponse(status = "expired", message = "Channel bootstrap expired.")
        }

        // Verify phone's confirmation MAC
        val phoneValid = SecureTransferCrypto.verifyConfirmation(
            derivedSecretBase64 = pending.derivedSecret,
            role = "phone-confirm",
            transcript = pending.transcript,
            expectedMac = request.phoneConfirm,
        )
        if (!phoneValid) {
            Log.w(TAG, "handleInboundChannelConfirm: phone confirmation MAC verification failed")
            pendingChannelConfirmation = null
            return LanStatusResponse(status = "rejected", message = "Confirmation verification failed.")
        }

        // Both sides confirmed — commit the pairing secret
        synchronized(peerLock) {
            pairingSecretByInstallationId[pending.phoneInstallationId] = pending.derivedSecret
        }
        scope.launch { persistPairingSecret(pending.phoneInstallationId, pending.derivedSecret) }
        pendingChannelConfirmation = null
        Log.d(TAG, "handleInboundChannelConfirm: secure channel established with phone ${pending.phoneInstallationId}")
        _state.value = _state.value.copy(secureChannelStates = computeSecureChannelStates())
        return LanStatusResponse(status = "ok")
    }

    private fun handleInboundLanEvent(event: LanEventEnvelope): LanStatusResponse {
        if (event.targetDeviceId != installationId()) {
            return LanStatusResponse(status = "ignored", message = "Not target device.")
        }
        val gatedFeature = when (event.eventType) {
            EVENT_SEARCH_PUSH -> PremiumFeature.TV_PHONE_CONTINUATION
            EVENT_PLAYBACK_INTENT -> PremiumFeature.TV_PHONE_CONTINUATION
            EVENT_SECURE_SETUP_TRANSFER -> PremiumFeature.DEVICE_SYNC
            // EVENT_SETTINGS_PUSH is rejected unconditionally in the router below — no premium gate needed
            else -> null
        }
        if (gatedFeature != null) {
            val premiumDecision = runCatching { kotlinx.coroutines.runBlocking { premiumActionGate.evaluate(gatedFeature) } }
                .getOrElse { return LanStatusResponse(status = "error", message = it.message ?: "Failed to validate account access.") }
            if (!premiumDecision.allowed) {
                markPremiumBlocked(premiumDecision.feature, premiumDecision.message)
                return LanStatusResponse(status = "premium_required", message = premiumDecision.message)
            }
        }
        return runCatching {
            when (event.eventType) {
                EVENT_SEARCH_PUSH -> {
                    val payload = json.decodeFromJsonElement<SyncSearchPushPayload>(event.payload)
                    _inboundEvents.tryEmit(SyncInboundEvent.SearchPush(query = payload.query, filters = payload.filters, issuedByDeviceId = payload.issuedByDeviceId ?: event.sourceDeviceId))
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
                    // Legacy plaintext settings push — rejected. All secret-bearing setup
                    // must use EVENT_SECURE_SETUP_TRANSFER with AES-256-GCM encryption.
                    Log.w(TAG, "Rejected legacy plaintext SETTINGS_PUSH from ${event.sourceDeviceId}")
                    return LanStatusResponse(status = "rejected", message = "Legacy plaintext settings push is disabled. Use secure transfer.")
                }
                EVENT_SECURE_SETUP_TRANSFER -> {
                    return handleSecureSetupTransfer(event)
                }
                else -> return LanStatusResponse(status = "bad_request", message = "Unsupported event type ${event.eventType}.")
            }
            LanStatusResponse(status = "ok")
        }.getOrElse { LanStatusResponse(status = "error", message = it.message ?: "Failed to decode event.") }
    }

    /**
     * Handle an encrypted setup transfer. Performs full cryptographic verification:
     * 1. Parse secure envelope
     * 2. Verify target matches this device
     * 3. Verify sender is a known non-revoked paired device
     * 4. Look up pairing shared secret
     * 5. Reject expired messages
     * 6. Reject replayed nonces
     * 7. Derive key and decrypt with AES-256-GCM (also verifies integrity + authenticity)
     * 8. Emit decrypted payload for import
     */
    private fun handleSecureSetupTransfer(event: LanEventEnvelope): LanStatusResponse {
        val envelope = runCatching {
            json.decodeFromJsonElement(SecureTransferEnvelope.serializer(), event.payload)
        }.getOrElse {
            return LanStatusResponse(status = "bad_request", message = "Invalid secure transfer envelope.")
        }

        // 1. Target binding — must match this device
        if (envelope.targetInstallationId != installationId()) {
            return LanStatusResponse(status = "rejected", message = "Target device mismatch.")
        }

        // 2. Sender must be a known non-revoked paired device.
        // Check by direct installationId match first, then via lanInstallationIdByBackendId bridge.
        val senderDevice = _state.value.devices.firstOrNull {
            it.revokedAt == null && (
                it.installationId == envelope.senderInstallationId ||
                synchronized(peerLock) { lanInstallationIdByBackendId[it.installationId] } == envelope.senderInstallationId
            )
        }
        // Also accept the sender if we have a pairing secret for them — the channel bootstrap
        // already verified their identity via mutual ECDH + MAC confirmation.
        val hasPairingSecret = synchronized(peerLock) { pairingSecretByInstallationId.containsKey(envelope.senderInstallationId) }
        if (senderDevice == null && !hasPairingSecret) {
            Log.w(TAG, "Rejected secure transfer: sender ${envelope.senderInstallationId} not paired or revoked")
            return LanStatusResponse(status = "rejected", message = "Sender is not a trusted paired device.")
        }

        // 3. Look up pairing shared secret
        val pairingSecret = synchronized(peerLock) { pairingSecretByInstallationId[envelope.senderInstallationId] }
        if (pairingSecret == null) {
            Log.w(TAG, "Rejected secure transfer: no pairing secret for sender ${envelope.senderInstallationId}")
            return LanStatusResponse(status = "rejected", message = "Secure channel not established. Please re-pair.")
        }

        // 4. Reject expired messages
        val now = System.currentTimeMillis()
        if (now > envelope.expiresAt || now < envelope.issuedAt - 30_000L) {
            return LanStatusResponse(status = "rejected", message = "Transfer expired or clock mismatch.")
        }

        // 5. Replay protection — reject duplicate nonces
        synchronized(seenNonces) {
            if (envelope.nonce in seenNonces) {
                Log.w(TAG, "Rejected replayed secure transfer nonce: ${envelope.nonce}")
                return LanStatusResponse(status = "rejected", message = "Replayed transfer rejected.")
            }
            seenNonces.add(envelope.nonce)
            // Bound the set to prevent unbounded growth
            if (seenNonces.size > MAX_SEEN_NONCES) {
                val iterator = seenNonces.iterator()
                iterator.next()
                iterator.remove()
            }
        }

        // 6. Derive key and decrypt (AES-256-GCM — also verifies integrity + authenticity)
        val key = SecureTransferCrypto.deriveKey(pairingSecret, envelope.senderInstallationId, envelope.targetInstallationId)
        val aad = SecureTransferCrypto.buildAad(envelope.pairingId, envelope.senderInstallationId, envelope.targetInstallationId, envelope.issuedAt)
        val decrypted = SecureTransferCrypto.decrypt(envelope.ciphertext, key, aad)
        if (decrypted == null) {
            Log.w(TAG, "Rejected secure transfer: decryption/authentication failed for pairing ${envelope.pairingId}")
            return LanStatusResponse(status = "rejected", message = "Decryption failed. Pairing secret mismatch or tampering detected.")
        }

        // 7. Emit decrypted payload for import
        _inboundEvents.tryEmit(SyncInboundEvent.SettingsPush(
            categories = listOf("secure_transfer"),
            payloadJson = decrypted,
            issuedByDeviceId = envelope.senderInstallationId,
        ))
        appendRecentEvent("secure_transfer_received:${event.eventId}:${senderDevice?.deviceName ?: envelope.senderInstallationId}")
        return LanStatusResponse(status = "ok")
    }

    private suspend fun buildInitialState(): SyncAccountState {
        val authUser = authClient.getAuthenticatedUser()
        val pendingPairing = readPendingPairing()
        return SyncAccountState(
            isLoading = false,
            isAuthenticated = authUser != null,
            userId = authUser?.id,
            userEmail = authUser?.email,
            profileName = authUser?.displayName ?: authUser?.email,
            deviceId = selfLanDeviceId(),
            devices = emptyList(),
            pairingCodeFlowSupported = authUser != null,
            pairingCode = pendingPairing,
            pairingStatus = pairingStatusFor(emptyList(), pendingPairing, authUser != null),
            wsStatus = onlineTransportStatus(),
            blockedFeature = null,
        )
    }

    private fun persistPendingPairing(code: String, expiresAt: String) {
        prefs.edit().putString(KEY_PENDING_PAIR_CODE, code).putString(KEY_PENDING_PAIR_EXPIRES_AT, expiresAt).apply()
    }

    private fun clearPendingPairing() {
        prefs.edit().remove(KEY_PENDING_PAIR_CODE).remove(KEY_PENDING_PAIR_EXPIRES_AT).apply()
    }

    private fun readPendingPairing(): SyncPairingCodeResponse? {
        val code = prefs.getString(KEY_PENDING_PAIR_CODE, null)
        val expiresAt = prefs.getString(KEY_PENDING_PAIR_EXPIRES_AT, null)
        if (code.isNullOrBlank() || expiresAt.isNullOrBlank()) return null
        if (isPairingExpired(expiresAt)) {
            clearPendingPairing()
            return null
        }
        return SyncPairingCodeResponse(code = code, expiresAt = expiresAt)
    }

    private suspend fun authDeviceRegistration(): DeviceRegistrationDto {
        val isTv = isTv()
        val stableId = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        return DeviceRegistrationDto(
            device_id = authClient.getServerDeviceId() ?: installationId(),
            installation_id = installationId(),
            device_name = Build.MODEL ?: if (isTv) "Torve TV" else "Android Device",
            device_type = if (isTv) "tv" else "phone",
            platform = if (isTv) "android_tv" else "android",
            stable_device_id = stableId,
        )
    }

    private fun buildSelfDevice(): SyncDeviceDto {
        val isTv = isTv()
        return SyncDeviceDto(
            id = selfLanDeviceId(),
            installationId = installationId(),
            deviceName = Build.MODEL ?: if (isTv) "Torve TV" else "Android Device",
            deviceType = if (isTv) "tv" else "phone",
            platform = if (isTv) "android_tv" else "android",
            lastSeenAt = utcNow(),
            pairingState = "reachable",
            revokedAt = null,
        )
    }

    private fun appendRecentEvent(entry: String) {
        val history = listOf("${utcNow()}:$entry") + _state.value.recentEvents.take(19)
        _state.value = _state.value.copy(recentEvents = history, error = null, blockedFeature = null, wsStatus = onlineTransportStatus())
    }

    private fun markPremiumBlocked(feature: PremiumFeature, message: String) {
        val history = listOf("${utcNow()}:premium_blocked:${feature.name}") + _state.value.recentEvents.take(19)
        _state.value = _state.value.copy(isLoading = false, error = message, blockedFeature = feature, recentEvents = history, wsStatus = onlineTransportStatus())
    }

    private fun pairingStatusFor(devices: List<SyncDeviceDto>, pendingPairing: SyncPairingCodeResponse?, isAuthenticated: Boolean): String {
        val activePairings = devices.count { it.revokedAt == null }
        return when {
            pendingPairing != null -> "Pairing code ${pendingPairing.code}"
            activePairings > 0 -> "$activePairings paired device${if (activePairings == 1) "" else "s"}"
            isAuthenticated -> "No paired devices yet."
            else -> "Sign in to pair devices."
        }
    }

    private fun isTv(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun normalizePairingCode(raw: String): String = raw.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(6)

    private fun isPairingExpired(expiresAtIso: String): Boolean {
        return runCatching { parseUtc(expiresAtIso) < System.currentTimeMillis() }.getOrDefault(true)
    }

    private fun selfLanDeviceId(): String = "self-${installationId()}"

    private fun localServiceNameHint(): String = "Torve-${installationId().replace("-", "").takeLast(8)}"

    private fun onlineTransportStatus(): String = if (lanReady) "local-lan-online" else "local-lan-offline"

    private fun currentReachableIds(): Set<String> = synchronized(peerLock) { endpointByInstallationId.keys.toSet() }

    private fun computeSecureChannelStates(): Map<String, SecureChannelState> {
        val reachable = currentReachableIds()
        return _state.value.devices.associate { device ->
            val installId = device.installationId
            val lanId = synchronized(peerLock) { lanInstallationIdByBackendId[installId] }
            val hasSecret = synchronized(peerLock) {
                pairingSecretByInstallationId.containsKey(installId) ||
                    (lanId != null && pairingSecretByInstallationId.containsKey(lanId))
            }
            val isReachable = reachable.contains(installId)
            val state = when {
                device.revokedAt != null -> SecureChannelState.PAIRED_REVOKED
                hasSecret && isReachable -> SecureChannelState.PAIRED_SECURE_CHANNEL_READY
                hasSecret && !isReachable -> SecureChannelState.PAIRED_LAN_UNREACHABLE
                !hasSecret && isReachable -> SecureChannelState.PAIRED_LAN_REACHABLE_NO_SECRET
                else -> SecureChannelState.PAIRED_BACKEND_ONLY
            }
            device.id to state
        }
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

    private fun PairedDeviceDto.toSyncDevice(): SyncDeviceDto {
        return SyncDeviceDto(
            id = resolvedDeviceId(),
            pairingId = resolvedPairingId(),
            installationId = resolvedInstallationId(),
            deviceName = resolvedDeviceName(),
            deviceType = resolvedDeviceType(),
            platform = resolvedPlatform(),
            lastSeenAt = resolvedLastSeenAt(),
            pairingState = resolvedPairingState(),
            revokedAt = revokedAt,
        )
    }

    // ── Pairing secret persistence ──

    private suspend fun persistPairingSecret(installationId: String, secret: String) {
        secureStorage?.putString("$PAIRING_SECRET_PREFIX$installationId", secret)
    }

    private fun clearPersistedPairingSecret(installationId: String) {
        scope.launch { secureStorage?.remove("$PAIRING_SECRET_PREFIX$installationId") }
    }

    private suspend fun restorePersistedPairingSecrets() {
        if (secureStorage == null) return
        // Load known installation IDs from persisted pairing secret keys.
        // SharedPreferences.getAll() returns all keys — filter for our prefix.
        val pairingPrefs = context.getSharedPreferences("integration_secret_store", Context.MODE_PRIVATE)
        val allKeys = pairingPrefs.all.keys.filter { it.startsWith(PAIRING_SECRET_PREFIX) }
        for (key in allKeys) {
            val installId = key.removePrefix(PAIRING_SECRET_PREFIX)
            val secret = secureStorage.getString(key)
            if (secret != null) {
                synchronized(peerLock) {
                    pairingSecretByInstallationId[installId] = secret
                }
            }
        }
    }

    private companion object {
        const val TAG = "SyncCoordinator"
        const val EVENT_SEARCH_PUSH = "SEARCH_PUSH"
        const val EVENT_PLAYBACK_INTENT = "PLAYBACK_INTENT"
        const val EVENT_SETTINGS_PUSH = "SETTINGS_PUSH"
        const val EVENT_SECURE_SETUP_TRANSFER = "SECURE_SETUP_TRANSFER"
        const val PREFS_NAME = "torve_sync_local_state"
        const val KEY_PENDING_PAIR_CODE = "pending_pair_code"
        const val KEY_PENDING_PAIR_EXPIRES_AT = "pending_pair_expires_at"
        const val TRANSFER_VALIDITY_MS = 5 * 60 * 1000L  // 5 minutes
        const val PENDING_CONFIRM_MAX_AGE_MS = 60_000L  // 60 seconds
        const val MAX_SEEN_NONCES = 100
        const val PAIRING_SECRET_PREFIX = "pairing_transfer_secret_"
    }
}
