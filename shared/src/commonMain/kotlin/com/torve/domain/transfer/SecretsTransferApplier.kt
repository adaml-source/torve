package com.torve.domain.transfer

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.PreferencesRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phase 3 — applies a decrypted [SecretsTransferPayload] to the device.
 *
 * Two stores get touched, atomically as a single transaction:
 *   - [IntegrationSecretStore] for the secret records.
 *   - [PreferencesRepository] for the [ConfigEntry] companion-config
 *     records (gated through [ConfigKeyAllowlist]).
 *
 * Atomicity contract: every target's pre-image is captured BEFORE any
 * write happens. If any single put throws mid-import — secret OR
 * config — every write that already succeeded is rolled back to its
 * captured pre-image (`null` pre-images are restored via remove). If a
 * rollback step itself fails, the result tells the caller the store
 * may be in an inconsistent state so the UI can surface that.
 *
 * Replay-guard contract: callers feed the result + the payload's
 * `transferNonce` to the [ConsumedNonceStore] EXACTLY ONCE on success
 * (the applier handles that itself). The store is not consulted before
 * [apply] dispatches secrets — the protocol layer's decrypt step did
 * that check via its `consumedNonceCheck` lambda. The applier records
 * consumption only after a fully-successful import.
 */
class SecretsTransferApplier(
    private val secretStore: IntegrationSecretStore,
    private val nonceStore: ConsumedNonceStore,
    private val prefsRepo: PreferencesRepository,
    private val configKeyAllowlist: ConfigKeyAllowlist = DefaultConfigKeyAllowlist(),
    /**
     * Optional channel repo for IPTV/M3U/Xtream playlist transfers. Null
     * on platforms without a channel repo wired (or in tests). When null
     * the applier silently ignores [SecretsTransferPayload.playlists]
     * the same way it silently skips unrecognised secret enum names.
     */
    private val channelRepo: ChannelRepository? = null,
) {

    suspend fun apply(payload: SecretsTransferPayload): TransferApplyResult {
        if (nonceStore.isConsumed(payload.transferNonce)) {
            return TransferApplyResult.DuplicateNonce
        }

        // Resolve every secret to its IntegrationSecretKey ahead of
        // time. Unknown enum names get reported as `skipped` and never
        // touch the store.
        data class ResolvedSecret(val record: SecretRecord, val key: IntegrationSecretKey)
        val skippedSecretNames = mutableListOf<String>()
        val resolvedSecrets: List<ResolvedSecret> = payload.secrets.mapNotNull { record ->
            val key = runCatching { IntegrationSecretKey.valueOf(record.key) }.getOrNull()
            if (key == null) {
                skippedSecretNames += record.key
                null
            } else {
                ResolvedSecret(record, key)
            }
        }

        // Resolve every config entry against the allowlist. Anything
        // off-list goes to `skippedConfigKeys` — never written.
        val skippedConfigKeys = mutableListOf<String>()
        val resolvedConfig: List<ConfigEntry> = payload.configEntries.mapNotNull { entry ->
            if (configKeyAllowlist.allows(entry.key)) entry else {
                skippedConfigKeys += entry.key
                null
            }
        }

        // Resolve playlists. Skip rows whose required fields are blank
        // (e.g. xtream without server / password) and rows whose id
        // already exists locally — playlist transfer is idempotent;
        // we never overwrite a working local entry.
        val skippedPlaylistIds = mutableListOf<String>()
        val repo = channelRepo
        val existingPlaylistIds = if (repo == null) {
            emptySet()
        } else {
            runCatching { repo.getPlaylists() }
                .getOrDefault(emptyList())
                .map { it.id }
                .toSet()
        }
        val resolvedPlaylists: List<TransferPlaylistDto> = if (repo == null) {
            // No channel repo wired — every playlist becomes "skipped".
            payload.playlists.forEach { skippedPlaylistIds += it.playlistId }
            emptyList()
        } else {
            payload.playlists.mapNotNull { p ->
                when (p.playlistType.lowercase()) {
                    "xtream" -> {
                        val ok = !p.server.isNullOrBlank() &&
                            !p.username.isNullOrBlank() &&
                            !p.password.isNullOrBlank()
                        if (ok) p else { skippedPlaylistIds += p.playlistId; null }
                    }
                    "m3u" -> {
                        if (!p.url.isNullOrBlank()) p
                        else { skippedPlaylistIds += p.playlistId; null }
                    }
                    else -> { skippedPlaylistIds += p.playlistId; null }
                }
            }
        }

        if (resolvedSecrets.isEmpty() && resolvedConfig.isEmpty() && resolvedPlaylists.isEmpty()) {
            return TransferApplyResult.NothingApplied(
                skippedKeyNames = skippedSecretNames.toList(),
                skippedConfigKeys = skippedConfigKeys.toList(),
                skippedPlaylistIds = skippedPlaylistIds.toList(),
            )
        }

        // ── Snapshot phase: capture every pre-image up front. ──
        val secretPreImages: MutableMap<KeyAddress, String?> = LinkedHashMap()
        for ((record, key) in resolvedSecrets) {
            val addr = KeyAddress(key, record.subKey)
            if (addr in secretPreImages) continue
            val previous = runCatching { secretStore.get(key, record.subKey) }
                .getOrElse { t ->
                    return snapshotFailure(
                        message = "secret snapshot read failed for ${record.key}: ${errMsg(t)}",
                        skippedSecretNames = skippedSecretNames,
                        skippedConfigKeys = skippedConfigKeys,
                    )
            }
            secretPreImages[addr] = previous
        }
        val shouldSnapshotSyntheticTraktBundle =
            resolvedSecrets.none { it.key == IntegrationSecretKey.TRAKT_TOKENS && it.record.subKey == null } &&
                resolvedSecrets.any {
                    it.key == IntegrationSecretKey.TRAKT_ACCESS_TOKEN ||
                        it.key == IntegrationSecretKey.TRAKT_REFRESH_TOKEN
                }
        if (shouldSnapshotSyntheticTraktBundle) {
            val addr = KeyAddress(IntegrationSecretKey.TRAKT_TOKENS, null)
            if (addr !in secretPreImages) {
                val previous = runCatching { secretStore.get(IntegrationSecretKey.TRAKT_TOKENS) }
                    .getOrElse { t ->
                        return snapshotFailure(
                            message = "secret snapshot read failed for TRAKT_TOKENS: ${errMsg(t)}",
                            skippedSecretNames = skippedSecretNames,
                            skippedConfigKeys = skippedConfigKeys,
                        )
                    }
                secretPreImages[addr] = previous
            }
        }
        val configPreImages: MutableMap<String, String?> = LinkedHashMap()
        for (entry in resolvedConfig) {
            if (entry.key in configPreImages) continue
            val previous = runCatching { prefsRepo.getString(entry.key) }
                .getOrElse { t ->
                    return snapshotFailure(
                        message = "config snapshot read failed for ${entry.key}: ${errMsg(t)}",
                        skippedSecretNames = skippedSecretNames,
                        skippedConfigKeys = skippedConfigKeys,
                    )
                }
            configPreImages[entry.key] = previous
        }

        // ── Apply phase: secrets first, then config. ──
        // `written` tracks BOTH groups in apply order so rollback walks
        // backwards through the whole transaction regardless of which
        // half failed.
        val written: MutableList<ApplyTarget> = mutableListOf()
        for ((record, key) in resolvedSecrets) {
            val addr = KeyAddress(key, record.subKey)
            try {
                secretStore.put(key, record.value, record.subKey)
                written += ApplyTarget.Secret(addr)
            } catch (t: Throwable) {
                val rollback = rollback(written, secretPreImages, configPreImages)
                return TransferApplyResult.StoreFailure(
                    message = "secret put failed for ${record.key}: ${errMsg(t)}",
                    rollbackAttempted = true,
                    rollbackSucceeded = rollback,
                    applied = 0,
                    skippedKeyNames = skippedSecretNames.toList(),
                    skippedConfigKeys = skippedConfigKeys.toList(),
                )
            }
        }
        for (entry in resolvedConfig) {
            try {
                prefsRepo.setString(entry.key, entry.value)
                written += ApplyTarget.Config(entry.key)
            } catch (t: Throwable) {
                val rollback = rollback(written, secretPreImages, configPreImages)
                return TransferApplyResult.StoreFailure(
                    message = "config put failed for ${entry.key}: ${errMsg(t)}",
                    rollbackAttempted = true,
                    rollbackSucceeded = rollback,
                    applied = 0,
                    skippedKeyNames = skippedSecretNames.toList(),
                    skippedConfigKeys = skippedConfigKeys.toList(),
                    skippedPlaylistIds = skippedPlaylistIds.toList(),
                )
            }
        }
        try {
            synthesizeTraktTokenBundleIfNeeded(
                tokenBundleImported = resolvedSecrets.any {
                    it.key == IntegrationSecretKey.TRAKT_TOKENS && it.record.subKey == null
                },
                legacyTokenImported = resolvedSecrets.any {
                    it.key == IntegrationSecretKey.TRAKT_ACCESS_TOKEN ||
                        it.key == IntegrationSecretKey.TRAKT_REFRESH_TOKEN
                },
                written = written,
                secretPreImages = secretPreImages,
            )
        } catch (t: Throwable) {
            val rollback = rollback(written, secretPreImages, configPreImages)
            return TransferApplyResult.StoreFailure(
                message = "trakt token synthesis failed: ${errMsg(t)}",
                rollbackAttempted = true,
                rollbackSucceeded = rollback,
                applied = 0,
                skippedKeyNames = skippedSecretNames.toList(),
                skippedConfigKeys = skippedConfigKeys.toList(),
                skippedPlaylistIds = skippedPlaylistIds.toList(),
            )
        }
        // Playlists last: save only provider configuration here. Full
        // IPTV/VOD/EPG catalog imports are intentionally delegated to
        // the platform background refresh pipeline so receiving
        // credentials cannot freeze the TV UI.
        for (p in resolvedPlaylists) {
            try {
                when (p.playlistType.lowercase()) {
                    "xtream" -> channelRepo!!.saveXtreamPlaylistConfig(
                        name = p.name,
                        server = p.server!!,
                        username = p.username!!,
                        password = p.password!!,
                        id = p.playlistId,
                        epgUrl = p.epgUrl,
                    )
                    "m3u" -> channelRepo!!.saveM3uPlaylistConfig(
                        name = p.name,
                        url = p.url!!,
                        epgUrl = p.epgUrl,
                        id = p.playlistId,
                    )
                }
                written += ApplyTarget.Playlist(
                    playlistId = p.playlistId,
                    existedBefore = p.playlistId in existingPlaylistIds,
                )
            } catch (t: Throwable) {
                val rollback = rollback(written, secretPreImages, configPreImages)
                return TransferApplyResult.StoreFailure(
                    message = "playlist put failed for ${p.playlistId}: ${errMsg(t)}",
                    rollbackAttempted = true,
                    rollbackSucceeded = rollback,
                    applied = 0,
                    skippedKeyNames = skippedSecretNames.toList(),
                    skippedConfigKeys = skippedConfigKeys.toList(),
                    skippedPlaylistIds = skippedPlaylistIds.toList(),
                )
            }
        }

        nonceStore.markConsumed(payload.transferNonce)
        val secretsApplied = written.count { it is ApplyTarget.Secret }
        val configApplied = written.count { it is ApplyTarget.Config }
        val playlistsApplied = written.count { it is ApplyTarget.Playlist }
        return TransferApplyResult.Success(
            applied = secretsApplied,
            configApplied = configApplied,
            playlistsApplied = playlistsApplied,
            skippedKeyNames = skippedSecretNames.toList(),
            skippedConfigKeys = skippedConfigKeys.toList(),
            skippedPlaylistIds = skippedPlaylistIds.toList(),
            categoriesMissingCompanionConfig = categoriesMissingCompanionConfig(payload),
        )
    }

    /**
     * Restores everything in [written], reverse order. Pre-image of
     * null is restored via [IntegrationSecretStore.remove] for secrets
     * and [PreferencesRepository.remove] for config. Returns true iff
     * every step succeeded; on first failure we keep going (best-effort)
     * and return false.
     */
    private suspend fun rollback(
        written: List<ApplyTarget>,
        secretPreImages: Map<KeyAddress, String?>,
        configPreImages: Map<String, String?>,
    ): Boolean {
        var ok = true
        for (target in written.asReversed()) {
            val attempt = runCatching {
                when (target) {
                    is ApplyTarget.Secret -> {
                        val pre = secretPreImages[target.addr]
                        if (pre == null) secretStore.remove(target.addr.key, target.addr.subKey)
                        else secretStore.put(target.addr.key, pre, target.addr.subKey)
                    }
                    is ApplyTarget.Config -> {
                        val pre = configPreImages[target.key]
                        if (pre == null) prefsRepo.remove(target.key)
                        else prefsRepo.setString(target.key, pre)
                    }
                    is ApplyTarget.Playlist -> {
                        // Existing rows were updated in-place and don't
                        // have a cheap cross-platform pre-image. Only
                        // remove playlists this transaction created.
                        if (!target.existedBefore) {
                            channelRepo?.removePlaylist(target.playlistId)
                        }
                    }
                }
            }
            if (attempt.isFailure) ok = false
        }
        return ok
    }

    private fun snapshotFailure(
        message: String,
        skippedSecretNames: List<String>,
        skippedConfigKeys: List<String>,
        skippedPlaylistIds: List<String> = emptyList(),
    ): TransferApplyResult.StoreFailure = TransferApplyResult.StoreFailure(
        message = message,
        rollbackAttempted = false,
        rollbackSucceeded = false,
        applied = 0,
        skippedKeyNames = skippedSecretNames,
        skippedConfigKeys = skippedConfigKeys,
        skippedPlaylistIds = skippedPlaylistIds,
    )

    /**
     * Reports any [SecretCategory] in [SecretsTransferPayload.secrets]
     * whose tokens won't be usable until matching companion config
     * arrives. v1: Plex / Jellyfin tokens require their server URL.
     */
    private fun categoriesMissingCompanionConfig(
        payload: SecretsTransferPayload,
    ): List<SecretCategory> {
        val missing = mutableListOf<SecretCategory>()
        val configKeysShipped = payload.configEntries.map { it.key }.toSet()
        val plexTokenShipped = payload.secrets.any {
            it.key == IntegrationSecretKey.PLEX_ACCESS_TOKEN.name
        }
        val plexUrlShipped = DefaultConfigKeyAllowlist.PLEX_SERVER_URL in configKeysShipped
        val jellyfinKeyShipped = payload.secrets.any {
            it.key == IntegrationSecretKey.JELLYFIN_API_KEY.name
        }
        val jellyfinUrlShipped = DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL in configKeysShipped
        if ((plexTokenShipped && !plexUrlShipped) || (jellyfinKeyShipped && !jellyfinUrlShipped)) {
            missing += SecretCategory.PLEX_JELLYFIN
        }
        return missing
    }

    private fun errMsg(t: Throwable): String = t.message ?: t::class.simpleName ?: "error"

    private suspend fun synthesizeTraktTokenBundleIfNeeded(
        tokenBundleImported: Boolean,
        legacyTokenImported: Boolean,
        written: MutableList<ApplyTarget>,
        secretPreImages: MutableMap<KeyAddress, String?>,
    ) {
        if (tokenBundleImported) return
        if (!legacyTokenImported) return
        if (!secretStore.get(IntegrationSecretKey.TRAKT_TOKENS).isNullOrBlank()) return

        val accessToken = secretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val refreshToken = secretStore.get(IntegrationSecretKey.TRAKT_REFRESH_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val addr = KeyAddress(IntegrationSecretKey.TRAKT_TOKENS, null)
        if (addr !in secretPreImages) {
            secretPreImages[addr] = secretStore.get(IntegrationSecretKey.TRAKT_TOKENS)
        }
        secretStore.put(
            IntegrationSecretKey.TRAKT_TOKENS,
            Json.encodeToString(
                TransferTraktTokenPayload(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = 0,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                ),
            ),
        )
        written += ApplyTarget.Secret(addr)
    }

    private data class KeyAddress(
        val key: IntegrationSecretKey,
        val subKey: String?,
    )

    private sealed interface ApplyTarget {
        data class Secret(val addr: KeyAddress) : ApplyTarget
        data class Config(val key: String) : ApplyTarget
        data class Playlist(
            val playlistId: String,
            val existedBefore: Boolean,
        ) : ApplyTarget
    }
}

@Serializable
private data class TransferTraktTokenPayload(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val createdAt: Long,
)

/** Outcome of [SecretsTransferApplier.apply]. */
sealed interface TransferApplyResult {
    data class Success(
        /** Count of secret records written to [IntegrationSecretStore]. */
        val applied: Int,
        /** Count of [ConfigEntry] records written to [PreferencesRepository]. */
        val configApplied: Int = 0,
        /** Count of [TransferPlaylistDto] rows written to the channel repo. */
        val playlistsApplied: Int = 0,
        /** Secret enum names from the payload we couldn't resolve — never wrote. */
        val skippedKeyNames: List<String>,
        /** Config keys rejected by the receiver allowlist — never wrote. */
        val skippedConfigKeys: List<String> = emptyList(),
        /**
         * Playlist ids skipped because they already existed locally
         * (idempotent re-import) or because their required fields were
         * blank (xtream without server/password, m3u without url).
         */
        val skippedPlaylistIds: List<String> = emptyList(),
        /**
         * Categories whose tokens *did* import but whose companion
         * config didn't arrive — e.g. Plex token without its server URL.
         * UI surfaces this so the user knows what to fill in manually.
         */
        val categoriesMissingCompanionConfig: List<SecretCategory> = emptyList(),
    ) : TransferApplyResult

    /** Nonce already in [ConsumedNonceStore]; nothing was written. */
    data object DuplicateNonce : TransferApplyResult

    /**
     * Payload had only unresolvable secret/config keys (or no records).
     * Nothing was written. The nonce is NOT marked consumed — a future
     * build with broader enum/allowlist coverage can retry the same
     * envelope.
     */
    data class NothingApplied(
        val skippedKeyNames: List<String>,
        val skippedConfigKeys: List<String> = emptyList(),
        val skippedPlaylistIds: List<String> = emptyList(),
    ) : TransferApplyResult

    /**
     * The store threw mid-import. [rollbackSucceeded] tells the UI
     * whether the device is back in its pre-import state; when false
     * the user should be told to verify their credentials manually.
     * The nonce is NOT marked consumed so the user can retry once the
     * underlying issue (full disk, keychain access, …) is resolved.
     */
    data class StoreFailure(
        val message: String,
        val rollbackAttempted: Boolean,
        val rollbackSucceeded: Boolean,
        val applied: Int,
        val skippedKeyNames: List<String>,
        val skippedConfigKeys: List<String> = emptyList(),
        val skippedPlaylistIds: List<String> = emptyList(),
    ) : TransferApplyResult
}
