package com.torve.presentation.transfer

import com.torve.data.transfer.TransferRelayApi
import com.torve.data.transfer.TransferRelayResult
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.TransferRole
import com.torve.domain.telemetry.TransferTelemetryErrorCategory
import com.torve.domain.telemetry.TransferTelemetryEvents
import com.torve.domain.telemetry.TransferTelemetryState
import com.torve.domain.telemetry.transferAttrs
import com.torve.domain.transfer.Base64Url
import com.torve.domain.transfer.ConfigEntry
import com.torve.domain.transfer.ConfigKeyAllowlist
import com.torve.domain.transfer.DefaultConfigKeyAllowlist
import com.torve.domain.model.PlaylistType
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.transfer.SealedSecretsEnvelope
import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretRecord
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferPlaylistDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Cross-platform sender VM for the credential-transfer flow.
 *
 * Drives:
 *   1. Receiver session-string parse (paste or QR scan).
 *   2. Per-category secret + companion-config snapshot from local stores.
 *   3. Seal via [SecretsTransferProtocol] with the receiver's pubkey.
 *   4. Optional relay POST when the receiver embedded a relay session id.
 *
 * Manual paste of the resulting [SenderStatus.Ready.envelopeJson] always
 * stays available — the relay path is best-effort and never blocks the
 * UI from falling back when the backend is unreachable.
 */
