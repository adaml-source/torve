package com.torve.domain.transfer

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.PreferencesRepository

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

        if (resolvedSecrets.isEmpty() && resolvedConfig.isEmpty()) {
            return TransferApplyResult.NothingApplied(
                skippedKeyNames = skippedSecretNames.toList(),
                skippedConfigKeys = skippedConfigKeys.toList(),
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
                )
            }
        }

        nonceStore.markConsumed(payload.transferNonce)
        val secretsApplied = written.count { it is ApplyTarget.Secret }
        val configApplied = written.count { it is ApplyTarget.Config }
        return TransferApplyResult.Success(
            applied = secretsApplied,
            configApplied = configApplied,
            skippedKeyNames = skippedSecretNames.toList(),
            skippedConfigKeys = skippedConfigKeys.toList(),
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
    ): TransferApplyResult.StoreFailure = TransferApplyResult.StoreFailure(
        message = message,
        rollbackAttempted = false,
        rollbackSucceeded = false,
        applied = 0,
        skippedKeyNames = skippedSecretNames,
        skippedConfigKeys = skippedConfigKeys,
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

    private data class KeyAddress(
        val key: IntegrationSecretKey,
        val subKey: String?,
    )

    private sealed interface ApplyTarget {
        data class Secret(val addr: KeyAddress) : ApplyTarget
        data class Config(val key: String) : ApplyTarget
    }
}

/** Outcome of [SecretsTransferApplier.apply]. */
sealed interface TransferApplyResult {
    data class Success(
        /** Count of secret records written to [IntegrationSecretStore]. */
        val applied: Int,
        /** Count of [ConfigEntry] records written to [PreferencesRepository]. */
        val configApplied: Int = 0,
        /** Secret enum names from the payload we couldn't resolve — never wrote. */
        val skippedKeyNames: List<String>,
        /** Config keys rejected by the receiver allowlist — never wrote. */
        val skippedConfigKeys: List<String> = emptyList(),
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
    ) : TransferApplyResult
}