class SecretsTransferSenderViewModel(
    private val protocol: SecretsTransferProtocol,
    private val secretStore: IntegrationSecretStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val prefsRepo: PreferencesRepository,
    private val configKeyAllowlist: ConfigKeyAllowlist = DefaultConfigKeyAllowlist(),
    private val relayApi: TransferRelayApi? = null,
    private val accessTokenProvider: (suspend () -> String?)? = null,
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
    private val attemptTracker: TransferAttemptTracker? = null,
    private val platform: String = "unknown",
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * Optional access to local IPTV/M3U/Xtream playlists. When wired,
     * selecting [SecretCategory.IPTV] in the sender ships every local
     * playlist (with credentials) inside the sealed envelope alongside
     * the enum-keyed secret bag. Null in tests / on platforms without
     * a channel repo wired — IPTV transfers simply ship no playlists.
     */
    private val channelRepo: ChannelRepository? = null,
) {
    private val _state = MutableStateFlow(
        SenderState(
            selectedCategories = TransferSecretCatalog.defaultSelectedCategories,
        ),
    )
    val state: StateFlow<SenderState> = _state.asStateFlow()

    fun updateReceiverSessionString(value: String) {
        _state.value = _state.value.copy(
            receiverSessionString = value,
            status = SenderStatus.Idle,
        )
    }

    fun setCategoryEnabled(category: SecretCategory, enabled: Boolean) {
        val current = _state.value
        val next = if (enabled) current.selectedCategories + category else current.selectedCategories - category
        _state.value = current.copy(
            selectedCategories = next,
            status = SenderStatus.Idle,
        )
    }

    suspend fun generateEnvelope() {
        val current = _state.value
        if (current.selectedCategories.isEmpty()) {
            _state.value = current.copy(
                status = SenderStatus.Error("Select at least one credential category."),
            )
            return
        }

        val parsed = TransferSessionCodec.decode(current.receiverSessionString)
        val handshake = when (parsed) {
            is TransferSessionParseResult.Success -> parsed.handshake
            TransferSessionParseResult.Empty -> return setError(TransferCopy.SEND_RECEIVER_REQUIRED_ERROR)
            TransferSessionParseResult.BadPrefix -> return setError(TransferCopy.SEND_RECEIVER_NOT_TORVE_ERROR)
            // Every "shape-correct but malformed" failure becomes the
            // same user-friendly message. Crypto/encoding jargon
            // (base64url, JSON, public key) only appears in
            // diagnostics surfaces.
            TransferSessionParseResult.BadBase64 -> return setError(TransferCopy.SEND_RECEIVER_CORRUPTED_ERROR)
            is TransferSessionParseResult.BadJson -> return setError(TransferCopy.SEND_RECEIVER_CORRUPTED_ERROR)
            TransferSessionParseResult.BadReceiverPublicKey -> return setError(TransferCopy.SEND_RECEIVER_CORRUPTED_ERROR)
        }
        if (handshake.expiresAtEpochMs <= nowMs()) {
            setError(TransferCopy.SEND_RECEIVER_EXPIRED_ERROR)
            return
        }
        val receiverPublicKey = Base64Url.decodeOrNull(handshake.receiverEphemeralPublicKey)
        if (receiverPublicKey == null) {
            setError(TransferCopy.SEND_RECEIVER_CORRUPTED_ERROR)
            return
        }

        _state.value = current.copy(status = SenderStatus.Generating)
        val snapshot = runCatching {
            snapshotSecrets(current.selectedCategories)
        }.getOrElse { t ->
            setError("Could not read local credentials: ${t.message ?: t::class.simpleName}")
            return
        }
        val configSnapshot = runCatching {
            snapshotConfigEntries(current.selectedCategories, snapshot.secrets)
        }.getOrElse { t ->
            setError("Could not read companion config: ${t.message ?: t::class.simpleName}")
            return
        }
        val playlistSnapshot = runCatching {
            snapshotPlaylists(current.selectedCategories)
        }.getOrElse { t ->
            setError("Could not read local playlists: ${t.message ?: t::class.simpleName}")
            return
        }
        if (snapshot.secrets.isEmpty() && configSnapshot.entries.isEmpty() && playlistSnapshot.isEmpty()) {
            setError("No transferable credentials, config, or playlists found for the selected categories.")
            return
        }

        val envelope = runCatching {
            val combinedCategories = buildList {
                addAll(snapshot.secrets.map { it.category })
                addAll(configSnapshot.entries.map { it.category })
                if (playlistSnapshot.isNotEmpty()) add(SecretCategory.IPTV)
            }.distinct()
            protocol.seal(
                receiverPublicKey = receiverPublicKey,
                senderDeviceId = deviceIdProvider.getDeviceId(),
                senderDeviceName = deviceIdProvider.getDeviceName(),
                categories = combinedCategories,
                secrets = snapshot.secrets,
                expiresAtEpochMs = handshake.expiresAtEpochMs,
                configEntries = configSnapshot.entries,
                playlists = playlistSnapshot,
            )
        }.getOrElse { t ->
            setError("Could not seal credentials: ${t.message ?: t::class.simpleName}")
            return
        }

        // IPTV category is "non-empty" iff at least one playlist made it
        // into the envelope — drop it from the empty-categories list so
        // the UI doesn't tell the user IPTV had nothing when in fact
        // they shipped playlists.
        val effectiveEmptyCategories = if (playlistSnapshot.isNotEmpty()) {
            snapshot.categoriesWithoutSecrets.filterNot { it == SecretCategory.IPTV }
        } else {
            snapshot.categoriesWithoutSecrets
        }
        val readyBase = SenderStatus.Ready(
            envelopeJson = JSON.encodeToString(SealedSecretsEnvelope.serializer(), envelope),
            secretCount = snapshot.secrets.size,
            configCount = configSnapshot.entries.size,
            playlistCount = playlistSnapshot.size,
            includedCategories = buildList {
                addAll(snapshot.secrets.map { it.category })
                addAll(configSnapshot.entries.map { it.category })
                if (playlistSnapshot.isNotEmpty()) add(SecretCategory.IPTV)
            }.distinct(),
            categoriesWithoutSecrets = effectiveEmptyCategories,
            categoriesMissingCompanionConfig = configSnapshot.categoriesMissingCompanionConfig,
            relayDelivery = if (handshake.relaySessionId != null && relayApi != null) {
                RelayDeliveryState.Posting
            } else {
                RelayDeliveryState.NotAttempted
            },
        )
        _state.value = _state.value.copy(status = readyBase)

        // Best-effort relay delivery. Manual paste path is always still
        // valid — we never block the UI on this call.
        val relaySessionId = handshake.relaySessionId
        if (relaySessionId != null && relayApi != null) {
            postEnvelopeViaRelay(envelope, relaySessionId)
        }
    }

    private suspend fun postEnvelopeViaRelay(
        envelope: SealedSecretsEnvelope,
        relaySessionId: String,
    ) {
        val api = relayApi ?: return
        val token = runCatching { accessTokenProvider?.invoke() }.getOrNull()
        if (token.isNullOrBlank()) {
            updateRelayDelivery(RelayDeliveryState.Failed("Sign in to deliver via relay."))
            return
        }
        val result = api.postEnvelope(token, relaySessionId, envelope)
        val newState = when (result) {
            is TransferRelayResult.Success -> RelayDeliveryState.Delivered
            TransferRelayResult.Unavailable ->
                RelayDeliveryState.Failed("Relay endpoints not deployed; copy the sealed code below.")
            TransferRelayResult.NotFound ->
                RelayDeliveryState.Failed("Relay session not found — the receiver may have cancelled. Copy the sealed code below.")
            TransferRelayResult.Unauthorized ->
                RelayDeliveryState.Failed("Relay rejected auth; copy the sealed code below.")
            TransferRelayResult.Forbidden ->
                RelayDeliveryState.Failed("Relay rejected this transfer; copy the sealed code below.")
            TransferRelayResult.Expired ->
                RelayDeliveryState.Failed("The receiver code expired before this envelope was delivered. Ask the other device to generate a new code.")
            TransferRelayResult.Consumed ->
                RelayDeliveryState.Failed("This envelope was already delivered or the receiver has already imported a previous one. Generate a new sealed code if you need to resend.")
            TransferRelayResult.PayloadTooLarge ->
                RelayDeliveryState.Failed("Sealed payload exceeds the relay size cap.")
            is TransferRelayResult.NetworkError ->
                // Generic message — never include the raw error so we don't
                // leak hostnames / IPs / Apple-Network reason strings.
                RelayDeliveryState.Failed("Network unreachable. Copy the sealed code below.")
            TransferRelayResult.EmailNotVerified ->
                RelayDeliveryState.Failed(
                    "Verify your email address first — we sent a confirmation link to your inbox. Copy the sealed code below in the meantime.",
                )
            is TransferRelayResult.ServerError -> {
                val human = result.detail?.takeIf { it.isNotBlank() }
                    ?: "Couldn't reach the auto-import service (status ${result.statusCode})."
                RelayDeliveryState.Failed("$human Copy the sealed code below.")
            }
        }
        updateRelayDelivery(newState)

        if (newState is RelayDeliveryState.Delivered) {
            telemetry.emit(
                TransferTelemetryEvents.SEND_DELIVERED,
                transferAttrs(
                    platform = platform,
                    role = TransferRole.SENDER,
                    state = TransferTelemetryState.DELIVERED,
                ),
            )
            attemptTracker?.record(AttemptRole.SENDER, AttemptOutcome.DELIVERED)
        } else if (newState is RelayDeliveryState.Failed) {
            val category = result.toErrorCategory() ?: TransferTelemetryErrorCategory.OTHER
            telemetry.emit(
                TransferTelemetryEvents.SEND_FAILED,
                transferAttrs(
                    platform = platform,
                    role = TransferRole.SENDER,
                    state = TransferTelemetryState.FAILED,
                    errorCategory = category,
                ),
            )
            attemptTracker?.record(
                AttemptRole.SENDER,
                AttemptOutcome.FAILED,
                errorCategory = category,
            )
        }
    }

    private fun updateRelayDelivery(next: RelayDeliveryState) {
        val ready = _state.value.status as? SenderStatus.Ready ?: return
        _state.value = _state.value.copy(status = ready.copy(relayDelivery = next))
    }

    private suspend fun snapshotSecrets(categories: Set<SecretCategory>): SecretSnapshot {
        val secrets = mutableListOf<SecretRecord>()
        val emptyCategories = mutableListOf<SecretCategory>()
        for (category in categories.sortedBy { it.name }) {
            val categorySecrets = TransferSecretCatalog.keysFor(category)
                .flatMap { key -> snapshotSecretKey(category, key) }
            if (categorySecrets.isEmpty()) emptyCategories += category
            secrets += categorySecrets
        }
        return SecretSnapshot(
            secrets = secrets,
            categoriesWithoutSecrets = emptyCategories,
        )
    }

    private suspend fun snapshotSecretKey(
        category: SecretCategory,
        key: IntegrationSecretKey,
    ): List<SecretRecord> {
        val records = mutableListOf<SecretRecord>()
        secretStore.get(key)
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                records += SecretRecord(
                    category = category,
                    key = key.name,
                    value = value,
                )
            }
        if (records.isEmpty()) {
            legacyTraktPreferenceKey(key)
                ?.let { prefKey -> prefsRepo.getString(prefKey)?.takeIf { it.isNotBlank() } }
                ?.let { value ->
                    records += SecretRecord(
                        category = category,
                        key = key.name,
                        value = value,
                    )
                }
        }

        val subKeys = runCatching { secretStore.getSubKeys(key) }
            .getOrDefault(emptyList())
            .distinct()
            .filter { it.isNotBlank() }
        subKeys.forEach { subKey ->
            val value = secretStore.get(key, subKey)?.takeIf { it.isNotBlank() }
            if (value != null) {
                records += SecretRecord(
                    category = category,
                    key = key.name,
                    value = value,
                    subKey = subKey,
                )
            }
        }
        return records
    }

    private fun legacyTraktPreferenceKey(key: IntegrationSecretKey): String? = when (key) {
        IntegrationSecretKey.TRAKT_ACCESS_TOKEN -> "trakt_access_token"
        IntegrationSecretKey.TRAKT_REFRESH_TOKEN -> "trakt_refresh_token"
        else -> null
    }

    /**
     * Pulls non-secret companion config (e.g. Plex/Jellyfin server
     * URL) needed to make the corresponding secret usable on the
     * receiver. Filters keys through [configKeyAllowlist] so the
     * sender can never ship something the receiver would reject.
     */
    private suspend fun snapshotConfigEntries(
        selected: Set<SecretCategory>,
        shippedSecrets: List<SecretRecord>,
    ): ConfigSnapshot {
        val entries = mutableListOf<ConfigEntry>()
        val missing = mutableListOf<SecretCategory>()
        if (SecretCategory.PLEX_JELLYFIN in selected) {
            val plexShipped = shippedSecrets.any {
                it.key == IntegrationSecretKey.PLEX_ACCESS_TOKEN.name
            }
            val jellyfinShipped = shippedSecrets.any {
                it.key == IntegrationSecretKey.JELLYFIN_API_KEY.name
            }
            val plexUrl = readConfigKey(DefaultConfigKeyAllowlist.PLEX_SERVER_URL)
            val jellyfinUrl = readConfigKey(DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL)
            plexUrl?.let { entries += ConfigEntry(SecretCategory.PLEX_JELLYFIN, DefaultConfigKeyAllowlist.PLEX_SERVER_URL, it) }
            jellyfinUrl?.let { entries += ConfigEntry(SecretCategory.PLEX_JELLYFIN, DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL, it) }
            val plexNeedsButMissing = plexShipped && plexUrl == null
            val jellyfinNeedsButMissing = jellyfinShipped && jellyfinUrl == null
            if (plexNeedsButMissing || jellyfinNeedsButMissing) {
                missing += SecretCategory.PLEX_JELLYFIN
            }
        }
        return ConfigSnapshot(entries = entries, categoriesMissingCompanionConfig = missing)
    }

    private suspend fun readConfigKey(key: String): String? {
        if (!configKeyAllowlist.allows(key)) return null
        val value = runCatching { prefsRepo.getString(key) }.getOrNull()
        return value?.takeIf { it.isNotBlank() }
    }

    /**
     * Snapshot every local IPTV playlist when [SecretCategory.IPTV] is
     * selected. Only Xtream playlists with full credentials and M3U
     * playlists with a URL are included — degenerate rows (missing
     * server/url) are skipped to avoid creating broken entries on the
     * receiver. Returns an empty list when no channel repo is wired,
     * IPTV isn't selected, or there are no transferable playlists.
     */
    private suspend fun snapshotPlaylists(selected: Set<SecretCategory>): List<TransferPlaylistDto> {
        if (SecretCategory.IPTV !in selected) return emptyList()
        val repo = channelRepo ?: return emptyList()
        return runCatching { repo.getPlaylists() }
            .getOrDefault(emptyList())
            .mapNotNull { playlist ->
                when (playlist.type) {
                    PlaylistType.XTREAM -> {
                        val server = playlist.server?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val username = playlist.username?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val password = playlist.password?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        TransferPlaylistDto(
                            playlistId = playlist.id,
                            name = playlist.name,
                            playlistType = "xtream",
                            server = server,
                            username = username,
                            password = password,
                        )
                    }
                    PlaylistType.M3U -> {
                        val url = playlist.url.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        TransferPlaylistDto(
                            playlistId = playlist.id,
                            name = playlist.name,
                            playlistType = "m3u",
                            url = url,
                            epgUrl = playlist.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    }
                }
            }
    }

    private fun setError(message: String) {
        _state.value = _state.value.copy(status = SenderStatus.Error(message))
    }

    private data class SecretSnapshot(
        val secrets: List<SecretRecord>,
        val categoriesWithoutSecrets: List<SecretCategory>,
    )

    private data class ConfigSnapshot(
        val entries: List<ConfigEntry>,
        val categoriesMissingCompanionConfig: List<SecretCategory>,
    )

    private companion object {
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

data class SenderState(
    val receiverSessionString: String = "",
    val selectedCategories: Set<SecretCategory>,
    val status: SenderStatus = SenderStatus.Idle,
)

sealed interface SenderStatus {
    data object Idle : SenderStatus
    data object Generating : SenderStatus
    data class Error(val message: String) : SenderStatus
    data class Ready(
        val envelopeJson: String,
        val secretCount: Int,
        val configCount: Int = 0,
        /** Number of IPTV/M3U/Xtream playlists shipped inside the envelope. */
        val playlistCount: Int = 0,
        val includedCategories: List<SecretCategory>,
        val categoriesWithoutSecrets: List<SecretCategory>,
        val categoriesMissingCompanionConfig: List<SecretCategory> = emptyList(),
        val relayDelivery: RelayDeliveryState = RelayDeliveryState.NotAttempted,
    ) : SenderStatus
}

/**
 * Lifecycle of pushing a sealed envelope through the backend relay.
 * Driven after the envelope is sealed; never short-circuits the manual
 * paste path.
 */
sealed interface RelayDeliveryState {
    /** Receiver didn't include a relay session id, or relay isn't wired. */
    data object NotAttempted : RelayDeliveryState

    /** POST in flight. */
    data object Posting : RelayDeliveryState

    /** Backend accepted the envelope; receiver will pull it on next poll. */
    data object Delivered : RelayDeliveryState

    /** Relay POST failed. [reason] is user-facing copy. */
    data class Failed(val reason: String) : RelayDeliveryState
}
